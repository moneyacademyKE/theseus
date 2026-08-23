(ns bb-agent.semantic-memory
  "Cross-session semantic memory.

  Ported from hermes-beam's semantic_search.gleam and
  cross_session_search.gleam, adapted to theseus shapes: the
  summarizer and scorer arrive as injected functions (the
  compression.clj precedent), so the module is zero-dep and
  offline-testable. Storage is one EDN file — session-id ->
  {:summary :updated/at} — refreshed automatically after each
  completed turn when :semantic-memory {:enabled true} in config.

  Ranking is BM25/IDF with an age-decay multiplier. hermes-beam's
  embedding+cosine shape (hash-embedding over 64 dims) was token
  overlap in vector costume with no IDF — BM25 is the same idea with
  honest weights and no stored vectors to go stale."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------- BM25 ranking core ----------

(def ^:private k1 1.2)
(def ^:private b 0.75)
(def ^:private half-life-days 30.0)

(defn tokenize [text]
  (->> (or text "")
       str/lower-case
       (re-seq #"[a-z0-9]+")
       vec))

(defn- idf-fn [docs]
  (let [n (count docs)
        df (frequencies (mapcat (comp set tokenize) docs))]
    (fn [token]
      (let [f (get df token 0)]
        (Math/log (+ 1.0 (/ (+ (- n f) 0.5) (+ f 0.5))))))))

(defn- bm25-score [query-tokens doc-tokens avg-len idf*]
  (let [len (count doc-tokens)
        tfs (frequencies doc-tokens)]
    (reduce (fn [score token]
              (let [tf (get tfs token 0)]
                (if (pos? tf)
                  (+ score (* (idf* token)
                              (/ (* tf (inc k1))
                                 (+ tf (* k1 (- 1.0 (* b (- 1.0 (/ len avg-len)))))))))
                  score)))
            0.0
            (distinct query-tokens))))

(defn- age-in-days [updated-at-ms now-ms]
  (max 0.0 (/ (- (long now-ms) (long (or updated-at-ms 0))) 86400000.0)))

(defn recency-decay
  "1/(1 + age/half-life): fresh records keep their score, month-old
  records halve it. Missing timestamps count as fresh."
  [record now-ms]
  (let [updated (:updated/at record)]
    (if (nil? updated)
      1.0
      (/ 1.0 (inc (/ (age-in-days updated now-ms) half-life-days))))))

(defn rank-summaries
  "Rank records (maps with :summary, optional :updated/at) against a
  query. BM25 x recency decay, best first, positive scores only."
  ([query records] (rank-summaries query records (System/currentTimeMillis)))
  ([query records now-ms]
   (let [query-tokens (tokenize query)
         records (vec records)
         docs (map #(tokenize (:summary %)) records)
         avg-len (if (seq docs)
                   (/ (transduce (map count) + 0 docs) (count docs))
                   0.0)
         idf* (idf-fn docs)]
     (->> records
          (map (fn [record]
                 (assoc record :score
                        (* (bm25-score query-tokens (tokenize (:summary record)) avg-len idf*)
                           (recency-decay record now-ms)))))
          (filter #(pos? (:score % 0)))
          (sort-by (comp - :score))
          vec))))

;; ---------- store ----------

(defn store-file []
  (fs/path (config/home) "state" "session-summaries.edn"))

(defn load-summaries []
  (let [path (store-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      {})))

(defn- save-summaries! [store]
  (let [path (store-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str store))
    store))

;; ---------- summarize + index (cross_session_search.gleam) ----------

(defn transcript
  "theseus turns -> transcript lines: user input and final answer per
  turn (tool rounds stay out; they are machinery, not meaning)."
  [turns]
  (->> turns
       (mapcat (fn [turn]
                 [(when-let [u (:user/input turn)] (str "user: " u))
                  (when-let [a (:assistant/final turn)] (str "assistant: " a))]))
       (remove str/blank?)
       vec))

(defn plain-summary
  "Default summarizer: the transcript's head and tail joined, capped at
  `max-chars`. Inject an LLM summarizer for real compression."
  ([turns] (plain-summary turns 2000))
  ([turns max-chars]
   (let [lines (transcript turns)
         full (str/join "\n" lines)
         head (str/join "\n" (take 10 lines))
         tail (str/join "\n" (take-last 6 lines))
         chosen (if (< (count full) max-chars) full (str head "\n...\n" tail))]
     (if (> (count chosen) max-chars)
       (subs chosen 0 max-chars)
       chosen))))

(defn index-session!
  "Summarize a session's history and store it, replacing any previous
  entry for that session. Empty sessions index nothing."
  ([session-id] (index-session! session-id {}))
  ([session-id {:keys [summarize-fn now]
                :or {summarize-fn plain-summary
                     now #(System/currentTimeMillis)}}]
   (let [turns (session/load-turns session-id)]
     (when (seq turns)
       (let [record {:session/id session-id
                     :summary (summarize-fn turns)
                     :updated/at (now)}]
         (save-summaries! (assoc (load-summaries) session-id record))
         record)))))

;; ---------- search + context (cross_session_search.gleam) ----------

(defn semantic-search
  "Rank stored session summaries against `query`. Returns scored
  records, best first. Injectable `:score-fn` replaces the BM25
  default; `:now-ms` pins time in tests."
  ([query] (semantic-search query {}))
  ([query {:keys [score-fn now-ms top-k]
           :or {score-fn rank-summaries}}]
   (let [hits (score-fn query (vals (load-summaries)) (or now-ms (System/currentTimeMillis)))]
     (if top-k (vec (take top-k hits)) hits))))

(defn semantic-context
  "hermes-beam's get_semantic_context: top-k summaries as a context
  block, or nil when there is nothing relevant."
  ([query] (semantic-context query {}))
  ([query {:keys [top-k] :or {top-k 3} :as opts}]
   (let [hits (take top-k (semantic-search query opts))]
     (when (seq hits)
       (str "Related historical context from past sessions:\n"
            (str/join "\n" (map (fn [{:keys [session/id summary]}]
                                  (str "- [Session " id "]: " summary))
                                hits)))))))

;; ---------- core wiring seam ----------

(defn enabled? [cfg]
  (boolean (get-in cfg [:semantic-memory :enabled])))

(defn attach-context
  "Context block for a turn prompt, or nil when disabled/empty."
  [prompt cfg]
  (when (enabled? cfg)
    (semantic-context prompt {:top-k (or (get-in cfg [:semantic-memory :top-k]) 3)})))
