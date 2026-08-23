(ns bb-agent.compression
  "Context compaction for long conversations.

  Ported from AlcaponeCoder's cl_agents/compression.clj (the lineage's
  benchmark-hardened variant), adapted to theseus message shapes:
  keyword roles and :tool/results / :tool/requests keys. The original
  required cheshire + babashka.http-client but used neither; this port
  is zero-dep.

  Strategy: prune stale tool output, protect the head (system + first
  turns) and a character-budgeted tail (recent turns), then replace the
  middle with an LLM summary injected as a user message. The LLM call
  arrives as an injected function, so the module stays pure and
  testable against a deterministic fake."
  (:require [clojure.string :as str]))

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
  "Rough token estimate: chars / 3.5."
  [messages]
  (long (Math/ceil (/ (count-chars messages) 3.5))))

(defn prune-old-tool-results
  "Replace the content of all but the `keep-recent` most recent tool
  messages with a placeholder. Tool payloads dominate context and go
  stale fastest."
  [messages keep-recent]
  (let [tool-indices (keep-indexed (fn [idx msg] (when (= (:role msg) :tool) idx))
                                   messages)
        prune-set (set (if (> (count tool-indices) keep-recent)
                         (drop-last keep-recent tool-indices)
                         []))]
    (mapv (fn [idx msg]
            (if (contains? prune-set idx)
              (assoc msg :content "[old tool output cleared]")
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

(defn summarize-middle
  "Ask `call-llm-fn` (prompt-string -> summary-string) to compress the
  middle turns, updating `previous-summary` when present."
  [middle previous-summary call-llm-fn]
  (let [turn (fn [msg]
               (str "[" (some-> (:role msg) name) "] "
                    (subs (str (:content msg))
                          0 (min 500 (count (str (:content msg)))))))
        prompt (str "Summarize these conversation turns for an AI agent to continue its work.\n"
                    "Use sections: Goal, Progress, Key Decisions, Files Modified, Next Steps.\n\n"
                    (when previous-summary
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
            summary (summarize-middle middle nil call-llm-fn)]
        (vec (concat (subvec pruned 0 head-end)
                     [{:role :user
                       :content (str "[SYSTEM: CONTEXT COMPACTION]\n"
                                     "History summarized to save context. Original goal and latest turns preserved.\n\n"
                                     "SUMMARY OF PREVIOUS WORK:\n"
                                     summary)}]
                     (subvec pruned tail-start)))))))
