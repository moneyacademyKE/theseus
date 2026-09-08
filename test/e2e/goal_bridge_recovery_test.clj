(ns e2e.goal-bridge-recovery-test
  "Restart recovery + outcome persistence: a poller restart must never
   orphan a running goal (active.edn names chat/topic, dead runners are
   resumed or their verdict announced), and every watcher verdict must
   become a durable session turn via outcome.edn drain. Amnesia class,
   2026-09-08."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.goal-bridge :as gb]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def dead-pid 4000000000)

(defn- with-home
  [f]
  (let [home (str (fs/create-temp-dir {:prefix "theseus-gbr-"}))]
    (try
      (with-redefs [config/home (fn [] home)]
        (f home))
      (finally
        (fs/delete-tree home)))))

(defn- stale-active!
  "Seed goals/<name> with a run.log and a dead-pid active.edn entry."
  [home name run-log-content]
  (let [ws (str home "/goals/" name)]
    (fs/create-dirs ws)
    (when run-log-content
      (spit (str ws "/run.log") run-log-content))
    (spit (str home "/goals/active.edn")
          (pr-str {:pid dead-pid :name name :chat-id -1001 :thread-id 196}))))

(deftest drain-outcomes-appends-session-turn
  (with-home
    (fn [home]
      (fs/create-dirs (str home "/goals/build-x-1"))
      (spit (str home "/goals/build-x-1/outcome.edn")
            (pr-str {:name "build-x-1" :chat-id -1001 :thread-id 196
                     :text "🎯 goal `build-x-1`: ✅ GOAL FULFILLED"}))
      (is (= 1 (gb/drain-outcomes!)) "one outcome drained")
      (let [turns (edn/read-string
                   (slurp (str home "/state/sessions/telegram--1001-topic-196.edn")))]
        (is (= 1 (count turns)))
        (is (str/includes? (str (:user/input (first turns))) "build-x-1"))
        (is (str/includes? (str (:assistant/final (first turns))) "FULFILLED")))
      (is (not (fs/exists? (str home "/goals/build-x-1/outcome.edn")))
          "outcome file consumed — no double drain")
      (testing "non-topic outcomes land in the chat-level session"
        (fs/create-dirs (str home "/goals/build-x-2"))
        (spit (str home "/goals/build-x-2/outcome.edn")
              (pr-str {:name "build-x-2" :chat-id -1001 :thread-id nil :text "⛔ HALT: integrity"}))
        (is (= 1 (gb/drain-outcomes!)))
        (let [turns (edn/read-string
                     (slurp (str home "/state/sessions/telegram--1001.edn")))]
          (is (str/includes? (str (:assistant/final (first turns))) "HALT")))))))

(deftest drain-survives-poison-outcome
  "An unreadable outcome file must never kill polling — it stays behind
   for inspection while the rest drain."
  (with-home
    (fn [home]
      (fs/create-dirs (str home "/goals/bad"))
      (spit (str home "/goals/bad/outcome.edn") "{:not :readable :edn]]]")
      (is (zero? (gb/drain-outcomes!)) "poison outcome skipped, zero drained")
      (is (fs/exists? (str home "/goals/bad/outcome.edn")) "poison kept for inspection"))))

(deftest recover-announces-fulfilled-and-clears
  (with-home
    (fn [home]
      (stale-active! home "build-done" "GOAL FULFILLED {:world {:ok \"pass\"}}")
      (let [anns (gb/recover-interrupted!)]
        (is (= 1 (count anns)) "one announcement")
        (let [a (first anns)]
          (is (= -1001 (:chat-id a)))
          (is (= 196 (:thread-id a)))
          (is (str/includes? (:text a) "FULFILLED"))
          (is (str/includes? (:text a) "build-done")))
        (is (not (fs/exists? (str home "/goals/active.edn")))
            "stale registry cleared")
        (is (fs/exists? (str home "/goals/build-done/outcome.edn"))
            "verdict queued as an outcome for the session drain")))))

(deftest recover-resumes-crashed-run
  (with-home
    (fn [home]
      (stale-active! home "build-crashed" nil)
      (let [called (atom [])
            anns (with-redefs [gb/resume-detached!
                               (fn [name chat-id thread-id]
                                 (swap! called conj [name chat-id thread-id])
                                 "spawned")]
                   (gb/recover-interrupted!))]
        (is (= [["build-crashed" -1001 196]] @called)
            "crashed run handed to the detached resume path")
        (is (= 1 (count anns)))
        (is (str/includes? (:text (first anns)) "resuming"))
        (is (not (fs/exists? (str home "/goals/active.edn")))
            "registry cleared before resume (resume! re-registers on launch)")))))

(deftest recover-ignores-live-runner
  (with-home
    (fn [home]
      (let [live-pid (.pid (java.lang.ProcessHandle/current))
            ws (str home "/goals/live")]
        (fs/create-dirs ws)
        (spit (str home "/goals/active.edn")
              (pr-str {:pid live-pid :name "live" :chat-id -1001 :thread-id 196}))
        (is (empty? (gb/recover-interrupted!)) "live runner: nothing to do")
        (is (fs/exists? (str home "/goals/active.edn")) "registry untouched")
        (is (not (fs/exists? (str ws "/outcome.edn"))))))))

(deftest recover-handles-parallel-topics-independently
  "Topic-scoped registry (parallelism, 2026-09-08): each dead run is
   announced in ITS own topic and a live runner in another topic survives
   the recovery untouched."
  (with-home
    (fn [home]
      (doseq [[gname log] [["topic-196-goal" "GOAL FULFILLED {}"]
                           ["topic-200-goal" "act 1 ok"]]]
        (let [ws (str home "/goals/" gname)]
          (fs/create-dirs ws)
          (spit (str ws "/run.log") log)))
      (let [live-pid (.pid (java.lang.ProcessHandle/current))
            resumed (atom [])
            _ (spit (str home "/goals/active.edn")
                    (pr-str {[-1001 196] {:pid dead-pid :name "topic-196-goal"
                                          :chat-id -1001 :thread-id 196}
                             [-1001 200] {:pid dead-pid :name "topic-200-goal"
                                          :chat-id -1001 :thread-id 200}
                             [-1001 999] {:pid live-pid :name "still-running"
                                          :chat-id -1001 :thread-id 999}}))
            anns (with-redefs [gb/resume-detached!
                               (fn [gname chat-id thread-id]
                                 (swap! resumed conj [gname chat-id thread-id])
                                 "spawned")]
                   (gb/recover-interrupted!))]
        (is (= 2 (count anns)) "two dead runs announced; the live runner is ignored")
        (is (= #{196 200} (set (map :thread-id anns)))
            "each announcement routes to its own topic")
        (is (= [["topic-200-goal" -1001 200]] @resumed)
            "only the verdict-less dead run is resumed")
        (let [a196 (some #(when (= 196 (:thread-id %)) %) anns)
              a200 (some #(when (= 200 (:thread-id %)) %) anns)]
          (is (str/includes? (:text a196) "FULFILLED"))
          (is (str/includes? (:text a200) "resuming")))
        (is (= {[-1001 999] {:pid live-pid :name "still-running"
                             :chat-id -1001 :thread-id 999}}
               (edn/read-string (slurp (str home "/goals/active.edn"))))
            "live entry survives; handled entries are dropped")))))

(deftest recover-without-active-run-is-noop
  (with-home
    (fn [home]
      (is (empty? (gb/recover-interrupted!)) "no active.edn, no announcements"))))
