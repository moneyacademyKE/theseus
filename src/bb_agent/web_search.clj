(ns bb-agent.web-search
  "Full-text search over session transcripts. An FTS5 index (go-sqlite3
  pod, the same pod the usage index rides) derived from the EDN session
  files — the same one-way flow as bb-agent.store: the files are the
  write path and the truth, search.db is a derived index.

  Refresh is mtime-incremental, not a rebuild: re-reading every session
  file per query would cost seconds at this corpus, and the usage-index
  trick of rebuild-per-query does not survive contact with 600+ session
  transcripts. An 'indexed' table records what was seen; a query pays
  only for files that changed since the last one.

  Snippets return term boundaries as \\u0001/\\u0002 markers — control
  characters cannot occur in transcript text, so the client can split
  on them without an HTML-escaping dance."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [pod.babashka.go-sqlite3 :as sqlite]))

(def ^:private session-file-re #"[.]edn$")

(defn index-file
  "Where the derived index lives: <home>/state/search.db."
  []
  (fs/path (config/home) "state" "search.db"))

(defn- session-files
  "Every EDN session file with its mtime — the indexing worklist."
  []
  (let [dir (fs/path (config/home) "state" "sessions")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter (fn [p] (and (fs/regular-file? p)
                                (re-find session-file-re (str p)))))
           (map (fn [p] {:id    (str/replace (fs/file-name (str p)) #"[.]edn$" "")
                         :path  p
                          :mtime (.toMillis (fs/last-modified-time p))})))
      [])))

(defn- turn-texts
  "The searchable text of one session: every user input and assistant
  final, in order. Metadata never enters the index — secrets live in
  tool args, not these fields, and the transcript endpoint already
  bounds what the deck shows."
  [turns]
  (mapcat (fn [t] [(str (:user/input t "")) (str (:assistant/final t ""))]) turns))

(defn- session-row
  "One session's index row: id, a short title, the joined text."
  [{:keys [id path]}]
  (let [raw (try (edn/read-string (slurp (str path))) (catch Exception _ []))
        turns (if (sequential? raw) (filter map? raw) [])
        texts (mapv str (turn-texts turns))
        first-trimmed (some-> (first texts) str/trim)
        title (if (and first-trimmed (not (str/blank? first-trimmed)))
                (subs first-trimmed 0 (min 80 (count first-trimmed)))
                id)]
    {:id id :title title :body (str/join "\n\n" texts)}))

(def ^:private index-lock (Object.))

(defn- with-search-conn [db f]
  (let [conn (sqlite/get-connection db)]
    (try
      (f conn)
      (finally
        (sqlite/close-connection conn)))))

(defn- refresh-unlocked! [db]
  (fs/create-dirs (fs/parent (index-file)))
  (with-search-conn db
    (fn [conn]
      (sqlite/execute! conn ["PRAGMA busy_timeout = 5000"])
      (sqlite/execute! conn ["create virtual table if not exists sessions_fts using fts5(id unindexed, title, body)"])
      (sqlite/execute! conn ["create table if not exists indexed (id text primary key, mtime integer)"])
      (let [known (into {} (map (fn [r] [(str (:id r)) (:mtime r)])
                                (sqlite/query conn ["select id, mtime from indexed"])))
            files (session-files)
            file-ids (set (map #(str (:id %)) files))
            stale (filter (fn [f] (not= (get known (str (:id f)))
                                        (:mtime f))) files)
            gone  (remove (fn [[id _]] (contains? file-ids (str id))) known)]
        (when (or (seq gone) (seq stale))
          (sqlite/execute! conn ["BEGIN IMMEDIATE"])
          (try
            (doseq [[id _mtime] gone]
              (sqlite/execute! conn ["delete from sessions_fts where id = ?" (str id)])
              (sqlite/execute! conn ["delete from indexed where id = ?" (str id)]))
            (doseq [f stale]
              (let [{:keys [id title body]} (session-row f)]
                (sqlite/execute! conn ["delete from sessions_fts where id = ?" id])
                (sqlite/execute! conn ["insert into sessions_fts (id, title, body) values (?,?,?)" id title body])
                (sqlite/execute! conn ["insert or replace into indexed (id, mtime) values (?,?)" id (:mtime f)])))
            (sqlite/execute! conn ["COMMIT"])
            (catch Exception e
              (try (sqlite/execute! conn ["ROLLBACK"]) (catch Exception _ nil))
              (throw e))))
        (count stale)))))

(defn refresh!
  "Bring the index up to date with the session files: index new files,
  re-index files whose mtime moved, drop rows whose file vanished.
  Serialized and transactional via one go-sqlite3 connection.
  Returns the number of (re)indexed sessions."
  []
  (locking index-lock
    (refresh-unlocked! (str (index-file)))))

(defn- sanitize-query
  "FTS5 has its own query grammar (quotes, colons, AND/OR/NOT, hyphens);
  raw user text can error or trigger boolean/column operators. Split into
  alphanumeric/underscore tokens and quote each one as a literal term."
  [q]
  (->> (str/split (str (or q "")) #"[^A-Za-z0-9_]+")
       (remove str/blank?)
       (map #(str "\"" % "\""))
       (str/join " ")))

(defn search
  "Query the index (refreshed first — a search is always fresh).
  Returns [{:id :title :snippet}]; snippet carries \\u0001/\\u0002 term
  markers the client splits on. Empty/garbage queries return []."
  ([q] (search q 30))
  ([q limit]
   (let [match (sanitize-query q)]
     (if (str/blank? match)
       []
       (locking index-lock
         (let [db (str (index-file))]
           (refresh-unlocked! db)
           (with-search-conn db
             (fn [conn]
               (mapv (fn [row]
                       (into {} (map (fn [[k v]] [(keyword k) v]) row)))
                     (sqlite/query conn ["select id, title, snippet(sessions_fts, 2, char(1), char(2), '…', 12) as snippet from sessions_fts where sessions_fts match ? order by rank limit ?"
                                         match (long (or limit 30))]))))))))))
