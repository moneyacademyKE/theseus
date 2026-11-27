(ns bb-agent.stop-intent
  "Multi-shape stop-intent kill switch (ported from OpenCrabs utils/stop_intent.rs).
   Normalizes punctuation/casing/slash-mentions, strips trailing address terms,
   matches single-word stops ONLY as the whole message (so 'stop the docker
   container' never false-cancels), and matches multi-word interrupts as either
   the whole message or a leading clause."
  (:require [clojure.string :as str]))

(def ^:private max-bare-words 3)

(def ^:private single-word-stops
  #{"stop" "cancel" "abort" "halt" "wait" "pause" "quit" "enough"})

(def ^:private multi-word-stops
  ["hold on"
   "wait a sec"
   "wait a second"
   "wait a moment"
   "hang on"
   "never mind"
   "nevermind"
   "stop right now"
   "stand down"
   "cut it out"
   "forget it"])

(def ^:private trailing-address-terms
  #{"please" "pls" "plz" "now" "eileen" "theseus" "bot" "agent" "assistant"})

(defn normalize
  "Casefold, drop punctuation/symbols (keeping apostrophes), collapse whitespace."
  [text]
  (if (or (nil? text) (str/blank? (str text)))
    ""
    (-> (str text)
        str/lower-case
        (str/replace #"[^\p{L}\p{N}']+" " ")
        str/trim)))

(defn- strip-trailing-address [normalized]
  (let [words (vec (remove str/blank? (str/split normalized #"\s+")))]
    (loop [ws words]
      (if (and (> (count ws) 1)
               (contains? trailing-address-terms (peek ws)))
        (recur (pop ws))
        (str/join " " ws)))))

(defn- opens-with? [text phrase]
  (and (str/starts-with? text phrase)
       (let [rest-str (subs text (count phrase))]
         (or (empty? rest-str) (str/starts-with? rest-str " ")))))

(defn stop-intent?
  "True when `text` expresses an immediate interrupt/stop intent (including
   `/stop`, `/stop@bot`, `STOP!!!`, `stop eileen`, or `hold on, ...`), while
   never false-triggering on instructions like `stop the docker container`."
  [text]
  (if (or (nil? text) (str/blank? (str text)))
    false
    (let [trimmed (str/trim (str text))
          no-slash (if (str/starts-with? trimmed "/") (subs trimmed 1) trimmed)
          no-mention (first (str/split no-slash #"@" 2))
          norm (normalize no-mention)]
      (if (empty? norm)
        false
        (let [cleaned (strip-trailing-address norm)
              words (remove str/blank? (str/split cleaned #"\s+"))]
          (boolean
           (or (and (= 1 (count words))
                    (contains? single-word-stops cleaned))
               (and (<= (count words) max-bare-words)
                    (contains? single-word-stops cleaned))
               (some #(opens-with? cleaned %) multi-word-stops))))))))
