(ns bb-agent.turn-integrity
  "Turn completion integrity:
   - Finish-reason classification (:complete / :truncated / :interrupted)
     and sectioned-write recovery prompts.
   - Execute-mode narration guard (refuse fake 'I fixed it' with zero mutations).
   - Silent-turn factual receipt synthesizer (truthful bulleted summary from
     executed tool receipts instead of failing a productive silent turn).
   - Two-stage interactive stall watchdog (soft pulse + hard escalation)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------- Finish-reason integrity (#9) ----------

(defn classify-finish-reason
  "Normalize provider finish_reason / stop_reason into :complete, :truncated,
   or :interrupted."
  [reason]
  (let [r (some-> reason name str/lower-case)]
    (case r
      ("stop" "end_turn" "tool_calls" "tool_use" nil "") :complete
      ("length" "max_tokens") :truncated
      :interrupted)))

(defn truncation-recovery-prompt
  "Sectioned continuation prompt when a response or write_file hits max_tokens."
  [tool-requests]
  (let [write-call (some #(when (= "write_file" (:tool/name %)) %) tool-requests)]
    (if write-call
      (str "Your previous output hit the token limit while writing "
           (or (get-in write-call [:tool/args :path]) "a file")
           ". Write the file in smaller sections (first chunk with append?=false, "
           "remaining chunks with append?=true) instead of repeating one giant write.")
      "Your previous response was truncated by the token limit. Continue cleanly from where you stopped without repeating earlier text.")))

;; ---------- Execute-mode narration guard (#10) ----------

(def mutating-tools
  #{"write_file" "edit_file" "shell" "launch_goal"})

(def ^:private imperative-edit-re
  #"(?i)\b(?:fix|implement|refactor|edit|modify|update|patch|add|create|write|delete|remove|build|wire|migrate)\b.*\b(?:file|code|bug|test|function|endpoint|handler|config|script|module|feature|class|namespace|repo)\b")

(def ^:private text-deliverable-re
  #"(?i)^\s*(?:write\s+(?:a|an|me)\s+(?:poem|email|letter|essay|summary|story|blog|tweet|draft|message|haiku)|explain|what\s+is|why\s+does|how\s+does|summarize|translate)\b")

(def ^:private fake-done-claim-re
  #"(?i)\b(?:i(?:'ve| have)\s+(?:fixed|updated|implemented|written|edited|modified|created|patched|added|refactored|completed)|changes\s+(?:have\s+been\s+)?(?:made|applied|saved)|the\s+bug\s+is\s+(?:now\s+)?fixed)\b")

(defn- any-mutating-tool-succeeded? [turn]
  (boolean
   (some (fn [r]
           (and (contains? mutating-tools (:tool/name r))
                (= :ok (:status r))
                (not (false? (:executed? r)))))
         (:tool/results turn))))

(defn narration-without-mutation?
  "True when the prompt asked for a concrete code/file change, zero mutating
   tools succeeded in this turn, and the assistant claimed completion in prose."
  [prompt turn content]
  (let [p (str (or prompt ""))
        c (str (or content ""))]
    (and (not (str/blank? p))
         (not (str/blank? c))
         (not (re-find text-deliverable-re p))
         (boolean (re-find imperative-edit-re p))
         (not (any-mutating-tool-succeeded? turn))
         (boolean (re-find fake-done-claim-re c)))))

(def execute-guard-nudge-message
  (str "Execution guard: you claimed changes were made, but no mutating tool "
       "(write_file, shell, launch_goal) succeeded in this turn. Either perform "
       "the edit with tools now, or honestly report what blocked you."))

;; ---------- Silent-turn factual receipt synthesizer (#11) ----------

(defn- format-tool-receipt [req res]
  (let [tname (or (:tool/name res) (:tool/name req) "tool")
        args (:tool/args req)
        ok? (= :ok (:status res))]
    (case tname
      "write_file"
      (str "- " (if ok? "Wrote " "Failed writing ") (or (:path args) (:path res) "file")
           (when (and ok? (:bytes res)) (str " (" (:bytes res) " bytes)")))

      "shell"
      (let [cmd (str (or (:cmd args) "command"))
            short-cmd (if (> (count cmd) 60) (str (subs cmd 0 57) "...") cmd)]
        (str "- Ran `" short-cmd "` — " (if ok? "succeeded" (str "failed (" (:status res) ")"))))

      "read_file"
      (str "- Read " (or (:path args) (:path res) "file"))

      "launch_goal"
      (str "- Launched goal (" (if ok? "started" "failed") ")")

      (str "- " tname " — " (if ok? "ok" (name (or (:status res) :error)))))))

(defn synthesize-silent-receipt
  "When a turn executed at least one successful tool call and ended without
   assistant prose, synthesize a strictly factual bulleted receipt from the
   recorded tool requests/results. Returns nil if no tool succeeded."
  [turn]
  (let [reqs (vec (or (:tool/requests turn) []))
        results (vec (or (:tool/results turn) []))]
    (when (some #(= :ok (:status %)) results)
      (let [lines (mapv (fn [idx res]
                          (format-tool-receipt (nth reqs idx nil) res))
                        (range (count results))
                        results)]
        (str "Completed actions:\n" (str/join "\n" lines))))))

;; ---------- Two-stage interactive stall watchdog (#12) ----------

(def default-soft-stall-ms 180000)
(def default-hard-stall-ms 480000)

(defn with-stall-watchdog
  "Run thunk f while monitoring heartbeat-atom (epoch ms). If no progress
   occurs within soft-ms, emits {:status :turn/still-working ...} once; if
   stalled past hard-ms, emits {:status :turn/stalled ...} once. Stops
   cleanly on return."
  [cfg heartbeat-atom f]
  (let [emit (:status/emit cfg)]
    (if-not emit
      (f)
      (let [soft-ms (long (or (:stall/soft-ms cfg) default-soft-stall-ms))
            hard-ms (long (or (:stall/hard-ms cfg) default-hard-stall-ms))
            poll-ms (max 10 (min 500 (quot soft-ms 4)))
            done? (atom false)
            soft-fired? (atom false)
            hard-fired? (atom false)
            worker (doto (Thread.
                          (fn []
                            (while (not @done?)
                              (try
                                (Thread/sleep poll-ms)
                                (when-not @done?
                                  (let [idle (- (System/currentTimeMillis) @heartbeat-atom)]
                                    (when (and (>= idle soft-ms) (compare-and-set! soft-fired? false true))
                                      (try (emit {:status :turn/still-working :idle-ms idle})
                                           (catch Exception _ nil)))
                                    (when (and (>= idle hard-ms) (compare-and-set! hard-fired? false true))
                                      (try (emit {:status :turn/stalled :idle-ms idle})
                                           (catch Exception _ nil)))))
                                (catch InterruptedException _ nil)))))
                     (.setDaemon true)
                     (.start))]
        (try
          (f)
          (finally
            (reset! done? true)
            (.interrupt worker)))))))

;; ---------- Bare JSON tool-call extractor & unrun fenced-shell guard ----------

(defn extract-bare-tool-call
  "Rescue models that emit a raw JSON tool call (`{\"name\": ..., \"arguments\": {...}}`,
   optionally inside a ```json fence) in plain text instead of structured tool_calls.
   Only returns a request when :name matches `known-tool-names`."
  [content known-tool-names]
  (when (and (string? content) (seq known-tool-names))
    (let [trimmed (str/trim content)
          unwrapped (if-let [[_ inner] (re-matches #"(?s)^```(?:json)?\s*\r?\n?(.*?)\r?\n?```$" trimmed)]
                      (str/trim inner)
                      trimmed)]
      (when (and (str/starts-with? unwrapped "{") (str/ends-with? unwrapped "}"))
        (try
          (let [parsed (json/parse-string unwrapped true)
                tname (some-> (or (:name parsed) (:tool parsed)) str)
                args (or (:arguments parsed) (:args parsed) (:input parsed) {})]
            (when (and (contains? known-tool-names tname) (map? args))
              {:tool/name tname :tool/args args}))
          (catch Exception _ nil))))))

(def ^:private run-command-prompt-re
  #"(?i)\b(?:run|execute|test|check|verify|start|build|compile|lint)\b.*\b(?:test|tests|command|script|suite|build|lint|shell|cli|server|check)\b")

(def ^:private show-example-prompt-re
  #"(?i)^\s*(?:show|give|write|explain|how\s+do\s+i|what\s+command|example)\b")

(def ^:private fenced-shell-re
  #"(?s)```(?:bash|sh|zsh|shell)\b.*?\n.*?```")

(defn unrun-fenced-shell?
  "True when the user asked to run/execute a command, zero shell calls executed
   in this turn, and the assistant replied with an unrun fenced shell block."
  [prompt content tool-results]
  (let [p (str (or prompt ""))
        c (str (or content ""))
        ran-shell? (some #(= "shell" (:tool/name %)) tool-results)]
    (and (not (str/blank? p))
         (not (str/blank? c))
         (not ran-shell?)
         (not (re-find show-example-prompt-re p))
         (boolean (re-find run-command-prompt-re p))
         (boolean (re-find fenced-shell-re c)))))

;; ---------- Deterministic Post-Turn Stop Gates ----------

(def ^:private max-stop-gates 2)

(def ^:private follow-up-promise-re
  #"(?i)\b(?:i(?:'ll| will)\s+(?:remind you|keep an eye on|check back|follow up|watch for|monitor|ping you|let you know)|i(?:'ve| have)\s+scheduled)\b")

(def ^:private bare-agree-re
  #"(?i)^\s*(?:you(?:'re| are)\s+(?:totally\s+|completely\s+|absolutely\s+)?right|good catch|my mistake|fair point|understood|got it)(?:[\s,;:-]+(?:my mistake|sorry|good catch|understood|got it|my bad))?[\s.,!]*$")

(def ^:private announces-step-re
  #"(?i)(?:^\s*(?:let me|i(?:'ll| will))\s+(?:check|inspect|look|read|run|verify|search)\b[^.!?\n]{0,120}(?:\.\.\.|:)\s*$|\bshould i (?:continue|proceed|go ahead)\??\s*$)")

(def ^:private outward-destructive-re
  #"(?i)\b(?:send|email|delete|remove|drop|deploy|push|publish|pay|purchase|wire|transfer)\b")

(def ^:private bare-ack-re
  #"(?i)^\s*(?:on it|working on it|checking now|let me check|one moment|sure thing|will do)[\s.,!]*$")

(defn- bare-tool-ack? [res]
  (let [out (str/trim (str (or (:output res) (:content res) (:stdout res) "")))]
    (and (= :ok (:status res))
         (contains? #{"write_file" "edit_file" "shell" "a2a_task"} (:tool/name res))
         (or (str/blank? out)
             (#{"ok" "done" "true" "{}" "{\"ok\":true}" "{\"ok\": true}"} (str/lower-case out))))))

(defn stop-gate-for
  "Evaluate 6 deterministic post-turn stop gates (capped at 2 per turn, each
   kind at most once, never after a failed/interrupted turn). Returns nil when
   the reply is clean, or {:gate kw :nudge str}."
  [{:keys [prompt content tool-results fired-gates turn-ok?]
    :or {fired-gates #{} turn-ok? true}}]
  (let [c (str/trim (str (or content "")))
        p (str/trim (str (or prompt "")))
        results (vec (or tool-results []))
        fired (set fired-gates)
        can-fire? (fn [k] (and (not (contains? fired k)) (< (count fired) max-stop-gates)))
        ran-schedule-or-goal? (some #(contains? #{"launch_goal" "plan"} (:tool/name %)) results)]
    (when (and turn-ok? (not (str/blank? c)) (< (count fired) max-stop-gates))
      (cond
        (and (can-fire? :follow-up)
             (not ran-schedule-or-goal?)
             (re-find follow-up-promise-re c))
        {:gate :follow-up
         :nudge "Stop gate: you promised a future follow-up or reminder, but no scheduling/goal tool ran. Either schedule/create it now or tell the user honestly you cannot watch asynchronously."}

        (and (can-fire? :checklist)
             (re-find #"(?m)^\s*[-*]\s+\[x\]" c)
             (re-find #"(?m)^\s*[-*]\s+\[ \]" c))
        {:gate :checklist
         :nudge "Stop gate: your reply has both completed [x] and open [ ] checklist items. Finish the remaining open items you own before concluding."}

        (and (can-fire? :bare-agree)
             (empty? results)
             (re-find bare-agree-re c)
             (re-find imperative-edit-re p))
        {:gate :bare-agree
         :nudge "Stop gate: you agreed with the user without calling any tools. Perform the requested fix now instead of only acknowledging."}

        (and (can-fire? :open-items)
             (empty? results)
             (re-find bare-ack-re c))
        {:gate :open-items
         :nudge "Stop gate: your reply is an acknowledgement only. Execute the work and return the actual result."}

        (and (can-fire? :announces-step)
             (re-find announces-step-re c)
             (not (re-find outward-destructive-re c)))
        {:gate :announces-step
         :nudge "Stop gate: you announced your next read-only step instead of executing it. Run the step now and finish the answer."}

        (and (can-fire? :unconfirmed-write)
             (seq results)
             (bare-tool-ack? (peek results))
             (not (some #(= "read_file" (:tool/name %)) (rest (reverse results))))
             (re-find #"(?i)\b(?:done|saved|written|updated|confirmed)\b" c))
        {:gate :unconfirmed-write
         :nudge "Stop gate: the last side-effecting tool returned only a bare ack. Verify/read back the result before confirming completion."}

        :else nil))))
