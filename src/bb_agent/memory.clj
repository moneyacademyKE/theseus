(ns bb-agent.memory
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
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
  ;; Idempotent migration for the curated tier: the ALTER fails once
  ;; the column exists, and that failure IS the migration being
  ;; already applied.
  (try (sqlite/execute! conn ["ALTER TABLE memories ADD COLUMN kind TEXT"])
       (catch Exception _ nil))
  nil)

(defn- normalize-row [row]
  (let [m (into {} (map (fn [[k v]] [(keyword k) v]) row))]
    {:memory/id (:id m)
     :memory/text (or (:text m) "")
     :memory/kind (or (some-> (:kind m) edn/read-string) :raw)}))

(defn- sqlite-load-memories []
  (with-sqlite
    (fn [conn]
      (ensure-sqlite! conn)
      (->> (sqlite/query conn ["SELECT id, text, kind FROM memories ORDER BY rowid"])
           (mapv normalize-row)))))

(defn- sqlite-save-memories! [entries]
  (with-sqlite
    (fn [conn]
      (ensure-sqlite! conn)
      (sqlite/execute! conn ["BEGIN IMMEDIATE"])
      (try
        (sqlite/execute! conn ["DELETE FROM memories"])
        (doseq [{:memory/keys [id text kind]} entries]
          (sqlite/execute! conn ["INSERT INTO memories (id, text, kind) VALUES (?, ?, ?)"
                                 (str id)
                                 (or text "")
                                 (when kind (pr-str kind))]))
        (sqlite/execute! conn ["COMMIT"])
        (catch Exception e
          (try (sqlite/execute! conn ["ROLLBACK"])
               (catch Exception _ nil))
          (throw e)))
      (vec entries))))

(defn load-memories []
  (case (backend)
    :sqlite (sqlite-load-memories)
    (let [path (memory-file)]
      (if (fs/regular-file? path)
        (edn/read-string (slurp (str path)))
        []))))

(defn save-memories! [entries]
  (case (backend)
    :sqlite (sqlite-save-memories! entries)
    (let [path (memory-file)]
      (fs/create-dirs (fs/parent path))
      (spit (str path) (pr-str (vec entries)))
      (vec entries))))

(defn add-memory!
  "Append an entry. Kind defaults to :raw; :curated entries rank
  before raw ones in search regardless of score."
  ([text] (add-memory! text :raw))
  ([text kind]
   (let [entry {:memory/id (str (random-uuid))
                :memory/text text
                :memory/kind kind}
         entries (conj (load-memories) entry)]
     (save-memories! entries)
     entry)))

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
        (map (fn [entry]
               (assoc entry :memory/score (score-entry query entry))))
        (filter #(pos? (:memory/score % 0)))
        (sort-by (juxt kind-rank (comp - :memory/score) :memory/text))
        (take limit)
        vec)))

(defn attach-memories [prompt]
  (search-memories prompt 5))
