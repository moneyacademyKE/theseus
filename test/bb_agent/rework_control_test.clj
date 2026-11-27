(ns bb-agent.rework-control-test
  (:require [bb-agent.rework-control :as rc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest rework-feedback-and-exhausted-escalation-test
  (testing "carries concrete failure into :feedback on retry within rework budget"
    (let [decision (rc/decide-failure {:node-id "test-gate"
                                       :reject-to "implement"
                                       :attempt 1
                                       :max-retries 2
                                       :rework-count 0
                                       :max-reworks 2
                                       :error "AssertionError: expected 200 got 503"})]
      (is (= :retry (:action decision)))
      (is (str/includes? (:feedback decision) "AssertionError"))))

  (testing "parks on :waiting-approval instead of hard-failing when MaxReworks is exhausted"
    (let [decision (rc/decide-failure {:node-id "review"
                                       :reject-to "implement"
                                       :attempt 2
                                       :max-retries 1
                                       :rework-count 2
                                       :max-reworks 2
                                       :error "needs upstream rework"})]
      (is (= :waiting-approval (:action decision)))
      (is (str/includes? (:reason decision) "MaxReworks=2")))))

(deftest generation-attempt-write-fence-and-stale-card-guard-test
  (testing "rejects stale generation and attempt-mismatched failures, allows live success"
    (let [node {:status :running :attempt 2 :rework-count 1}]
      (is (true? (rc/should-commit-outcome? {:generation 5} node 5 2 false)))
      (is (false? (rc/should-commit-outcome? {:generation 6} node 5 2 false)) "stale generation")
      (is (false? (rc/should-commit-outcome? {:generation 5} node 5 1 false)) "stale failed attempt")
      (is (true? (rc/should-commit-outcome? {:generation 5} node 5 1 true)) "late passing result still lands")
      (is (false? (rc/should-commit-outcome? {:generation 5} {:status :pending :attempt 2} 5 2 true)) "reset sibling blocked")))

  (testing "stale inline card action rejected when node status/attempt/rework changed"
    (let [run {:nodes {"n1" {:status :waiting-approval :attempt 2 :rework-count 1}}}]
      (is (nil? (rc/check-node-expectation run {:node-id "n1" :status :waiting-approval :attempt 2 :rework-count 1})))
      (is (some? (rc/check-node-expectation run {:node-id "n1" :status :waiting-approval :attempt 1 :rework-count 1}))))))
