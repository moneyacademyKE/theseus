(ns bb-agent.compression
  "Hierarchical context compaction for long conversations.

   Pure compaction mechanics plus an atomic EDN checkpoint sidecar
   (`state/session-compaction/<id>.edn`):
   - Unified 4 chars/token estimation matching `bb-agent.brain`.
   - Model-aware history budget minus actual system-prompt overhead.
   - Deterministic 1-line tool-result breadcrumbs instead of blind erasure.
   - Structured memory ledger (:facts, :decisions, :files, :pending) merged
     additively keeping the newest bounded tail, with plain-prose fallback.
   - Turn-range provenance tags ([Turns A-B]) aligned with `session/load-turn-range`.
   - Persistent rolling checkpoints (max 3 tiers) so unchanged history is
     never re-summarized on every turn.
   - Pure set-diff `retention-report` for verifying multi-tier fact retention."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def chars-per-token 4)
(def default-model-tokens 32000)
(def min-history-chars 8000)
(def max-default-history-chars 96000)
(def default-max-structured-items 12)
(def max-tiers 3)
(def max-ancient-chars 2400)

(def ^:private model-token-prefixes
  [["gemini-2" 256000]
   ["gemini-3" 256000]
   ["gemini-1.5" 256000]
   ["claude-" 200000]
   ["gpt-5" 128000]
   ["gpt-4.1" 128000]
   ["gpt-4o" 128000]
   ["o3" 128000]
   ["o4" 128000]
   ["deepseek" 64000]
   ["qwen" 64000]
   ["llama" 64000]])

(defn model-context-tokens
  "Approximate context window in tokens for a model identifier."
  [model]
  (let [m (str/lower-case (str (or model "")))]
    (or (some (fn [[prefix tokens]]
                (when (str/includes? m prefix) tokens))
              model-token-prefixes)
        default-model-tokens)))

(def ^:private output-window-percent 0.2)
(def ^:private min-output-tokens 256)

(defn bounded-output-tokens
  "Cap requested max_tokens at 20% of the model's context window (floored at 256)
   so an oversized max_tokens never starves input context."
  [model requested-max-tokens]
  (let [window (model-context-tokens model)
        cap (max min-output-tokens (long (* window output-window-percent)))
        req (long (or requested-max-tokens 4096))]
    (max min-output-tokens (min req cap))))

(defn calibrate-token-report
  "Sanity-check a provider's reported input token count against a local estimate.
   Rejects buggy flat provider offsets (e.g. +20k over-reports) and below-floor
   drops; returns the credible value."
  [reported estimated]
  (let [est (max 0 (long (or estimated 0)))
        rep (when (number? reported) (long reported))]
    (cond
      (nil? rep) est
      (< rep 10) est
      (and (>= est 500) (< rep (long (* 0.2 est)))) est
      (> rep (max 10000 (* 4 (max 1 est)))) est
      :else rep)))

(defn history-budget-chars
  "Character budget for replayed session history. Explicit :history-budget-chars
   in cfg always wins; otherwise scales from the model's context window (25%
   allocation, capped at max-default-history-chars) minus injected
   system/brain/memory overhead, floored at min-history-chars."
  [cfg overhead-chars]
  (if-let [explicit (:history-budget-chars cfg)]
    (long explicit)
    (let [model-chars (* (long (model-context-tokens (:model cfg)))
                         chars-per-token)
          base (min max-default-history-chars (quot model-chars 4))]
      (max min-history-chars (- base (long (or overhead-chars 0)))))))

