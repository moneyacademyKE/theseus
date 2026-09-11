(ns bb-agent.telegram-flow
  "The turn's process rendered as ONE growing Telegram message — an HTML
   <blockquote expandable> edited in place — instead of one message per
   tool call. Port of the OpenCrabs flow semantics (channels/telegram/
   flow.rs), reduced to Theseus's single-threaded poll model:

   - per-tool line: <b>{⚙️|✅|❌} {name}</b> <code>{context}</code>;
     the icon flips when the tool's outcome lands;
   - two or more entries wrap in the expandable blockquote with a live
     footer (⚙️ Working • N tool calls • 45s); a lone line stays a plain
     one-liner;
   - long turns compress rather than drop: consecutive completed calls to
     the same tool collapse into one counted line (✅ read_file ×12) —
     sequence and totals are preserved, only per-call contexts fold;
   - settle rewrites the footer to ✅ Finished / ❌ Failed with the
     wall-clock duration from turn start;
   - the model's final answer is never part of the flow — it stays the
     clean message at the bottom;
   - contexts are redacted and middle-truncated; a flow failure never
     blocks the turn (try/catch at every Telegram call)."
  (:require [bb-agent.session :as session]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-presence :as presence]
            [clojure.string :as str]))

(def ^:private context-cap 64)

(def ^:private render-cap 3900)
;; Telegram kills message text past 4096 chars — and a flow message grows
;; one line per tool call, so long turns hit the wall exactly when
;; visibility matters most. The render adapts instead of dropping
;; information: full detail while it fits; past the budget, run-length
;; compression folds consecutive completed calls to the same tool into one
;; counted line (sequence and totals survive — only per-call contexts
;; fold); the head+tail net with a true-count marker fires only when even
;; the compressed form overflows. The footer — the live status — is never
;; truncated.

(defn- compress-entries
  "Pure: entry maps → run-length groups. Consecutive :ok calls to the same
   tool collapse into one {:count N} group; :running and :failed entries
   always stay individual — the live call and every failure keep their
   own line and context."
  [entries]
  (reduce (fn [groups e]
            (let [top (peek groups)]
              (if (and (= :ok (:status e))
                       (= :ok (:status top))
                       (= (:name e) (:name top)))
                (update groups (dec (count groups)) update :count inc)
                (conj groups (assoc e :count 1)))))
          []
          entries))

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- middle-truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (let [head (quot n 2)
            tail (- n head 1)]
        (str (subs s 0 head) "…" (subs s (- (count s) tail)))))))

(defn- tool-context
  "The one-line hint for a tool call: the command for shell, the path for
   file tools, a bounded pr-str otherwise. Redacted — args can carry
   secrets (a token inside a curl command)."
  [tool-name args]
  (let [raw (case tool-name
              "shell" (:cmd args)
              ("file-write" "file-read" "file-edit") (:path args)
              (pr-str args))]
    (middle-truncate (session/redact-secrets (str raw)) context-cap)))

(defn- status-icon [status]
  (case status
    :running "⚙️"
    :ok "✅"
    :failed "❌"))

(defn- line-html [{:keys [name context status]}]
  (let [label (str (status-icon status) " " (esc name))]
    (if (str/blank? context)
      (str "<b>" label "</b>")
      (str "<b>" label "</b> <code>" (esc context) "</code>"))))

(defn- group-line-html
  "One rendered line per group: a compressed run shows icon, name, and its
   true count; a singleton renders exactly as an individual entry."
  [{:keys [name context status count]}]
  (if (> count 1)
    (str "<b>" (status-icon status) " " (esc name) " ×" count "</b>")
    (line-html {:name name :context context :status status})))

(defn- fit-groups
  "Pure: compressed groups → a single string under budget chars, keeping
   the head line and the longest tail that fits. The marker accounts for
   the dropped middle in true CALLS (group counts summed), never lines."
  [groups budget]
  (let [head (group-line-html (first groups))
        tail (vec (rest groups))
        marker-for (fn [dropped] (str "… " (reduce + (map :count dropped)) " earlier calls"))
        render (fn [k]
                 (str/join "\n"
                           (concat [head]
                                   (when (< k (count tail))
                                     [(marker-for (subvec tail 0 (- (count tail) k)))])
                                   (map group-line-html (subvec tail (- (count tail) k))))))]
    (loop [k (count tail)]
      (if (or (zero? k) (<= (count (render k)) budget))
        (render k)
        (recur (dec k))))))

(defn- humanize-duration [ms]
  (let [secs (max 0 (quot ms 1000))]
    (if (< secs 60)
      (str secs "s")
      (str (quot secs 60) " min " (mod secs 60) "s"))))

