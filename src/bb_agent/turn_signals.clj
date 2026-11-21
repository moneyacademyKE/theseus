(ns bb-agent.turn-signals
  "Zero-LLM behavioural turn signal extractor for RSI telemetry.

   Detects owner friction cues (corrections, brevity/verbosity nudges,
   autonomy preferences, repeated requests, approval denials) as pure data
   with zero extra LLM calls."
  (:require [clojure.string :as str]))

(def ^:private correction-re
  #"(?i)(?:^|\b)(?:no,\s*i\s+meant|that'?s\s+(?:wrong|not\s+what)|not\s+what\s+i\s+asked|you\s+missed|actually,\s*i\s+meant|i\s+said\s+not\s+to)\b")

(def ^:private shorter-re
  #"(?i)\b(?:shorter|be\s+brief|too\s+long|more\s+concise|tl;?dr|keep\s+it\s+short)\b")

(def ^:private longer-re
  #"(?i)\b(?:more\s+detail|elaborate|explain\s+more|go\s+deeper|expand\s+on)\b")

(def ^:private just-do-it-re
  #"(?i)\b(?:just\s+do\s+it|don'?t\s+ask|stop\s+asking|go\s+ahead\s+without\s+asking)\b")

(def ^:private ask-first-re
  #"(?i)\b(?:ask\s+first|ask\s+before|confirm\s+before|check\s+with\s+me\s+first)\b")

(defn- normalize-prompt [s]
  (-> (str (or s ""))
      str/lower-case
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn extract-turn-signals
  "Pure extractor -> compact signal map, or nil when no friction signals fired."
  [{:keys [prompt prev-prompts tool-results]}]
  (let [p (str (or prompt ""))
        norm (normalize-prompt p)
        recent-norms (->> (take-last 3 (or prev-prompts []))
                          (map normalize-prompt)
                          (remove str/blank?)
                          set)
        correction? (boolean (re-find correction-re p))
        brevity (cond
                  (re-find shorter-re p) :shorter
                  (re-find longer-re p) :longer
                  :else nil)
        autonomy (cond
                   (re-find just-do-it-re p) :just-do-it
                   (re-find ask-first-re p) :ask-first
                   :else nil)
        repeated? (and (seq norm) (contains? recent-norms norm))
        denials (count (filter #(= :denied (:status %)) (or tool-results [])))
        signals (cond-> {}
                  correction? (assoc :correction? true)
                  brevity (assoc :brevity brevity)
                  autonomy (assoc :autonomy autonomy)
                  repeated? (assoc :repeated-prompt? true)
                  (pos? denials) (assoc :approval-denials denials))]
    (when (seq signals)
      signals)))
