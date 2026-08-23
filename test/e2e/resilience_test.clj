(ns e2e.resilience-test
  (:require [bb-agent.circuit-breaker :as cb]
            [bb-agent.error-classifier :as ec]
            [clojure.test :refer [deftest is testing]]))

(deftest breaker-allows-while-closed
  (let [br (cb/breaker 3 60)]
    (is (= [true br] (cb/check br :gpt 1000)) "fresh breaker allows")
    (is (= [true br] (cb/check br :claude 1000)) "keys are independent")))

(deftest breaker-trips-open-after-threshold-failures
  (let [br (-> (cb/breaker 3 60)
               (cb/record-failure :gpt 1000)
               (cb/record-failure :gpt 1001))]
    (is (= [true br] (cb/check br :gpt 1002)) "two failures do not trip")
    (let [tripped (cb/record-failure br :gpt 1002)]
      (is (= [false tripped] (cb/check tripped :gpt 1003))
          "third failure trips open and blocks"))))

(deftest breaker-half-open-after-cooldown
  (let [br (-> (cb/breaker 3 60)
               (cb/record-failure :gpt 1000)
               (cb/record-failure :gpt 1001)
               (cb/record-failure :gpt 1002))]
    (testing "still blocked before cooldown elapses"
      (is (= [false br] (cb/check br :gpt 1061))))
    (testing "cooldown elapsed: one trial allowed, state moves to half-open"
      (let [[allowed? br'] (cb/check br :gpt 1062)]
        (is allowed?)
        (testing "trial success closes and resets the failure count"
          (let [closed (cb/record-success br' :gpt)]
            (is (= [true closed] (cb/check closed :gpt 1063)))
            (let [retripped (-> closed
                                (cb/record-failure :gpt 1064)
                                (cb/record-failure :gpt 1065))]
              (is (= [true retripped] (cb/check retripped :gpt 1066))
                  "failures were reset, so two failures do not re-trip"))))
        (testing "trial failure re-opens immediately"
          (let [reopened (cb/record-failure br' :gpt 1062)]
            (is (= [false reopened] (cb/check reopened :gpt 1063)))))))))

(deftest breaker-failures-are-per-key
  (let [br (-> (cb/breaker 2 60)
               (cb/record-failure :gpt 1000)
               (cb/record-failure :gpt 1001))]
    (is (= [false br] (cb/check br :gpt 1002))
        "gpt trips at its own threshold")
    (is (= [true br] (cb/check br :claude 1002))
        "claude untouched by gpt's failures")))

(deftest classifier-categories-and-precedence
  (testing "auth wins over everything"
    (is (= :auth (:kind (ec/classify "401 Unauthorized + 503 upstream chaos"))))
    (is (= :auth (:kind (ec/classify "invalid API key")))))
  (testing "rate limit beats infra and logic"
    (is (= :rate-limit (:kind (ec/classify "HTTP 429 Too Many Requests"))))
    (is (= :rate-limit (:kind (ec/classify "quota exceeded for project")))))
  (testing "infra"
    (is (= :infra (:kind (ec/classify "connect ECONNREFUSED 127.0.0.1:8080"))))
    (is (= :infra (:kind (ec/classify "504 Gateway Timeout")))))
  (testing "logic errors are our bug"
    (is (= :logic (:kind (ec/classify "400 Bad Request: invalid model")))))
  (testing "unrecognized input"
    (let [{:keys [kind reason]} (ec/classify "something novel happened")]
      (is (= :unknown kind))
      (is (= "something novel happened" reason)))))

(deftest retryable-policy-matches-classification
  (is (true? (ec/retryable? {:kind :infra})))
  (is (true? (ec/retryable? {:kind :rate-limit})))
  (is (false? (ec/retryable? {:kind :auth})))
  (is (false? (ec/retryable? {:kind :logic})))
  (is (false? (ec/retryable? {:kind :unknown}))))

(deftest breaker-and-classifier-compose
  (testing "the ported pattern as data: classify, then trip only on retryable failures"
    (let [br (cb/breaker 2 60)]
      (let [br' (-> br
                    (as-> b (if (ec/retryable? (ec/classify "503 unavailable"))
                              (cb/record-failure b :gpt 1000)
                              b))
                    (as-> b (if (ec/retryable? (ec/classify "401 key rejected"))
                              (cb/record-failure b :gpt 1001)
                              b)))]
        (is (= [true br'] (cb/check br' :gpt 1002))
            "auth failure did not count; breaker still closed")))))