(defn render-flow
  "Pure: flow state → final Telegram HTML. Lone tool line stays a plain
   one-liner; anything bigger wraps in the expandable blockquote with the
   footer line (live ⚙️ Working… / settled ✅ Finished / ❌ Failed).
   Long renders adapt rather than drop: full detail while it fits, then
   run-length compression, with head+tail truncation only as the final
   net. The footer survives every tier."
  [{:keys [entries started-ms settled] :as _state}]
  (let [line-strs (map line-html entries)
        n (count entries)
        dur (humanize-duration (- (System/currentTimeMillis) (or started-ms 0)))]
    (if (<= n 1)
      (or (first line-strs) "")
      (let [footer (if settled
                     (if (:ok? settled)
                       (str "✅ Finished (" n " tool calls, " dur ")")
                       (str "❌ Failed (" n " tool calls, " dur ")"))
                     (str "⚙️ Working • " n " tool " (if (= 1 n) "call" "calls") " • " dur))
            wrapper (count "<blockquote expandable></blockquote>\n")
            budget (max 0 (- render-cap wrapper (count footer)))
            full (str/join "\n" line-strs)
            body (if (<= (count full) budget)
                   full
                   (fit-groups (compress-entries entries) budget))]
        (str "<blockquote expandable>" body "</blockquote>\n" footer)))))

(defn make-flow
  "Per-turn flow state: entries in order, the flow message id once sent,
   and the turn's start time. Nothing is sent until the first entry."
  []
  (atom {:entries [] :msg-id nil :started-ms (System/currentTimeMillis)}))

(defn- upsert!
  "Send the flow message on first entry, edit it in place after. Never
   throws into the turn — a broken status surface must not kill work.
   Re-asserts typing immediately so flow edits do not cancel the client's
   active typing indicator."
  [flow telegram-cfg chat-id thread-id]
  (try
    (let [state @flow
          html (render-flow state)]
      (if-let [mid (:msg-id state)]
        (do
          (delivery/edit-message-text! telegram-cfg chat-id mid html {:parse-mode "HTML"})
          (presence/typing! telegram-cfg chat-id {:thread-id thread-id}))
        (when (seq (:entries state))
          (let [{:keys [message-id]} (delivery/send-message! telegram-cfg chat-id html
                                                             {:thread-id thread-id
                                                              :parse-mode "HTML"})]
            (when message-id
              (swap! flow assoc :msg-id message-id))
            (presence/typing! telegram-cfg chat-id {:thread-id thread-id})))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "telegram flow upsert failed: " (.getMessage e)))))))

(defn flow-emit
  "The :status/emit handler for core/run-turn!: one entry per tool call,
   icon flipped on completion. Telegram I/O is synchronous here — fine at
   Theseus's scale (a handful of calls per turn)."
  [flow telegram-cfg chat-id thread-id]
  (fn [{:keys [status tool args]}]
    (case status
      :tool/call
      (do (swap! flow update :entries conj
                 {:name tool :context (tool-context tool args) :status :running})
          (upsert! flow telegram-cfg chat-id thread-id))

      :tool/done
      (do (swap! flow
                 (fn [s]
                   (let [idx (last (keep-indexed
                                    (fn [i e]
                                      (when (and (= tool (:name e))
                                                 (= :running (:status e)))
                                        i))
                                    (:entries s)))]
                     (if idx
                       (assoc-in s [:entries idx :status]
                                 (if (:ok? args) :ok :failed))
                       s))))
          (upsert! flow telegram-cfg chat-id thread-id))

      nil)))

(defn settle!
  "Final flow edit: the footer becomes ✅ Finished / ❌ Failed with the
   turn's wall-clock duration. No-op when the turn never called a tool."
  [flow telegram-cfg chat-id ok?]
  (when (:msg-id @flow)
    (swap! flow assoc :settled {:ok? ok?})
    (try
      (delivery/edit-message-text! telegram-cfg chat-id (:msg-id @flow)
                                   (render-flow @flow) {:parse-mode "HTML"})
      (catch Exception e
        (binding [*out* *err*]
          (println (str "telegram flow settle failed: " (.getMessage e)
                        " data=" (pr-str (ex-data e)))))))))

(defn with-flow!
  "Run f (a thunk receiving the emit fn) inside a fresh flow: tool calls
   stream into ONE live topic message, settled ✅/❌ when f returns or
   throws. Goal commands run here — the authoring phase is where the
   minutes go, and until now it was invisible."
  [telegram-cfg chat-id thread-id f]
  (let [flow (make-flow)
        reply (try
                (f (flow-emit flow telegram-cfg chat-id thread-id))
                (catch Exception e
                  (settle! flow telegram-cfg chat-id false)
                  (throw e)))]
    (settle! flow telegram-cfg chat-id true)
    reply))
