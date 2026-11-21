(ns bb-agent.beliefs
  "Evidence-backed belief lifecycle and local negation-aware claim matching.

   Zero-LLM, pure Clojure:
   - Normalizes claims via light stemming, synonym folding, and negation polarity.
   - Deduplicates reworded claims by incrementing :memory/evidence-count and
     promoting :memory/status (:candidate -> :held -> :confirmed).
   - Handles contradictions non-destructively by marking older opposing claims
     :contested so attach-memories never injects conflicting preferences."
  (:require [clojure.string :as str]))

(def ^:private stopwords
  #{"a" "an" "the" "is" "are" "was" "were" "be" "been" "to" "of" "in" "on"
    "for" "with" "at" "by" "from" "that" "this" "it" "i" "we" "you" "my"
    "our" "your" "please" "always" "really" "very" "just" "should" "would"})

(def ^:private negation-words
  #{"not" "never" "no" "dont" "don't" "doesnt" "doesn't" "avoid" "stop"
    "without" "hate" "dislike" "ban" "forbid" "disable" "skip"})

(def ^:private synonym-map
  {"prefer" "use"
   "prefers" "use"
   "preferred" "use"
   "like" "use"
   "likes" "use"
   "want" "use"
   "wants" "use"
   "using" "use"
   "concise" "brief"
   "short" "brief"
   "terse" "brief"
   "succinct" "brief"
   "verbose" "detailed"
   "long" "detailed"
   "npm" "npm"
   "babashka" "bb"})

(defn- light-stem [w]
  (let [w (get synonym-map w w)]
    (cond
      (and (> (count w) 5) (str/ends-with? w "ing")) (subs w 0 (- (count w) 3))
      (and (> (count w) 4) (str/ends-with? w "ed")) (subs w 0 (- (count w) 2))
      (and (> (count w) 4) (str/ends-with? w "s") (not (str/ends-with? w "ss")))
      (subs w 0 (dec (count w)))
      :else w)))

(defn claim-signature
  "Extract {:polarity (:pos|:neg) :stems #{...}} from free-text memory."
  [text]
  (let [raw-tokens (->> (str/lower-case (str (or text "")))
                        (re-seq #"[a-z0-9']+")
                        vec)
        neg-count (count (filter negation-words raw-tokens))
        polarity (if (odd? neg-count) :neg :pos)
        stems (->> raw-tokens
                   (remove negation-words)
                   (remove stopwords)
                   (map #(str/replace % #"'" ""))
                   (map light-stem)
                   (remove str/blank?)
                   set)]
    {:polarity polarity
     :stems stems}))

(defn- jaccard [a b]
  (if (and (empty? a) (empty? b))
    0.0
    (let [inter (count (filter a b))
          union (- (+ (count a) (count b)) inter)]
      (if (zero? union) 0.0 (/ (double inter) union)))))

(defn same-claim?
  "True when two texts express the same claim with the same polarity."
  [a b]
  (let [sa (claim-signature a)
        sb (claim-signature b)]
    (and (seq (:stems sa))
         (= (:polarity sa) (:polarity sb))
         (>= (jaccard (:stems sa) (:stems sb)) 0.75))))

(defn contradicts?
  "True when two texts target the same subject stems with opposite polarity."
  [a b]
  (let [sa (claim-signature a)
        sb (claim-signature b)]
    (and (seq (:stems sa))
         (not= (:polarity sa) (:polarity sb))
         (>= (jaccard (:stems sa) (:stems sb)) 0.65))))

(defn promote-status
  "Promote belief status based on evidence count."
  [evidence-count]
  (let [n (long (or evidence-count 1))]
    (cond
      (>= n 3) :confirmed
      (= n 2) :held
      :else :candidate)))

(defn active-entry?
  "True when an entry is eligible for search/injection (not :contested or :retired)."
  [entry]
  (not (contains? #{:contested :retired} (:memory/status entry))))

(defn upsert-belief
  "Insert or merge `new-entry` into `entries` with deduplication and
   non-destructive contradiction handling. Returns [updated-entries saved-entry]."
  [entries new-entry]
  (let [entries (vec (or entries []))
        new-text (:memory/text new-entry)
        match-idx (first (keep-indexed
                          (fn [idx e]
                            (when (and (active-entry? e)
                                       (same-claim? (:memory/text e) new-text))
                              idx))
                          entries))]
    (if (some? match-idx)
      (let [old (nth entries match-idx)
            ev (inc (long (or (:memory/evidence-count old) 1)))
            kind (if (or (= :curated (:memory/kind old))
                         (= :curated (:memory/kind new-entry)))
                   :curated
                   :raw)
            merged (assoc old
                          :memory/kind kind
                          :memory/evidence-count ev
                          :memory/status (promote-status ev))]
        [(assoc entries match-idx merged) merged])
      (let [contradicted-ids (atom [])
            marked (mapv (fn [e]
                           (if (and (active-entry? e)
                                    (contradicts? (:memory/text e) new-text))
                             (do (swap! contradicted-ids conj (:memory/id e))
                                 (assoc e :memory/status :contested))
                             e))
                         entries)
            fresh (cond-> (assoc new-entry
                                 :memory/status :candidate
                                 :memory/evidence-count 1)
                    (seq @contradicted-ids)
                    (assoc :memory/supersedes (vec @contradicted-ids)))]
        [(conj marked fresh) fresh]))))
