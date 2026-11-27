(ns bb-agent.tool-name-heal
  "Conservative tool-name self-heal (ported from OpenCrabs tool_name_heal.rs).
   Weaker/local models occasionally emit near-miss tool names (`tg_send_file`,
   `repl-eval`, `read_flie`). Heals ONLY on a unique, high-confidence match
   across 3 conservative stages; ambiguous or distant names return nil so the
   normal Unknown-tool error fires."
  (:require [clojure.string :as str]))

(def ^:private abbreviations
  {"tg" "telegram"
   "tgram" "telegram"
   "msg" "message"
   "img" "image"
   "vid" "video"
   "doc" "document"
   "cfg" "config"})

(defn- normalize-name [s]
  (->> (str/lower-case (str s))
       (filter #(or (Character/isLetterOrDigit ^char %) false))
       (apply str)))

(defn- expand-tokens [s]
  (let [s (str s)
        ;; Split camelCase boundaries before splitting on non-alphanumerics
        spaced (str/replace s #"([a-z0-9])([A-Z])" "$1 $2")
        raw (->> (str/split (str/lower-case spaced) #"[^a-z0-9]+")
                 (remove str/blank?))]
    (mapv #(get abbreviations % %) raw)))

(defn- damerau-levenshtein
  "Optimal string alignment distance (adjacent transposition = 1 edit)."
  [a b]
  (let [m (count a)
        n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1
             prev2 nil
             prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (let [ca (.charAt ^String a (dec i))
                ca-prev (when (> i 1) (.charAt ^String a (- i 2)))
                curr (loop [j 1
                            row [i]]
                       (if (> j n)
                         row
                         (let [cb (.charAt ^String b (dec j))
                               cb-prev (when (> j 1) (.charAt ^String b (- j 2)))
                               cost (if (= ca cb) 0 1)
                               del (inc (nth prev j))
                               ins (inc (peek row))
                               sub (+ (nth prev (dec j)) cost)
                               base (min del ins sub)
                               best (if (and prev2 ca-prev cb-prev
                                             (= ca cb-prev) (= ca-prev cb))
                                      (min base (inc (nth prev2 (- j 2))))
                                      base)]
                           (recur (inc j) (conj row best)))))]
            (recur (inc i) prev curr)))))))

(defn resolve-tool-name
  "Resolve `requested` against `registered` tool names, or nil when no unique
   high-confidence match exists."
  [requested registered]
  (when (and (string? requested) (not (str/blank? requested)) (seq registered))
    (let [reg (vec registered)]
      (or
       ;; 0. Exact match
       (some #(when (= % requested) %) reg)

       ;; 1. Normalized exact (casing, hyphens, punctuation) — unique only
       (let [req-norm (normalize-name requested)
             hits (filterv #(= (normalize-name %) req-norm) reg)]
         (when (= 1 (count hits))
           (first hits)))

       ;; 2. Abbreviation-expanded token subset (>=2 tokens, unique most-specific)
       (let [req-set (set (expand-tokens requested))
             scored (keep (fn [r]
                            (let [rtoks (expand-tokens r)]
                              (when (and (>= (count rtoks) 2)
                                         (every? req-set rtoks))
                                [(count rtoks) r])))
                          reg)]
         (when (seq scored)
           (let [best-len (apply max (map first scored))
                 best (filterv #(= best-len (first %)) scored)]
             (when (= 1 (count best))
               (second (first best))))))

       ;; 3. Small Damerau-Levenshtein typo on normalized name — unique only
       (let [req-norm (normalize-name requested)
             limit (max 1 (min 2 (quot (count req-norm) 6)))
             hits (filterv #(<= (damerau-levenshtein (normalize-name %) req-norm) limit) reg)]
         (when (= 1 (count hits))
           (first hits)))))))
