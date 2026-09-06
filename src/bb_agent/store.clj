(ns bb-agent.store
  "BUY (2026-09-06, pods gap analysis): the usage ledger becomes queryable
  SQL via the go-sqlite3 pod. The EDN ledger stays the write path and the
  source of truth; the sqlite file is a derived index rebuilt from it --
  one-way data flow, no dual-write steady state. Rebuild is cheap at this
  ledger's size, so queries always index-then-query: no staleness machinery."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.usage :as usage]
            [clojure.string :as str]
            [pod.babashka.go-sqlite3 :as sqlite]))

(defn index-file []
  (fs/path (config/home) "state" "usage-index.db"))

(def ^:private schema!
  ["create table if not exists usage_events (created_at text, session_id text, provider text, model text, tokens_input integer, tokens_output integer, tokens_cache_read integer, tokens_cache_write integer, tokens_total integer, cost_estimate_usd real, ok integer)"])

(defn- event-row
  [e]
  [(str (:created/at e))
   (str (:session/id e))
   (str (:provider e))
   (str (:model e))
   (long (:tokens/input e 0))
   (long (:tokens/output e 0))
   (long (:tokens/cache-read e 0))
   (long (:tokens/cache-write e 0))
   (long (:tokens/total e 0))
   (double (:cost/estimate-usd e 0.0))
   (if (false? (:ok e)) 0 1)])

(defn- normalize-row
  "Pod rows come back with whatever key type the driver chose; pin keywords."
  [row]
  (into {} (map (fn [[k v]] [(keyword k) v]) row)))

(defn index-usage!
  "Rebuild the sqlite index from the EDN ledger; returns the row count."
  []
  (let [db (str (index-file))]
    (fs/create-dirs (fs/parent (index-file)))
    (doseq [s schema!]
      (sqlite/execute! db [s]))
    (sqlite/execute! db ["delete from usage_events"])
    (doseq [e (usage/load-events)]
      (sqlite/execute! db (into ["insert into usage_events (created_at, session_id, provider, model, tokens_input, tokens_output, tokens_cache_read, tokens_cache_write, tokens_total, cost_estimate_usd, ok) values (?,?,?,?,?,?,?,?,?,?,?)"]
                                (event-row e))))
    (-> (sqlite/query db ["select count(*) as n from usage_events"])
        first normalize-row :n)))

(defn query
  "SELECT/WITH-only SQL over the index. The index is rebuilt first, so a
  query is always fresh. Rows come back as maps with keyword keys."
  [sql]
  (when-not (re-find #"(?is)^\s*(select|with)\b" (str sql))
    (throw (ex-info "usage:query is SELECT-only (the EDN ledger remains the write path)" {:sql sql})))
  (let [db (str (index-file))]
    (index-usage!)
    (mapv normalize-row (sqlite/query db [sql]))))

(defn -main
  "bb usage:query \"select ...\""
  [& args]
  (let [sql (first args)]
    (when (or (nil? sql) (str/blank? sql))
      (println "Usage: bb usage:query \"select ...\"")
      (System/exit 2))
    (doseq [row (query sql)]
      (prn row))))
