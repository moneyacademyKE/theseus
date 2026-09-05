(ns axiom.phase6-operator-test
  "Phase 6 -- operator UX tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [axiom.status :as status]))

(def iterations
  [{:event :act :iteration 0 :progressed? true :exit 0
    :attempt 0 :prompt-index 0 :model-index 0 :stall 0 :thrash 1}
   {:event :escalate :iteration 1 :rung :reframe
    :prompt-index 1 :model-index 0 :stall 0 :thrash 0}])

(deftest operator-facts-extracts-status-without-parsing-text
  (let [facts (status/operator-facts {:name "ux-demo"
                                      :config-path "configs/ux.edn"
                                      :hot-reload true}
                                     nil
                                     iterations)]
    (is (= :running (:state facts)))
    (is (= 1 (:iteration facts)))
    (is (= :escalate (:last-event facts)))
    (is (= :reframe (:rung facts)))
    (is (= 1 (:prompt-index facts)))
    (is (= 0 (:model-index facts)))
    (is (:hot-reload? facts))))

(deftest operator-facts-classifies-halted-runs
  (let [facts (status/operator-facts {:name "ux-halt"}
                                     {:reason :stall
                                      :halt {:reason :stall :iterations 3}}
                                     iterations)]
    (is (= :halted (:state facts)))
    (is (= :no-progress (:failure-class facts)))
    (is (= :warning (:severity facts)))
    (is (:retryable? facts))
    (is (= 3 (:halt-iterations facts)))))

(deftest format-summary-renders-operator-fields
  (let [summary (status/format-summary {:name "ux-demo"
                                        :config-path "configs/ux.edn"
                                        :hot-reload true}
                                       {:reason :stall
                                        :halt {:reason :stall
                                               :iterations 3
                                               :world {:n 1}}}
                                       iterations)]
    (doseq [snippet ["Config path: configs/ux.edn"
                    "Failure class: no-progress"
                    "Hot reload: on"
                    "Last event: escalate"
                    "Current rung: reframe"
                    "Prompt/model index: 1/0"]]
      (is (str/includes? summary snippet) snippet))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase6-operator-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
