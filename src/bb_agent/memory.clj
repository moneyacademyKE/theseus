(ns bb-agent.memory
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn memory-file []
  (fs/path (config/home) "state" "memory.edn"))

(defn sqlite-memory-file []
  (fs/path (config/home) "state" "memory.sqlite"))

(defn backend []
  (let [configured (:memory/backend (config/load-config))]
    (if (= configured :sqlite)
      :sqlite
      :edn)))

(defn- sqlite! [& args]
  (fs/create-dirs (fs/parent (sqlite-memory-file)))
  (let [result (apply p/shell {:out :string
                               :err :string
                               :continue true}
                      "sqlite3" (str (sqlite-memory-file)) args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "sqlite3 command failed"
                      {:stderr (:err result)})))
    (:out result)))

(defn- ensure-sqlite! []
  (sqlite! "CREATE TABLE IF NOT EXISTS memories (id TEXT PRIMARY KEY, text TEXT NOT NULL);")
  ;; Idempotent migration for the curated tier: the ALTER fails once
  ;; the column exists, and that failure IS the migration being
  ;; already applied.
  (try (sqlite! "ALTER TABLE memories ADD COLUMN kind TEXT;")
       (catch Exception _ nil))
  nil)

(defn- sqlite-load-memories []
  (ensure-sqlite!)
  (->> (str/split-lines (sqlite! "-separator" "\t" "SELECT id, text, kind FROM memories ORDER BY rowid;"))
       (remove str/blank?)
       (mapv (fn [line]
               (let [[id text kind] (str/split line #"\t" 3)]
                 {:memory/id id
                  :memory/text (or text "")
                  :memory/kind (or (some-> kind edn/read-string) :raw)})))))

(defn- sql-quote [value]
  (str "'" (str/replace (or value "") "'" "''") "'"))

(defn- sqlite-save-memories! [entries]
  (ensure-sqlite!)
  (sqlite! "DELETE FROM memories;")
  (doseq [{:memory/keys [id text kind]} entries]
    (sqlite! (str "INSERT INTO memories (id, text, kind) VALUES ("
                  (sql-quote id)
                  ", "
                  (sql-quote text)
                  ", "
                  (if kind (sql-quote (pr-str kind)) "NULL")
                  ");")))
  (vec entries))

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
