(ns bb-agent.knowledge-usage
  "Per-page knowledge usage telemetry: :search-hit, :read, :cited by caller surface
   (ported from LoopForge domain/knowledge_usage.go).
   Append-only EDN-lines ledger under <home>/state/knowledge-usage.ednl so
   `bb knowledge prune` and RSI can measure which knowledge pages actually earn
   their keep. Best-effort: telemetry failure never breaks a tool call."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn ledger-path []
  (fs/path (config/home) "state" "knowledge-usage.ednl"))

(defn record!
  "Append one knowledge-usage event {:page \"foo.md\" :action :search-hit|:read|:cited :surface ...}."
  [{:keys [page action surface session-id]}]
  (when (and (string? page) (not (str/blank? page)) action)
    (try
      (let [p (ledger-path)
            entry (cond-> {:page (str (fs/file-name page))
                           :action action
                           :surface (or surface :chat)
                           :ts (str (java.time.Instant/now))}
                    session-id (assoc :session/id session-id))]
        (fs/create-dirs (fs/parent p))
        (spit (str p) (str (pr-str entry) "\n") :append true)
        entry)
      (catch Exception _ nil))))

(defn load-events
  "Read all recorded knowledge usage events from disk."
  []
  (let [p (ledger-path)]
    (if-not (fs/regular-file? p)
      []
      (->> (str/split-lines (slurp (str p)))
           (remove str/blank?)
           (keep #(try (edn/read-string %) (catch Exception _ nil)))
           vec))))

(defn aggregate
  "Aggregate counts per page: {page {:search-hit N :read N :cited N :total N :surfaces #{...}}}."
  ([] (aggregate (load-events)))
  ([events]
   (reduce
    (fn [acc {:keys [page action surface]}]
      (if-not page
        acc
        (let [cur (get acc page {:search-hit 0 :read 0 :cited 0 :total 0 :surfaces #{}})
              k (case action
                  :search-hit :search-hit
                  :read :read
                  :cited :cited
                  nil)]
          (assoc acc page
                 (cond-> (-> cur
                             (update :total inc)
                             (update :surfaces conj (or surface :chat)))
                   k (update k inc))))))
    {}
    events)))
