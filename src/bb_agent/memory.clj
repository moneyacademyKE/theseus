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
  nil)

(defn- sqlite-load-memories []
  (ensure-sqlite!)
  (->> (str/split-lines (sqlite! "-separator" "\t" "SELECT id, text FROM memories ORDER BY rowid;"))
       (remove str/blank?)
       (mapv (fn [line]
               (let [[id text] (str/split line #"\t" 2)]
                 {:memory/id id
                  :memory/text (or text "")})))))

(defn- sql-quote [value]
  (str "'" (str/replace (or value "") "'" "''") "'"))

(defn- sqlite-save-memories! [entries]
  (ensure-sqlite!)
  (sqlite! "DELETE FROM memories;")
  (doseq [{:memory/keys [id text]} entries]
    (sqlite! (str "INSERT INTO memories (id, text) VALUES ("
                  (sql-quote id)
                  ", "
                  (sql-quote text)
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

(defn add-memory! [text]
  (let [entry {:memory/id (str (random-uuid))
               :memory/text text}
        entries (conj (load-memories) entry)]
    (save-memories! entries)
    entry))

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

(defn search-memories
  ([query] (search-memories query 5))
  ([query limit]
   (->> (load-memories)
        (map (fn [entry]
               (assoc entry :memory/score (score-entry query entry))))
        (filter #(pos? (:memory/score % 0)))
        (sort-by (juxt (comp - :memory/score) :memory/text))
        (take limit)
        vec)))

(defn attach-memories [prompt]
  (search-memories prompt 5))
