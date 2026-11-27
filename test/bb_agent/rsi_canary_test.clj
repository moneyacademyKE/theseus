(ns bb-agent.rsi-canary-test
  (:require [bb-agent.rsi-canary :as canary]
            [clojure.test :refer [deftest is testing]]))

(deftest deterministic-canary-bucketing-and-pinning-test
  (testing "bucket is deterministic in [0, 100) for same (run-id, proposal-id)"
    (let [b1 (canary/canary-bucket "run-abc" "prop-1")
          b2 (canary/canary-bucket "run-abc" "prop-1")]
      (is (= b1 b2))
      (is (<= 0 b1 99))))

  (testing "assign-canaries respects percent=100 vs percent=0 and inherits parent arm"
    (let [props [{:id "p-on" :asset "00-soul.md" :percent 100 :candidate "new-body"}
                 {:id "p-off" :asset "10-hickey.md" :percent 0 :candidate "never"}]]
      (is (= ["p-on"] (canary/assign-canaries "run-1" props {})))
      (is (= ["p-inherited"] (canary/assign-canaries "run-child" props {:parent-run-id "run-1"
                                                                        :canary-ids ["p-inherited"]}))
          "child subagents inherit parent canary arm")
      (is (empty? (canary/assign-canaries "run-replay" props {:replay? true}))
          "replays never enter live canary trials")))

  (testing "validate-replay fails closed when credentials or external repos are present"
    (is (some? (:error (canary/validate-replay {:repositories [{:url "https://github.com/a/b"}]}))))
    (is (some? (:error (canary/validate-replay {:nodes [{:id "n1" :credential-env ["API_KEY"]}]}))))
    (is (true? (:ok? (canary/validate-replay {:nodes [{:id "n1" :type :agent}]}))))))
