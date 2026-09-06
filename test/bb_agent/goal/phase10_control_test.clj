(ns bb-agent.goal.phase10-control-test
  "Operator control command tests."
  (:require [clojure.test :refer [deftest is testing]]
            [bb-agent.goal.control :as control]
            [bb-agent.goal.core :as core]
            [bb-agent.goal.status :as status]
            [babashka.fs :as fs]
            [clojure.java.shell]
            [clojure.string]))

(deftest control-files-model-pause-resume-stop
  (let [dir (str (fs/create-temp-dir))
        cfg {:name "control-demo" :workdir dir}]
    (try
      (is (= :running (:state (control/state cfg))))
      (is (= :paused (:state (control/apply-command! cfg :pause))))
      (is (= :paused (:state (control/state cfg))))
      (is (= :running (:state (control/apply-command! cfg :resume))))
      (is (= :running (:state (control/state cfg))))
      (is (= :stop-requested (:state (control/apply-command! cfg :stop))))
      (is (= :stop-requested (:state (control/state cfg))))
      (finally (fs/delete-tree dir)))))

(deftest run-halts-safely-on-operator-controls
  (testing "pause request becomes a resumable halt"
    (let [dir (str (fs/create-temp-dir))]
      (try
        (spit (str dir "/n") "0\n")
        (let [cfg {:name "paused-run"
                   :workdir dir
                   :lock (str dir "/.bb-agent.goal.lock")
                   :log-dir (str dir "/logs")
                   :observers {:n {:sh "cat n" :parse :int}}
                   :goal {:op :>= :ref :n :value 1}
                   :progress :n
                   :integrity []
                   :act {:sh "echo 1 > n"}}]
          (control/request! cfg :pause)
          (let [res (core/run! cfg {:max-iters 2})]
            (is (= :halt (:status res)))
            (is (= :operator-paused (:reason res)))))
        (finally (fs/delete-tree dir)))))
  (testing "stop request becomes a non-retryable operator halt"
    (let [dir (str (fs/create-temp-dir))]
      (try
        (spit (str dir "/n") "0\n")
        (let [cfg {:name "stopped-run"
                   :workdir dir
                   :lock (str dir "/.bb-agent.goal.lock")
                   :log-dir (str dir "/logs")
                   :observers {:n {:sh "cat n" :parse :int}}
                   :goal {:op :>= :ref :n :value 1}
                   :progress :n
                   :integrity []
                   :act {:sh "echo 1 > n"}}]
          (control/request! cfg :stop)
          (let [res (core/run! cfg {:max-iters 2})]
            (is (= :halt (:status res)))
            (is (= :operator-stop (:reason res)))))
        (finally (fs/delete-tree dir))))))

(deftest status-includes-control-state
  (let [dir (str (fs/create-temp-dir))
        cfg {:name "control-status" :workdir dir}]
    (try
      (control/request! cfg :pause)
      (let [facts (status/operator-facts cfg nil [])
            summary (status/format-summary cfg nil [])]
        (is (= :paused (:control-state facts)))
        (is (clojure.string/includes? summary "Control: paused")))
      (finally (fs/delete-tree dir)))))

(deftest cli-operator-control-commands
  (let [dir (str (fs/create-temp-dir))
        cfg-path (str dir "/config.edn")]
    (try
      (spit cfg-path (pr-str {:name "control-cli" :workdir dir}))
      (let [pause  (clojure.java.shell/sh "bb" "goal" "pause" cfg-path)
            resume (clojure.java.shell/sh "bb" "goal" "resume" cfg-path)
            stop   (clojure.java.shell/sh "bb" "goal" "stop" cfg-path)]
        (is (zero? (:exit pause)))
        (is (clojure.string/includes? (:out pause) "pause -> paused"))
        (is (zero? (:exit resume)))
        (is (clojure.string/includes? (:out resume) "resume -> running"))
        (is (zero? (:exit stop)))
        (is (clojure.string/includes? (:out stop) "stop -> stop-requested"))
        (is (= :stop-requested (:state (control/state {:workdir dir})))))
      (finally (fs/delete-tree dir)))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'bb-agent.goal.phase10-control-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
