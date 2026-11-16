(ns bb-agent.knowledge-routing
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def routing-stopwords #{"the" "a" "an" "of" "for" "and" "to" "in" "on" "is" "are" "it" "its" "with" "that" "this" "be" "not" "as" "at" "by" "from" "or" "one" "two" "each" "every" "when" "what" "how" "why" "which" "who"})
(def routing-thin-min 3)
(def routing-collision-jaccard 0.40)
(defn routing-terms [scope]
  (->> (str/lower-case (str scope)) (re-seq #"[a-z0-9][a-z0-9_-]*")
       (remove routing-stopwords) (filter #(> (count %) 2)) set))
(defn- jaccard-sim [a b]
  (let [u (count (set/union a b))]
    (if (zero? u) 0.0 (double (/ (count (set/intersection a b)) u)))))
(defn routing-rows [index-lines]
  (let [parsed (mapv (fn [line]
                       (let [[slug scope] (str/split (str line) #" — " 2)]
                         {:slug (or slug (str line)) :scope (or scope "")
                          :terms (routing-terms (or scope ""))})) index-lines)]
    (mapv (fn [{:keys [slug terms] :as p}]
            (let [others (remove #(= (:slug %) slug) parsed)
                  scored (map (fn [o] [(jaccard-sim terms (:terms o)) (:slug o)]) others)
                  [best nearest] (if (seq scored) (first (reverse (sort-by first scored))) [0.0 nil])]
              (-> p (dissoc :terms)
                  (assoc :terms (count terms) :max-jaccard (double best)
                         :nearest nearest :thin? (< (count terms) routing-thin-min)
                         :collision? (>= (double best) routing-collision-jaccard))))) parsed)))
(defn routing-report [rows]
  (let [n (count rows) thin (filterv :thin? rows) coll (filterv :collision? rows)
        terms (map :terms rows)]
    (mapv str (concat
      [(format "routing: %d index line(s) | content terms %s..%s | max Jaccard %.3f | THIN %d | COLLISION %d"
               n (if (seq terms) (str (apply min terms)) "-")
               (if (seq terms) (str (apply max terms)) "-")
               (double (if (seq rows) (apply max (map :max-jaccard rows)) 0.0))
               (count thin) (count coll))]
      (when (zero? n) ["  [UNVERIFIED] 0 index lines measured — empty corpus or rotted index walk."])
      (map #(format "  THIN %s — %d content term(s), cannot select its page: %s" (:slug %) (:terms %) (:scope %)) thin)
      (map #(format "  COLLISION %s <-> %s — Jaccard %.2f, a reader cannot choose between them" (:slug %) (:nearest %) (:max-jaccard %)) coll)
      [(if (and (seq rows) (empty? thin) (empty? coll))
         "  known misses: none at these thresholds. This measures this corpus, not routing meaning."
         "  known misses: flagged rows above are retained as useful failures; they are the useful part.")
       "negative scope: terms are alnum tokens >2 chars minus a 31-word stoplist; Jaccard measures word overlap, not meaning. Report only."]))))
