(ns bb-agent.memory-dream
  "Cross-conversation DREAM consolidation with deterministic quote verification.
   Packages 2–20 recent owner conversations (bounded 40KB), asks an LLM for
   5 strict JSON buckets (learned, outdated, ideas, missed, declined), and
   deterministically verifies that every `evidence` quote literally exists as
   a substring of the user's own typed lines before accepting it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private max-dream-bytes 40000)

(defn package-conversations
  "Format recent owner conversations into a bounded transcript block (<=40KB)
   alongside an index of each session's user-only lines for quote verification."
  [conversations]
  (let [selected (vec (take 20 conversations))
        user-lines-by-session
        (into {}
              (map (fn [{:session/keys [id] :keys [turns]}]
                     [(str id)
                      (str/lower-case
                       (str/join "\n" (keep :user/input turns)))]))
              selected)
        rendered (reduce
                  (fn [acc {:session/keys [id] :keys [turns]}]
                    (let [block (str "=== Session " id " ===\n"
                                     (str/join "\n"
                                               (map (fn [{:keys [user/input assistant/final]}]
                                                      (str "User: " (or input "") "\n"
                                                           "Assistant: " (or final "")))
                                                    turns))
                                     "\n\n")]
                      (if (> (+ (count acc) (count block)) max-dream-bytes)
                        (reduced acc)
                        (str acc block))))
                  ""
                  selected)]
    {:transcript rendered
     :user-lines-by-session user-lines-by-session}))

(defn dream-prompt [transcript]
  (str "Analyze these recent owner conversations and return STRICT JSON ONLY with keys:\n"
       "{\"learned\": [...], \"outdated\": [...], \"ideas\": [...], \"missed\": [...], \"declined\": [...]}\n"
       "Each item must be {\"text\": \"...\", \"confidence\": 0.0-1.0, \"sessions\": [\"id1\", ...], \"evidence\": \"exact verbatim substring from a User: line\"}.\n"
       "For `learned`, require >=2 cited sessions and confidence >= 0.7.\n\n"
       transcript))

(defn dream-evidenced?
  "Deterministically verify that `evidence` literally appears as a substring
   of the user's own lines in at least one cited session."
  [item user-lines-by-session]
  (let [ev (some-> (:evidence item) str str/trim str/lower-case)
        cited (map str (or (:sessions item) []))]
    (boolean
     (and (not (str/blank? ev))
          (>= (count ev) 6)
          (seq cited)
          (some (fn [sid]
                  (when-let [u-lines (get user-lines-by-session sid)]
                    (str/includes? u-lines ev)))
                cited)))))

(defn parse-dream [raw]
  (let [trimmed (str/trim (str (or raw "")))
        unwrapped (if-let [[_ inner] (re-matches #"(?s)^```(?:json)?\s*\r?\n?(.*?)\r?\n?```$" trimmed)]
                    (str/trim inner)
                    trimmed)]
    (try
      (let [m (json/parse-string unwrapped true)]
        (if (map? m) m {}))
      (catch Exception _ {}))))

(defn run-dream
  "Package conversations, run `(call-llm-fn prompt)`, parse JSON, and filter
   every bucket through deterministic user-quote verification."
  [conversations call-llm-fn]
  (let [{:keys [transcript user-lines-by-session]} (package-conversations conversations)
        parsed (parse-dream (call-llm-fn (dream-prompt transcript)))
        buckets [:learned :outdated :ideas :missed :declined]
        rejected (atom 0)
        verified (into {}
                       (map (fn [b]
                              (let [raw-items (vec (or (get parsed b) []))
                                    kept (filterv (fn [it]
                                                    (let [conf (double (or (:confidence it) 1.0))
                                                          min-sess (if (= b :learned) 2 1)
                                                          ok? (and (dream-evidenced? it user-lines-by-session)
                                                                   (>= (count (distinct (:sessions it))) min-sess)
                                                                   (or (not= b :learned) (>= conf 0.7)))]
                                                      (when-not ok? (swap! rejected inc))
                                                      ok?))
                                                  raw-items)]
                                [b kept])))
                       buckets)]
    (assoc verified :rejected-count @rejected)))
