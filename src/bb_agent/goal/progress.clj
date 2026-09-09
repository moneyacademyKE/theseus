(ns bb-agent.goal.progress
  "Workspace-level status + progress reporting: run.log verdict scraping
   and iteration-ledger rendering for topic messages. Extracted from
   goal_bridge.clj (bk-c60f, LOC ceiling). Distinct from goal.status,
   which reports the runner's internal loop state; this reports the
   workspace's outward progress."
  (:require [babashka.fs :as fs]
            [bb-agent.goal.registry :as registry]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn run-status
  "Scrape the run log for the outcome. The runner's own words are the truth:
   GOAL FULFILLED / HALT lines; anything else while the pid lives is running."
  [name]
  (let [log (io/file (registry/goals-root) name "run.log")]
    (cond
      (not (.exists log)) :authored
      :else (let [s (slurp log)]
              (cond
                (str/includes? s "GOAL FULFILLED") :fulfilled
                (str/includes? s "HALT") :halted
                :else :running)))))

(defn list-goals
  "One line per workspace: name + last known status."
  []
  (when (.exists (io/file (registry/goals-root)))
    (->> (fs/list-dir (registry/goals-root))
         (filter #(-> % str io/file .isDirectory))
         (map #(-> % str (str/split #"/") last))
         (remove #{"logs"})
         (sort)
         (map (fn [n] (str n " — " (name (run-status n))))))))

(defn ledger-events
  "All iteration ledgers for a workspace, in run order. The runner writes
   one logs/iter-NNN.edn per iteration — acts carry :progress-before/after,
   halts carry :reason/:world — this is the run's event stream."
  [ws]
  (let [dir (io/file ws "logs")]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(str/ends-with? (str %) ".edn"))
           (sort-by #(str %))
           (keep #(try (edn/read-string (slurp %)) (catch Exception _ nil)))
           (filter map?)
           vec))))

(defn- hhmmss
  "19:14:51 from an ISO-8601 ts; ??:??:?? when absent."
  [ts]
  (or (second (re-find #"T(\d\d:\d\d:\d\d)" (str ts))) "??:??:??"))

(defn- progress-line
  "One ledger event → one compact display line. Acts show progress movement,
   halts show reason + world, done shows the satisfied world."
  [m]
  (case (:event m)
    :act (str "▸ it " (:iteration m) " act"
              (when (and (some? (:progress-before m)) (some? (:progress-after m)))
                (str " " (:progress-before m) "→" (:progress-after m)))
              (when (false? (:progressed? m)) " (no progress)")
              " · " (hhmmss (:ts m)))
    :halt (str "▸ it " (:iteration m) " ⛔ halt:" (name (:reason m))
               (when (:world m) (str " · world " (pr-str (:world m)))))
    :done (str "▸ ✅ fulfilled · world " (pr-str (:world m)))
    (str "▸ it " (:iteration m) " " (name (:event m)))))

(def progress-cap 10)

(defn progress-text
  "The running-state topic message: header with event count + the last
   `progress-cap` lines. One message, edited in place by the watcher."
  [name events]
  (let [n (count events)
        shown (take-last progress-cap events)
        more (max 0 (- n progress-cap))]
    (str "🎯 goal `" name "` — running · " n " events"
         (when (pos? more) (str " (showing last " progress-cap ")"))
         "\n"
         (str/join "\n" (map progress-line shown)))))
