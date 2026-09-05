(ns axiom.phase6-operator-ux-test
  "Phase 6 -- operator UX regression tests.

  Phase 5 introduced stable failure taxonomy. Phase 6 starts making that
  useful for humans by exposing a pure operator-facts map from the status
  surface: easy to render in a CLI, GUI, webhook, or REPL without scraping
  summary prose."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.status :as status]))

(def halt-bundle
  {:reason :no-convergence
   :halt {:reason :no-convergence
          :iterations 4
          :world {:level-count 2}}
   :iterations [{:event :act
                 :iteration 2
                 :attempt 2
                 :prompt-index 1
                 :model-index 0
                 :stall 0
                 :thrash 2}
                {:event :escalate
                 :iteration 3
                 :attempt 3
                 :prompt-index 1
                 :model-index 1
                 :rung :escalate-model
                 :stall 0
                 :thrash 3}]})

(deftest operator-facts-classifies-halted-run
  (let [facts (status/operator-facts {:name "ux-demo"
                                      :config-path "configs/ux.edn"
                                      :hot-reload true}
                                     halt-bundle
                                     (:iterations halt-bundle))]
    (is (= {:config "ux-demo"
            :state :halted
            :iteration 3
            :last-event :escalate
            :prompt-index 1
            :model-index 1
            :attempt 3
            :rung :escalate-model
            :stall 0
            :thrash 3
            :config-path "configs/ux.edn"
            :hot-reload? true
            :control-state :running
            :halt-reason :no-convergence
            :halt-iterations 4
            :failure-class :repeated-identical-actions
            :severity :warning
            :retryable? false}
           facts))))

(deftest operator-facts-tolerates-running-and-empty-runs
  (testing "running run with recent iteration data"
    (is (= {:config "running-demo"
            :state :running
            :iteration 7
            :last-event :act
            :prompt-index nil
            :model-index nil
            :attempt 7
            :rung nil
            :stall 1
            :thrash nil
            :config-path nil
            :hot-reload? false
            :control-state :running}
           (status/operator-facts {:name "running-demo"}
                                  nil
                                  [{:event :act
                                    :iteration 7
                                    :attempt 7
                                    :stall 1}]))))
  (testing "empty/no-log run still renders as structured operator data"
    (is (= {:config "<unnamed>"
            :state :running
            :iteration 0
            :last-event nil
            :prompt-index nil
            :model-index nil
            :attempt nil
            :rung nil
            :stall nil
            :thrash nil
            :config-path nil
            :hot-reload? false
            :control-state :running}
           (status/operator-facts {} nil [])))))
