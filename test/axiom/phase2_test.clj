(ns axiom.phase2-test
  "Runner-level Phase 2 regression tests. These exercise the side-effecting
  loop just enough to prove escalation decisions are not accidentally treated
  as ordinary acts."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.core :as core]
            [babashka.fs :as fs]))

(deftest run-climbs-escalation-ladder-before-no-convergence-halt
  (testing "progress-without-goal triggers a finite escalation rung, then halts"
    (let [dir (str (fs/create-temp-dir))
          log-dir (str dir "/logs")
          counter (str dir "/count")]
      (try
        (spit counter "0\n")
        (let [cfg {:name "phase2-runner-test"
                   :workdir dir
                   :lock (str dir "/.axiom.lock")
                   :log-dir log-dir
                   :observers {:n {:sh "cat count" :parse :int}}
                   :goal {:op :>= :ref :n :value 99}
                   :integrity []
                   :progress :n
                   :thrash-after 1
                   :escalations [:reseed]
                   :act {:sh "n=$(cat count); echo $((n + 1)) > count"}}
              result (core/run! cfg {:max-iters 10})]
          (is (= :halt (:status result)))
          (is (= :no-convergence (:reason result)))
          (is (= 3 (:iterations result)))
          (is (= 2 (Integer/parseInt (clojure.string/trim (slurp counter))))
              "the escalation rung does not perform an extra act"))
        (finally
          (fs/delete-tree dir))))))
