(ns e2e.ops-stats-test
  "Pins `bb stats` (audit F5): one-screen ops summary built ONLY from
   ledgers that already exist -- usage.edn, outbox depth/dead-letters,
   goal run.log verdicts. No new infra."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.stats :as stats]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def ^:dynamic *home* nil)

(defn- fresh-home [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "ops-stats-test"}))]
    (binding [*home* tmp]
      (with-redefs [config/home (constantly tmp)]
        (f)))))

(use-fixtures :each fresh-home)

(defn- seed-ledgers! []
  (let [h *home*]
    ;; usage: two events
    (fs/create-dirs (fs/path h "state"))
    (spit (str (fs/path h "state" "usage.edn"))
          (pr-str [{:usage/event :turn :provider :a :model "m"
                    :tokens/input 10 :tokens/output 5 :tokens/cache-read 0
                    :tokens/cache-write 0 :tokens/total 15 :cost/estimate-usd 0.01
                    :created/at "2026-09-09T00:00:00Z" :session/id "s1"}
                   {:usage/event :turn :provider :a :model "m"
                    :tokens/input 1 :tokens/output 1 :tokens/cache-read 0
                    :tokens/cache-write 0 :tokens/total 2 :cost/estimate-usd 0.0
                    :created/at "2026-09-09T01:00:00Z" :session/id "s1"}]))
    ;; outbox: 2 pending, 1 dead
    (fs/create-dirs (fs/path h "state" "outbox" "dead"))
    (spit (str (fs/path h "state" "outbox" "a.edn")) {:id "a"})
    (spit (str (fs/path h "state" "outbox" "b.edn")) {:id "b"})
    (spit (str (fs/path h "state" "outbox" "dead" "c.edn")) {:id "c"})
    ;; goals: fulfilled, stalled, no-run, a logs dir (excluded), 1 active
    (doseq [[ws verdict] [["ws-fulfilled" "[t] INFO   GOAL FULFILLED {:world {:ok \"pass\"}}"]
                          ["ws-stalled"   "[t] HALT   HALT: stall {:reason :stall}"]
                          ["ws-empty"     nil]]]
      (fs/create-dirs (fs/path h "goals" ws))
      (when verdict
        (spit (str (fs/path h "goals" ws "run.log")) (str verdict "\n"))))
    (fs/create-dirs (fs/path h "goals" "logs"))
    (spit (str (fs/path h "goals" "active.edn"))
          (pr-str {"-100" {196 {:workspace "ws-fulfilled"}}}))))

(deftest summary-counts-from-fixtures
  (seed-ledgers!)
  (let [{:keys [usage outbox goals]} (stats/summary)]
    (is (= 2 (:usage/events usage)))
    (is (= 17 (:tokens/total usage)))
    (is (= {:outbox/pending 2 :outbox/dead 1} outbox))
    (is (= 1 (:goal/active goals)))
    (is (= 3 (:goal/workspaces goals)))
    (is (= 1 (:fulfilled goals)))
    (is (= 1 (:stalled goals)))
    (is (= 1 (:no-run goals)))
    (is (zero? (:failed goals)))))

(deftest empty-home-is-all-zeros
  (let [{:keys [outbox goals]} (stats/summary)]
    (is (= {:outbox/pending 0 :outbox/dead 0} outbox))
    (is (zero? (:goal/workspaces goals)))
    (is (zero? (:goal/active goals)))))

(deftest bb-stats-shell-task
  (seed-ledgers!)
  (let [{:keys [out err exit]} (p/shell {:out :string
                                         :err :string
                                         :continue true
                                         :extra-env {"OPENCRABS_HOME" *home*}}
                                        "bb" "stats")]
    (is (zero? exit))
    (is (str/includes? out "Theseus stats"))
    (is (str/includes? out "2 pending, 1 dead-lettered"))
    (is (str/includes? out "fulfilled 1, stalled 1"))
    (is (empty? err))))