(defn- message-chars [msg]
  (let [sized #(if (some? %) (count (pr-str %)) 0)]
    (+ (count (str (:content msg)))
       (sized (:tool/results msg))
       (sized (:tool/requests msg)))))

(defn count-chars
  "Total characters across message content and tool payloads."
  [messages]
  (reduce (fn [acc msg] (+ acc (message-chars msg))) 0 messages))

(defn estimate-tokens
  "Unified token estimate: chars / 4, rounded up (matches bb-agent.brain)."
  [messages]
  (long (Math/ceil (/ (double (count-chars messages)) chars-per-token))))

(defn- one-line-preview [s max-len]
  (let [clean (-> (str (or s ""))
                  (str/replace #"[\r\n\t]+" " ")
                  (str/replace #"\s+" " ")
                  str/trim)]
    (if (<= (count clean) max-len)
      clean
      (str (subs clean 0 (max 0 (- max-len 1))) "…"))))

(defn- summarize-tool-entry [entry]
  (let [tname (or (:tool/name entry) (:name entry) "tool")
        status (some-> (or (:status entry) :ok) name)
        body (or (:output entry) (:content entry) (:stdout entry)
                 (:error/message entry) (pr-str entry))
        body-str (str body)
        preview (one-line-preview body-str 80)]
    (str tname " " status " (" (count body-str) " chars)"
         (when (seq preview) (str ": " preview)))))

(defn- tool-breadcrumb [msg]
  (if (seq (:tool/results msg))
    (str "[tool pruned: "
         (str/join "; " (map summarize-tool-entry (:tool/results msg)))
         "]")
    (let [raw (str (or (:content msg) ""))
          preview (one-line-preview raw 80)]
      (str "[tool pruned (" (count raw) " chars)"
           (when (seq preview) (str ": " preview))
           "]"))))

(defn- already-pruned? [msg]
  (and (nil? (:tool/results msg))
       (str/starts-with? (str (or (:content msg) "")) "[tool pruned")))

(defn prune-old-tool-results
  "Condense all but the `keep-recent` most recent tool messages into
   deterministic 1-line breadcrumbs and drop heavy :tool/results payloads.
   Idempotent: already-pruned breadcrumbs are left untouched."
  [messages keep-recent]
  (let [tool-indices (keep-indexed (fn [idx msg] (when (= (:role msg) :tool) idx))
                                   messages)
        prune-set (set (if (> (count tool-indices) keep-recent)
                         (drop-last keep-recent tool-indices)
                         []))]
    (mapv (fn [idx msg]
            (if (and (contains? prune-set idx)
                     (not (already-pruned? msg)))
              (-> msg
                  (dissoc :tool/results)
                  (assoc :content (tool-breadcrumb msg)))
              msg))
          (range (count messages))
          messages)))

(defn find-boundaries
  "Returns [head-end tail-start]. The first `protect-first` messages
  are always kept; walking backwards from the end, messages stay kept
  until `tail-char-budget` is exhausted."
  [messages protect-first tail-char-budget]
  (let [head-end (min protect-first (count messages))
        tail-indices (loop [msgs (reverse (subvec messages head-end))
                            acc-chars 0
                            indices []
                            idx-from-end 0]
                       (if (empty? msgs)
                         indices
                         (let [chars (message-chars (first msgs))]
                           (if (> (+ acc-chars chars) tail-char-budget)
                             indices
                             (recur (rest msgs)
                                    (+ acc-chars chars)
                                    (conj indices (- (count messages) 1 idx-from-end))
                                    (inc idx-from-end))))))
        tail-start (if (empty? tail-indices)
                     (count messages)
                     (apply min tail-indices))]
    [head-end tail-start]))

(def ^:private structured-fields [:facts :decisions :files :pending])

(def empty-structured-memory
  {:facts [] :decisions [] :files [] :pending []})

(defn- normalize-items [coll]
  (if (sequential? coll)
    (->> coll
         (map #(str/trim (str %)))
         (remove str/blank?)
         vec)
    []))

(defn parse-summary-response
  "Parse an LLM compaction reply. Accepts either an EDN map with :summary and
   optional :facts/:decisions/:files/:pending vectors, or plain prose (which
   falls back cleanly with empty structured vectors)."
  [raw]
  (let [text (str/trim (str (or raw "")))
        parsed (try
                 (let [v (edn/read-string text)]
                   (when (and (map? v) (string? (:summary v))) v))
                 (catch Exception _ nil))]
    (if parsed
      {:summary (str/trim (:summary parsed))
       :facts (normalize-items (:facts parsed))
       :decisions (normalize-items (:decisions parsed))
       :files (normalize-items (:files parsed))
       :pending (normalize-items (:pending parsed))}
      {:summary text
       :facts []
       :decisions []
       :files []
       :pending []})))

(defn- merge-tail-dedupe [prev next-items max-items]
  (let [combined (concat (normalize-items prev) (normalize-items next-items))
        ;; Keep the newest occurrence order so updated items stay in the tail.
        deduped (->> (reverse combined)
                     distinct
                     reverse
                     vec)]
    (if (> (count deduped) max-items)
      (vec (take-last max-items deduped))
      deduped)))

(defn merge-structured-memory
  "Merge two structured memory maps additively, deduplicating and keeping
   the newest bounded tail per field (never freezing oldest items)."
  ([prev next-mem]
   (merge-structured-memory prev next-mem default-max-structured-items))
  ([prev next-mem max-items]
   (reduce (fn [acc k]
             (assoc acc k (merge-tail-dedupe (get prev k) (get next-mem k) max-items)))
           empty-structured-memory
           structured-fields)))

(defn retention-report
  "Pure set-diff check of structured-memory retention across compaction foldovers.
   Returns {:tracked n :retained m :dropped #{...} :retention-rate double}."
  [before-mem after-mem]
  (let [all-items (fn [m]
                    (->> structured-fields
                         (mapcat #(normalize-items (get m %)))
                         set))
        before-set (all-items before-mem)
        after-set (all-items after-mem)
        dropped (into #{} (remove after-set before-set))
        tracked (count before-set)
        retained (- tracked (count dropped))]
    {:tracked tracked
     :retained retained
     :dropped dropped
     :retention-rate (if (zero? tracked) 1.0 (/ (double retained) tracked))}))

(defn- format-structured-memory [mem]
  (let [sections (keep (fn [[label k]]
                         (let [items (normalize-items (get mem k))]
                           (when (seq items)
                             (str label ": " (str/join " | " items)))))
                       [["Facts" :facts]
                        ["Decisions" :decisions]
                        ["Files" :files]
                        ["Pending" :pending]])]
    (when (seq sections)
      (str "[Structured Memory]\n" (str/join "\n" sections)))))

(defn- format-compaction-message [tiers structured-memory]
  (let [tier-blocks (mapv (fn [{:keys [from to text]}]
                            (str "[Turns " from "-" to "]\n" text))
                          tiers)
        mem-block (format-structured-memory structured-memory)]
    {:role :user
     :content (str "[SYSTEM: CONTEXT COMPACTION]\n"
                   "History summarized to save context. Original goal and latest turns preserved.\n"
                   "Use read_session_turns(from, to) to inspect verbatim turns on disk.\n\n"
                   "SUMMARY OF PREVIOUS WORK:\n"
                   (str/join "\n\n" tier-blocks)
                   (when mem-block (str "\n\n" mem-block)))}))

(defn summarize-middle
  "Ask `call-llm-fn` (prompt-string -> summary-string) to compress the
  middle turns, updating `previous-summary` when present."
  [middle previous-summary call-llm-fn]
  (let [turn (fn [msg]
               (str "[" (some-> (:role msg) name) "] "
                    (subs (str (:content msg))
                          0 (min 500 (count (str (:content msg)))))))
        prompt (str "Summarize these conversation turns for an AI agent to continue its work.\n"
                    "Return an EDN map {:summary \"...\" :facts [...] :decisions [...] :files [...] :pending [...]}\n"
                    "(or plain text with sections: Goal, Progress, Key Decisions, Files Modified, Next Steps).\n\n"
                    (when (not (str/blank? previous-summary))
                      (str "Previous summary to update:\n" previous-summary "\n\n"))
                    "Turns to summarize:\n"
                    (str/join "\n" (map turn middle)))]
    (call-llm-fn prompt)))

(defn should-compress?
  "True when the estimated token count exceeds `threshold-tokens`."
  [messages threshold-tokens]
  (> (estimate-tokens messages) threshold-tokens))

(defn compress
  "Compact `messages` (vector of theseus chat maps). Options:
    :protect-first    messages always kept at the head
    :tail-char-budget chars of recent turns always kept at the tail
    :call-llm-fn      injected summarizer, prompt-string -> summary-string"
  [messages {:keys [protect-first tail-char-budget call-llm-fn]}]
  (let [pruned (prune-old-tool-results messages 2)
        [head-end tail-start] (find-boundaries pruned protect-first tail-char-budget)]
    (if (>= head-end tail-start)
      pruned
      (let [middle (subvec pruned head-end tail-start)
            raw-summary (summarize-middle middle nil call-llm-fn)
            parsed (parse-summary-response raw-summary)
            tier {:from head-end :to (dec tail-start) :text (:summary parsed)}
            mem (merge-structured-memory empty-structured-memory parsed)]
        (vec (concat (subvec pruned 0 head-end)
                     [(format-compaction-message [tier] mem)]
                     (subvec pruned tail-start)))))))

;; --- Persistent session compaction checkpoints ---

(defn checkpoint-file [session-id]
  (fs/path (config/home) "state" "session-compaction"
           (str (session/safe-session-id session-id) ".edn")))

(defn load-checkpoint [session-id]
  (let [v (aio/read-edn-safe (checkpoint-file session-id) nil)]
    (when (and (map? v) (integer? (:last-summarized-turn v)))
      v)))

(defn save-checkpoint! [session-id checkpoint]
  (aio/write-private-file! (checkpoint-file session-id) (pr-str checkpoint))
  checkpoint)

(defn clear-checkpoint! [session-id]
  (fs/delete-if-exists (checkpoint-file session-id)))

(defn- turn->pair [{:keys [user/input assistant/final]}]
  [{:role :user :content input}
   {:role :assistant :content final}])

(defn- tail-start-turn-index
  "Find the earliest indexed turn whose pair fits in tail-char-budget walking backwards."
  [indexed-turns tail-char-budget]
  (let [n (count indexed-turns)]
    (loop [idx (dec n) acc 0 kept-start n]
      (if (neg? idx)
        kept-start
        (let [pair (turn->pair (nth indexed-turns idx))
              c (count-chars pair)]
          (if (and (< kept-start n) (> (+ acc c) tail-char-budget))
            kept-start
            (recur (dec idx) (+ acc c) idx)))))))

(defn- condense-ancient-tiers [t0 t1 call-llm-fn]
  (let [combined (str (:text t0) "\n" (:text t1))]
    (if (or (<= (count combined) max-ancient-chars) (nil? call-llm-fn))
      combined
      (let [prompt (str "Condense these older summary tiers [Turns "
                        (:from t0) "-" (:to t1) "] into one concise summary "
                        "(under 1600 chars) preserving key facts, decisions, and file paths:\n\n"
                        "[Turns " (:from t0) "-" (:to t0) "]\n" (:text t0) "\n\n"
                        "[Turns " (:from t1) "-" (:to t1) "]\n" (:text t1))
            condensed (try
                        (let [raw (call-llm-fn prompt)
                              parsed (parse-summary-response raw)]
                          (when-not (str/blank? (:summary parsed))
                            (:summary parsed)))
                        (catch Exception _ nil))]
        (or condensed
            (str (subs combined 0 (- max-ancient-chars 14)) "\n[truncated]"))))))

(defn- fold-tiers [tiers call-llm-fn]
  (if (<= (count tiers) max-tiers)
    (vec tiers)
    (let [[t0 t1 & more] tiers
          merged {:from (:from t0)
                  :to (:to t1)
                  :text (condense-ancient-tiers t0 t1 call-llm-fn)}]
      (recur (into [merged] more) call-llm-fn))))

(defn compress-session-turns!
  "Incrementally compact persisted session turns using a durable EDN checkpoint.
   Only turns strictly after :last-summarized-turn and before the verbatim tail
   trigger a summarizer call. When the watermark has not advanced, reuses the
   persisted checkpoint with zero LLM calls."
  [session-id indexed-turns {:keys [tail-char-budget call-llm-fn]}]
  (let [tail-pos (tail-start-turn-index indexed-turns tail-char-budget)
        tail-turns (subvec indexed-turns tail-pos)
        tail-messages (vec (mapcat turn->pair tail-turns))]
    (if (zero? tail-pos)
      tail-messages
      (let [target-end-turn (:turn/index (nth indexed-turns (dec tail-pos)))
            raw-cp (load-checkpoint session-id)
            max-turn-idx (:turn/index (peek indexed-turns))
            ;; Discard checkpoint if session was reset/truncated beneath it.
            cp (when (and raw-cp (<= (:last-summarized-turn raw-cp) max-turn-idx))
                 raw-cp)
            watermark (if cp (inc (:last-summarized-turn cp)) 0)
            delta-turns (filterv #(<= watermark (:turn/index %) target-end-turn)
                                 (subvec indexed-turns 0 tail-pos))
            cp' (if (empty? delta-turns)
                  cp
                  (let [delta-msgs (vec (mapcat turn->pair delta-turns))
                        prev-text (when (seq (:tiers cp))
                                    (str/join "\n\n" (map :text (:tiers cp))))
                        raw-summary (summarize-middle delta-msgs prev-text call-llm-fn)
                        parsed (parse-summary-response raw-summary)
                        new-tier {:from (:turn/index (first delta-turns))
                                  :to (:turn/index (peek delta-turns))
                                  :text (:summary parsed)}
                        tiers' (fold-tiers (conj (vec (:tiers cp)) new-tier) call-llm-fn)
                        mem' (merge-structured-memory (or (:memory cp) empty-structured-memory)
                                                      parsed)
                        next-cp {:last-summarized-turn (:turn/index (peek delta-turns))
                                 :tiers tiers'
                                 :memory mem'}]
                    (save-checkpoint! session-id next-cp)
                    next-cp))]
        (if (seq (:tiers cp'))
          (into [(format-compaction-message (:tiers cp') (:memory cp'))]
                tail-messages)
          tail-messages)))))
