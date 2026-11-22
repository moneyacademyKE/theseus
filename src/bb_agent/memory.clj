(ns bb-agent.memory
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.beliefs :as beliefs]
            [bb-agent.config :as config]
            [bb-agent.security :as sec]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [pod.babashka.go-sqlite3 :as sqlite]))

(defn memory-file []
  (fs/path (config/home) "state" "memory.edn"))

(defn sqlite-memory-file []
  (fs/path (config/home) "state" "memory.sqlite"))

(defn backend []
  (let [configured (:memory/backend (config/load-config))]
    (if (= configured :sqlite)
      :sqlite
      :edn)))

(defn- with-sqlite [f]
  (let [path (sqlite-memory-file)]
    (fs/create-dirs (fs/parent path))
    (let [conn (sqlite/get-connection (str path))]
      (try
        (f conn)
        (finally
          (sqlite/close-connection conn))))))

(defn- ensure-sqlite! [conn]
  (sqlite/execute! conn ["CREATE TABLE IF NOT EXISTS memories (id TEXT PRIMARY KEY, text TEXT NOT NULL)"])
  (doseq [sql ["ALTER TABLE memories ADD COLUMN kind TEXT"
               "ALTER TABLE memories ADD COLUMN status TEXT"
               "ALTER TABLE memories ADD COLUMN evidence_count INTEGER"]]
    (try (sqlite/execute! conn [sql])
         (catch Exception _ nil)))
  nil)

(defn- normalize-row [row]
  (let [m (into {} (map (fn [[k v]] [(keyword k) v]) row))
        status (some-> (:status m) edn/read-string)
        ev (:evidence_count m)]
    (cond-> {:memory/id (:id m)
             :memory/text (or (:text m) "")
             :memory/kind (or (some-> (:kind m) edn/read-string) :raw)}
      status (assoc :memory/status status)
      (some? ev) (assoc :memory/evidence-count (long ev)))))

(defn- sqlite-load-memories []
  (with-sqlite
    (fn [conn]
      (ensure-sqlite! conn)
      (->> (sqlite/query conn ["SELECT id, text, kind, status, evidence_count FROM memories ORDER BY rowid"])
           (mapv normalize-row)))))

(defn- sqlite-save-memories! [entries]
  (with-sqlite
    (fn [conn]
      (ensure-sqlite! conn)
      (sqlite/execute! conn ["BEGIN IMMEDIATE"])
      (try
        (sqlite/execute! conn ["DELETE FROM memories"])
        (doseq [{:memory/keys [id text kind status evidence-count]} entries]
          (sqlite/execute! conn ["INSERT INTO memories (id, text, kind, status, evidence_count) VALUES (?, ?, ?, ?, ?)"
                                 (str id)
                                 (or text "")
                                 (when kind (pr-str kind))
                                 (when status (pr-str status))
                                 (when (some? evidence-count) (long evidence-count))]))
        (sqlite/execute! conn ["COMMIT"])
        (catch Exception e
          (try (sqlite/execute! conn ["ROLLBACK"])
               (catch Exception _ nil))
          (throw e)))
      (vec entries))))

(defn load-memories []
  (case (backend)
    :sqlite (sqlite-load-memories)
    (aio/read-edn-safe (memory-file) [])))

(defn save-memories! [entries]
  (case (backend)
    :sqlite (sqlite-save-memories! entries)
    (do
      (aio/write-private-file! (memory-file) (pr-str (vec entries)))
      (vec entries))))

(defn add-memory!
  "Upsert a belief entry with local negation-aware deduplication,
   non-destructive contradiction handling, and optional authorship-verified
   memory taint gating when opts carries :exposure. Kind defaults to :raw."
  ([text] (add-memory! text :raw))
  ([text kind-or-opts]
   (let [opts (if (map? kind-or-opts) kind-or-opts {:kind kind-or-opts})
         kind (or (:kind opts) :raw)
         verdict (when (:exposure opts)
                   (sec/memory-trust-verdict text (:exposure opts) (:user-messages opts)))]
     (if (and verdict (not (:allowed? verdict)))
       {:blocked? true :reason (:reason verdict) :verdict verdict}
       (let [entry {:memory/id (str (random-uuid))
                    :memory/text text
                    :memory/kind kind}
             [entries saved] (beliefs/upsert-belief (load-memories) entry)]
         (save-memories! entries)
         saved)))))

(defn curate-memory!
  "Promote the entry with `id` to :curated. Throws when no entry
  carries that id — curating a ghost would silently do nothing."
  [id]
  (let [entries (load-memories)
        matches (filter #(= id (:memory/id %)) entries)]
    (if (empty? matches)
      (throw (ex-info "No memory with that id" {:memory/id id}))
      (let [updated (mapv (fn [entry]
                            (if (= id (:memory/id entry))
                              (assoc entry :memory/kind :curated)
                              entry))
                          entries)]
        (save-memories! updated)
        (assoc (first matches) :memory/kind :curated)))))

(defn- tokenize [text]
  (->> (or text "")
       str/lower-case
       (re-seq #"[a-z0-9]+")
       vec))

(defn- score-entry [query entry]
  (let [query-tokens (tokenize query)
        text-tokens (tokenize (:memory/text entry))
        text-set (set text-tokens)]
    (reduce (fn [score token]
              (if (contains? text-set token)
                (inc score)
                score))
            0
            query-tokens)))

(defn- kind-rank [entry]
  (if (= :curated (:memory/kind entry)) 0 1))

(defn search-memories
  ([query] (search-memories query 5))
  ([query limit]
   (->> (load-memories)
        (filter beliefs/active-entry?)
        (map (fn [entry]
               (assoc entry :memory/score (score-entry query entry))))
        (filter #(pos? (:memory/score % 0)))
        (sort-by (fn [entry] [(kind-rank entry) (- (:memory/score entry)) (:memory/id entry)]))
        (take limit)
        vec)))

(defn attach-memories [prompt]
  (search-memories prompt 3))
