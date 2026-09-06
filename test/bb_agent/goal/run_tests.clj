(ns bb-agent.goal.run-tests
  "Phase 0 unit tests. Targets the PURE surfaces only."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [bb-agent.goal.config :as config]
            [bb-agent.goal.predicates :as predicates]
            [bb-agent.goal.decision :as decision]
            [bb-agent.goal.git-test]
            [bb-agent.goal.lock-test]
            [bb-agent.goal.phase1-test]
            [bb-agent.goal.phase2-test]
            [bb-agent.goal.phase2-rungs-test]
            [bb-agent.goal.phase2-guard-test]
            [bb-agent.goal.phase3-test]
            [bb-agent.goal.status-test]
            [bb-agent.goal.notify-test]
            [bb-agent.goal.observe-test]
            [bb-agent.goal.config-test]
            [bb-agent.goal.phase4-bundles-test]
            [bb-agent.goal.phase4-hotreload-test]
            [bb-agent.goal.phase5-dogfood-test]
            [bb-agent.goal.phase6-operator-test]
            [bb-agent.goal.phase6-operator-ux-test]
            [bb-agent.goal.phase7-harness-test]
            [bb-agent.goal.phase9-opencode-dogfood-test]
            [bb-agent.goal.phase10-control-test]
            [bb-agent.goal.phase11-budget-test]))

(deftest eval-pred-comparisons
  (testing "numeric comparisons against the world value"
    (is (predicates/eval-pred {:level-count 5} {:op :>= :ref :level-count :value 3}))
    (is (predicates/eval-pred {:level-count 3} {:op :>= :ref :level-count :value 3}))
    (is (not (predicates/eval-pred {:level-count 2} {:op :>= :ref :level-count :value 3})))
    (is (predicates/eval-pred {:level-count 2} {:op :<= :ref :level-count :value 3}))
    (is (predicates/eval-pred {:n 4} {:op :> :ref :n :value 3}))
    (is (predicates/eval-pred {:n 3} {:op :< :ref :n :value 4})))
  (testing "equality / inequality on strings"
    (is (predicates/eval-pred {:build-ok "pass"} {:op := :ref :build-ok :value "pass"}))
    (is (not (predicates/eval-pred {:build-ok "fail"} {:op := :ref :build-ok :value "pass"})))
    (is (predicates/eval-pred {:build-ok "fail"} {:op :not= :ref :build-ok :value "pass"})))
  (testing "missing refs are nil -> comparisons fail safe (false), not crashes"
    (is (not (predicates/eval-pred {} {:op :>= :ref :level-count :value 0})))
    (is (not (predicates/eval-pred {} {:op :<  :ref :level-count :value 9})))))

(deftest eval-pred-exists
  (is (predicates/eval-pred {:level-count 0} {:op :exists :ref :level-count}))
  (is (predicates/eval-pred {:flag false} {:op :exists :ref :flag}))
  (is (not (predicates/eval-pred {} {:op :exists :ref :level-count}))))

(deftest eval-pred-boolean-combinators
  (testing ":not negates"
    (is (predicates/eval-pred {:build-ok "fail"}
                          {:op :not :expr {:op := :ref :build-ok :value "pass"}}))
    (is (not (predicates/eval-pred {:build-ok "pass"}
                               {:op :not :expr {:op := :ref :build-ok :value "pass"}}))))
  (testing ":and is all-true"
    (is (predicates/eval-pred {:a 1 :b 2}
                          {:op :and :exprs [{:op :>= :ref :a :value 1}
                                            {:op :<= :ref :b :value 2}]}))
    (is (not (predicates/eval-pred {:a 1 :b 2}
                               {:op :and :exprs [{:op :>= :ref :a :value 1}
                                                 {:op :> :ref :b :value 5}]}))))
  (testing ":or is any-true"
    (is (predicates/eval-pred {:a 1}
                          {:op :or :exprs [{:op := :ref :a :value 9}
                                           {:op := :ref :a :value 1}]}))
    (is (not (predicates/eval-pred {:a 1}
                               {:op :or :exprs [{:op := :ref :a :value 9}
                                                {:op := :ref :a :value 8}]}))))
  (testing "nested composition"
    (is (predicates/eval-pred {:build-ok "fail" :level-count 3}
                          {:op :and :exprs [{:op :not :expr {:op := :ref :build-ok :value "pass"}}
                                            {:op :>= :ref :level-count :value 3}]}))))

