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
            [bb-agent.goal.control :as control]
            [bb-agent.rsi :as rsi]
            [bb-agent.session :as session]))

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

(defn goal-detail
  "One goal workspace's operator view: run.log tail (bounded), the
  control state from the goal's OWN run workdir (config.edn :workdir —
  the same place `bb goal pause` writes), and a run verdict heuristic
  from the log's last line. Read-only."
  [slug]
  (let [ws (fs/path (home) "goals" (str slug))]
    (when (fs/directory? ws)
      (let [cfg      (or (read-edn (fs/path ws "config.edn")) {})
            workdir  (or (:workdir cfg) (str ws))
            lines    (->> (str/split-lines (or (slurp-quiet (fs/path ws "run.log")) ""))
                          (remove str/blank?))
            last     (or (last lines) "")
            verdict  (cond
                       (str/includes? (str/upper-case last) "FULFILLED") :fulfilled
                       (str/includes? (str/upper-case last) "HALT") :halted
                       :else (if (seq lines) :running :authored))]
        {:dir           (str slug)
         :title         (or (:name (read-edn (fs/path ws "project.edn"))) (str slug))
         :config-exists (map? cfg)
         :workdir       workdir
         :verdict       (name verdict)
         :control       (control/state {:workdir workdir})
         :log           (mapv #(trunc % 400) (vec (take-last 80 lines)))
         :log-lines     (count lines)}))))

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

(defn usage-series
  "Daily turn/token/cost buckets for the deck sparkline, zero-filled —
  a day with no turns is a zero bar, not a gap that lies about the
  past. Newest last, so time flows right on the chart. Unpriced turns
  contribute 0.0 cost, the honest number for them."
  ([] (usage-series 30))
  ([days]
   (let [by-day (->> (events)
                     (filter #(= :turn (:usage/event %)))
                     (group-by (fn [e] (subs (str (:created/at e)) 0 10))))
         today  (java.time.LocalDate/now)
         bucket (fn [i]
                  (let [d  (str (.minusDays today i))
                        es (get by-day d)]
                    {:date   d
                     :turns  (count es)
                     :tokens (reduce + 0 (keep :tokens/total es))
                     :cost   (reduce + 0.0 (keep :cost/estimate-usd es))}))]
     (mapv bucket (range (dec days) -1 -1)))))

;; ---------- cockpit (interactive lane) ----------

(defn session-list
  "Live session metadata, newest first — the cockpit's session picker.
  Reads the same metadata files the turn engine touches, so a session
  created over Telegram appears here and vice versa."
  []
  (->> (session/list-metadata)
       (mapv (fn [m] {:id      (:session/id m)
                      :updated (:updated/at m)
                      :model   (:model m)
                      :cwd     (:cwd m)}))))

(defn transcript
  "One session's turn history, oldest first, paginated from the TAIL:
  page 1 is the newest `size` turns (the cockpit's landing view), page 2
  the batch before it. Text fields arrive UNTRUNCATED — parity row 12:
  Telegram reads the same session file without caps. The envelope
  carries page/total/has-more so the UI can offer 'load older' without
  guessing. The session file is the durable record both channels append
  to under the same lock — this reader never writes, it renders."
  ([session-id] (transcript session-id 60 1))
  ([session-id size]
   (transcript session-id size 1))
  ([session-id size page]
   (let [turns (vec (session/load-turns session-id))
         total (count turns)
         size (max 1 (long (or size 60)))
         page (max 1 (long (or page 1)))
         to (max 0 (- total (* (dec page) size)))
         from (max 0 (- to size))
         rows (mapv (fn [t]
                      {:input  (:user/input t)
                       :final  (:assistant/final t)
                       :source (some-> (:source t) name)
                       :ok     (boolean (:turn/ok t true))})
                    (subvec turns from to))]
     {:page page
      :total total
      :has-more (pos? from)
      :rows rows})))
