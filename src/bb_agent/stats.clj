(ns bb-agent.stats
  "Operational stats from ledgers that already exist on disk (audit F5).
   No new infra, no daemon: reads usage events, the outbox, and goal
   run logs, and returns one plain map. `bb stats` prints it."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.usage :as usage]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- outbox-dir []
  (fs/path (config/home) "state" "outbox"))

(defn outbox-summary []
  (let [root (outbox-dir)
        pending (if (fs/exists? root) (count (fs/glob (str root) "*.edn")) 0)
        dead-root (fs/path root "dead")
        dead (if (fs/exists? dead-root) (count (fs/glob (str dead-root) "*.edn")) 0)]
    {:outbox/pending pending :outbox/dead dead}))

(defn- verdict-of [run-log]
  (let [lines (->> (slurp (str run-log))
                   str/split-lines
                   (take-last 20))]
    (cond
      (some #(re-find #"GOAL FULFILLED" %) lines) :fulfilled
      (some #(re-find #"HALT: stall" %) lines)    :stalled
      (some #(re-find #"GOAL FAILED" %) lines)    :failed
      :else :unresolved)))

(defn goal-summary []
  (let [root (str (fs/path (config/home) "goals"))
        registry (let [f (fs/path root "active.edn")]
                   (if (fs/regular-file? f)
                     (edn/read-string (slurp (str f)))
                     {}))
        workspaces (when (fs/exists? root)
                     (->> (fs/list-dir root)
                          (filter fs/directory?)
                          (remove #(str/ends-with? (str %) "logs"))))]
    (-> (reduce (fn [acc ws]
                  (let [rl (fs/path ws "run.log")
                        v (if (fs/regular-file? rl) (verdict-of rl) :no-run)]
                    (update acc v (fnil inc 0))))
                {:goal/active (count registry)
                 :fulfilled 0 :stalled 0 :failed 0 :unresolved 0 :no-run 0}
                workspaces)
        (assoc :goal/workspaces (count workspaces)))))

(defn summary []
  {:usage (usage/report)
   :outbox (outbox-summary)
   :goals (goal-summary)})

(defn print-summary!
  "Human one-screen rendering of `summary`. Returns nil."
  []
  (let [{:keys [usage outbox goals]} (summary)]
    (println "== Theseus stats ==")
    (println (str "home:       " (config/home)))
    (println (format "usage:      %d events, %d tokens, $%.4f est"
                     (:usage/events usage) (:tokens/total usage)
                     (:cost/estimate-usd usage)))
    (println (format "outbox:     %d pending, %d dead-lettered"
                     (:outbox/pending outbox) (:outbox/dead outbox)))
    (println (format "goals:      %d workspaces, %d active | fulfilled %d, stalled %d, failed %d, unresolved %d"
                     (:goal/workspaces goals) (:goal/active goals)
                     (:fulfilled goals) (:stalled goals) (:failed goals) (:unresolved goals)))))