(deftest eval-pred-bare-keyword
  (is (predicates/eval-pred {:ready? true} :ready?))
  (is (predicates/eval-pred {:items [1 2]} :items))
  (is (not (predicates/eval-pred {:ready? false} :ready?)))
  (is (not (predicates/eval-pred {} :ready?))))

(deftest eval-pred-rejects-unknown-op
  (is (thrown? Exception (predicates/eval-pred {:x 1} {:op :bogus :ref :x :value 1}))))

(deftest integrity-violations
  (testing "empty when every integrity check holds"
    (is (empty? (predicates/integrity-violations {:build-ok "pass" :disk 10}
                                              [{:op := :ref :build-ok :value "pass"}
                                               {:op :>= :ref :disk :value 5}]))))
  (testing "reports each violated check"
    (let [v (predicates/integrity-violations {:build-ok "fail" :disk 1}
                                          [{:op := :ref :build-ok :value "pass"}
                                           {:op :>= :ref :disk :value 5}])]
      (is (= 2 (count v)))
      (is (every? #(contains? % :check) v))))
  (testing "nil integrity spec -> no violations"
    (is (empty? (predicates/integrity-violations {:build-ok "fail"} nil)))))

(deftest expand-bundles-bundles
  (testing "an integrity bundle expands to its predicate set"
    (is (= [{:op := :ref :build-ok :value "pass"}]
           (config/expand-bundles {:integrity-bundle :gleam-build})))
    (is (= [{:op := :ref :build-ok :value "pass"}
            {:op := :ref :tests-ok  :value "pass"}]
           (config/expand-bundles {:integrity-bundle :clj-build}))))
  (testing "bundle checks are composed BEFORE explicit :integrity checks"
    (let [ax (config/expand-bundles {:integrity-bundle :rust-build
                                    :integrity [{:op :>= :ref :disk :value 1}]})]
      (is (= 3 (count ax)))
      (is (= :disk (-> ax last :ref)) "explicit checks are appended last")))
  (testing "configs with no bundle just pass :integrity through (possibly empty)"
    (is (= [] (config/expand-bundles {}))
        "no bundle + nil :integrity -> no integrity checks")
    (is (= [{:op := :ref :build-ok :value "pass"}]
           (config/expand-bundles {:integrity [{:op := :ref :build-ok :value "pass"}]}))))
  (testing "an unknown bundle name fails LOUD (never silently drop integrity checks)"
    (is (thrown? Exception (config/expand-bundles {:integrity-bundle :nonsense})))))

(deftest clj-build-bundle-halts-on-red-test-or-build
  (let [green  {:build-ok "pass" :tests-ok "pass"}
        red-t  {:build-ok "pass" :tests-ok "FAIL"}
        broken {:build-ok "FAIL" :tests-ok "pass"}]
    (is (empty? (predicates/integrity-violations green (config/expand-bundles {:integrity-bundle :clj-build})))
        "green build+tests satisfies every clj-build integrity check")
    (is (seq (predicates/integrity-violations red-t (config/expand-bundles {:integrity-bundle :clj-build})))
        "red tests violate the clj-build bundle -> halt, not retry")
    (is (seq (predicates/integrity-violations broken (config/expand-bundles {:integrity-bundle :clj-build})))
        "broken build violates the clj-build bundle -> halt")))

(deftest goal-met
  (is (predicates/goal-met? {:level-count 5} {:op :>= :ref :level-count :value 3}))
  (is (not (predicates/goal-met? {:level-count 1} {:op :>= :ref :level-count :value 3}))))

(deftest progress-signature
  (testing "keyword spec tracks one value"
    (is (= 3 (predicates/progress-sig {:level-count 3} :level-count))))
  (testing "coll spec hashes only the listed keys"
    (is (= (predicates/progress-sig {:a 1 :b 2} [:a :b])
           (predicates/progress-sig {:a 1 :b 2 :c 9} [:a :b])))
    (is (not= (predicates/progress-sig {:a 1 :b 2} [:a :b])
              (predicates/progress-sig {:a 1 :b 3} [:a :b]))))
  (testing "nil spec -> any change anywhere is progress"
    (is (= (predicates/progress-sig {:a 1} nil)
           (predicates/progress-sig {:a 1} nil)))
    (is (not= (predicates/progress-sig {:a 1} nil)
              (predicates/progress-sig {:a 2} nil)))))

(deftest load-config-refuses-host-repo-workdir
  (testing ":workdir resolving to the runner's own repo is rejected at load"
    (let [tmp (java.io.File/createTempFile "guard" ".edn")]
      (spit tmp (pr-str {:name "guard" :observers [] :goal :ready?
                         :progress :ready? :act {:sh "true"} :workdir (System/getProperty "user.dir")}))
      (is (thrown? Exception (config/load-config (.getPath tmp))))
      (.delete tmp)))
  (testing "a dedicated workdir loads fine"
    (let [tmp  (java.io.File/createTempFile "ws-ok" ".edn")
          ws   (.getParentFile tmp)]
      (spit tmp (pr-str {:name "ok" :observers [] :goal :ready?
                         :progress :ready? :act {:sh "true"} :workdir (.getPath ws)}))
      (is (config/load-config (.getPath tmp)))
      (.delete tmp))))

(deftest load-config-anchors-relative-paths
  (testing "relative :workdir/:lock/:log-dir resolve against the config's dir, not the cwd"
    (let [dir      (.getParentFile (java.io.File/createTempFile "anchor" ".edn"))
          cfg-file (java.io.File/createTempFile "anchor" ".edn" dir)]
      (spit cfg-file (pr-str {:name "anchor" :observers [] :goal :ready?
                              :progress :ready? :act {:sh "true"}
                              :workdir "work" :lock ".bb-agent.goal.lock" :log-dir "logs"}))
      (let [cfg  (config/load-config (.getPath cfg-file))
            base (.getPath dir)]
        (is (= (str base "/work") (:workdir cfg)))
        (is (= (str base "/.bb-agent.goal.lock") (:lock cfg)))
        (is (= (str base "/logs") (:log-dir cfg))))
      (.delete cfg-file))))

(def base-cfg
  {:goal        {:op :>= :ref :level-count :value 3}
   :integrity   [{:op := :ref :build-ok :value "pass"}]
   :stall-after 3})

(deftest decide-goal-met
  (let [a (decision/decide base-cfg {:level-count 3 :build-ok "pass"} {:stall 0})]
    (is (= :done (:type a)))))

(deftest decide-act-when-progressing
  (testing "under the stall budget and goal unmet -> :act"
    (let [a (decision/decide base-cfg {:level-count 0 :build-ok "pass"} {:stall 0})]
      (is (= :act (:type a)))))
  (testing "at the last allowed stall iteration it still acts (stall < budget)"
    (let [a (decision/decide base-cfg {:level-count 0 :build-ok "pass"} {:stall 2})]
      (is (= :act (:type a))))))

(deftest decide-stall-budget
  (testing "once stall reaches the budget, decide rolls back before halting"
    (let [a (decision/decide base-cfg {:level-count 0 :build-ok "pass"} {:stall 3})]
      (is (= :rollback (:type a)))
      (is (= 0 (:rollbacks a)))
      (is (= 2 (:max-rollbacks a)))))
  (testing "stall halts after the rollback budget is exhausted"
    (let [tight (assoc base-cfg :stall-after 1 :max-rollbacks 1)
          a     (decision/decide tight {:level-count 0 :build-ok "pass"} {:stall 1 :rollbacks 1})]
      (is (= :halt (:type a)))
      (is (= :stall (:reason a))))))

(deftest decide-integrity-halt
  (testing "a violated integrity check halts :integrity"
    (let [a (decision/decide base-cfg {:level-count 0 :build-ok "fail"} {:stall 0})]
      (is (= :halt (:type a)))
      (is (= :integrity (:reason a)))
      (is (seq (:violations a)))))
  (testing "integrity is checked BEFORE goal: a broken system halts even if the goal is met"
    (let [a (decision/decide base-cfg {:level-count 5 :build-ok "fail"} {:stall 0})]
      (is (= :integrity (:reason a)))
      (is (not= :done (:type a))))))

(deftest decide-determinism
  (let [w {:level-count 2 :build-ok "pass"}
        s {:stall 1 :thrash 0 :attempt 4}]
    (is (= (decision/decide base-cfg w s)
           (decision/decide base-cfg w s)))))

(deftest decide-no-convergence-ladder
  (testing "thrash budget exhausts -> deterministic Phase 2 escalation rungs"
    (let [cfg (assoc base-cfg :thrash-after 4)
          w   {:level-count 0 :build-ok "pass"}]
      (is (= :act (:type (decision/decide cfg w {:stall 0 :thrash 3 :attempt 3}))))
      (let [a (decision/decide cfg w {:stall 0 :thrash 4 :attempt 4})]
        (is (= :escalate (:type a)))
        (is (= :rollback (:rung a)))
        (is (= 0 (:escalation-index a)))
        (is (= 1 (:next-escalation-index a)))
        (is (= 4 (:iterations a))))
      (is (= :reseed (:rung (decision/decide cfg w {:stall 0 :thrash 4 :escalation-index 1}))))
      (is (= :reframe (:rung (decision/decide cfg w {:stall 0 :thrash 4 :escalation-index 2}))))
      (is (= :escalate-model (:rung (decision/decide cfg w {:stall 0 :thrash 4 :escalation-index 3}))))))
  (testing "ladder exhaustion halts with a complete no-convergence action"
    (let [cfg (assoc base-cfg :thrash-after 4)
          w   {:level-count 0 :build-ok "pass"}
          a   (decision/decide cfg w {:stall 0 :thrash 4 :escalation-index 4})]
      (is (= :halt (:type a)))
      (is (= :no-convergence (:reason a)))
      (is (= 4 (:iterations a)))
      (is (= 4 (:escalation-index a)))))
  (testing "thrash is ignored when :thrash-after is unset (Phase 0/1 back-compat)"
    (let [w {:level-count 0 :build-ok "pass"}]
      (is (= :act (:type (decision/decide base-cfg w {:stall 0 :thrash 9999 :attempt 9999})))))))

(let [summary (t/run-tests 'bb-agent.goal.run-tests 'bb-agent.goal.git-test 'bb-agent.goal.lock-test 'bb-agent.goal.phase1-test 'bb-agent.goal.phase2-test 'bb-agent.goal.phase2-rungs-test 'bb-agent.goal.phase2-guard-test 'bb-agent.goal.phase3-test 'bb-agent.goal.status-test 'bb-agent.goal.notify-test 'bb-agent.goal.observe-test 'bb-agent.goal.config-test 'bb-agent.goal.phase4-bundles-test 'bb-agent.goal.phase4-hotreload-test 'bb-agent.goal.phase5-dogfood-test 'bb-agent.goal.phase6-operator-test 'bb-agent.goal.phase6-operator-ux-test 'bb-agent.goal.phase7-harness-test 'bb-agent.goal.phase9-opencode-dogfood-test 'bb-agent.goal.phase10-control-test 'bb-agent.goal.phase11-budget-test)]
  (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0)))
