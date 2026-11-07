(ns bb-agent.web-data
  "Read-only adapters: live Theseus state -> plain maps for the web deck.

  One reader per column, each tolerant of a missing or mid-append file:
  an empty column tells the truth, a 500 lies. No writes, no caching —
  state is read fresh on every request; the files are small and the deck
  polls slowly. Auth lives in bb-agent.web-auth; the master-password
  record sits at the home root as a dotfile (outside state/) so the
  state-hygiene gate stays untouched."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bb-agent.config :as config]
            [bb-agent.rsi :as rsi]))

;; ---------- utilities ----------

(defn- home [] (config/home))

(defn- read-edn [path]
  (try
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))
    (catch Exception _ nil)))

(defn- slurp-quiet [path]
  (try
    (when (fs/regular-file? path)
      (slurp (str path)))
    (catch Exception _ nil)))

(defn- file-ms [path]
  (try
    (when (fs/regular-file? path)
      (.lastModified (java.io.File. (str path))))
    (catch Exception _ nil)))

(defn- trunc [s n]
  (let [s (str (or s ""))]
    (if (> (count s) n) (subs s 0 n) s)))

(defn- pid-alive [pid]
  (when-let [pid (and pid (re-matches #"\d+" (str/trim (str pid))))]
    (zero? (:exit (p/shell {:continue true :out :string :err :string}
                           "kill" "-0" pid)))))

(defn- events []
  (or (read-edn (fs/path (home) "state" "usage.edn")) []))

;; ---------- columns ----------

(defn recent-turns
  "Newest-first LLM turns from the usage ledger."
  [n]
  (->> (events)
       (filter #(= :turn (:usage/event %)))
       (take-last n)
       (reverse)
       (mapv (fn [e] {:ts      (:created/at e)
                      :model   (:model e)
                      :ok      (boolean (:ok e))
                      :session (:session/id e)
                      :tokens  (:tokens/total e)
                      :cost    (:cost/estimate-usd e)}))))

(defn tool-rows
  "Per-tool outcome rows via the RSI funnel aggregate (composition, not a
  second implementation of the same fold)."
  []
  (mapv (fn [[t m]]
          (assoc (select-keys m [:calls :ok :fail :denied])
                 :tool (name t)))
        (rsi/tools-aggregate (events))))

(defn rsi-snapshot
  []
  (let [path (fs/path (home) "state" "rsi" "digest.md")]
    {:md (or (slurp-quiet path) "")
     :ms (file-ms path)}))

(defn session-rows
  "Recent session summaries, newest first."
  []
  (->> (or (read-edn (fs/path (home) "state" "session-summaries.edn")) {})
       (vals)
       (sort-by :updated/at >)
       (mapv (fn [s] {:id      (:session/id s)
                      :updated (:updated/at s)
                      :summary (-> (or (:summary s) "")
                                   (str/replace #"\s+" " ")
                                   (trunc 400))}))))

(defn goal-rows
  "Goal workspaces: name, liveness (watcher pid), last activity, last log line."
  []
  (->> (try (fs/list-dir (fs/path (home) "goals")) (catch Exception _ []))
       (filter fs/directory?)
       (mapv (fn [d]
               (let [name     (fs/file-name d)
                     proj     (or (read-edn (fs/path d "project.edn")) {})
                     run-path (fs/path d "run.log")
                     last     (->> (str/split-lines (or (slurp-quiet run-path) ""))
                                   (remove str/blank?)
                                   (last))]
                 {:name    (or (:name proj) name)
                  :dir     name
                  :active  (boolean (pid-alive (slurp-quiet (fs/path d "watcher.pid"))))
                  :updated (max (or (file-ms run-path) 0)
                                (or (file-ms (fs/path d "watch.log")) 0))
                  :last    (trunc last 140)})))
       (sort-by :updated >)))

(defn schedule-rows
  "Recent schedule runs, newest first."
  [n]
  (->> (or (read-edn (fs/path (home) "state" "schedule-runs.edn")) [])
       (take-last n)
       (reverse)
       (mapv (fn [r] {:id     (str (:schedule/id r))
                      :status (some-> (:status r) name)
                      :at     (:at r)
                      :final  (trunc (:assistant/final r) 160)}))))

(defn status
  "System snapshot: poller liveness, ledger freshness, digest age."
  []
  (let [evts    (events)
        pids    (map #(str/trim (or (slurp-quiet (fs/path (home) "state" %)) ""))
                     ["telegram-poller.pid" "poller.pid" "gateway.pid"])
        pid     (some #(when (re-matches #"\d+" %) %) pids)
        alive   (boolean (some pid-alive pids))
        last-ts (->> evts (keep :created/at) (last))]
    {:now          (str (java.time.Instant/now))
     :poller       {:alive alive :pid pid}
     :events       (count evts)
     :tool-events  (count (filter #(= :tool (:usage/event %)) evts))
     :last-event   last-ts
     :digest-ms    (file-ms (fs/path (home) "state" "rsi" "digest.md"))
     :schedules    (count (or (read-edn (fs/path (home) "state" "schedules.edn")) []))
     :goal-count   (count (goal-rows))}))
