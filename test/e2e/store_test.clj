(ns e2e.store-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.store :as store]
            [clojure.test :as t :refer [deftest is testing]]))

(deftest usage-index-and-query
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [config/home (fn [] home)]
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn"))
            (pr-str [{:created/at "t1" :session/id "s" :provider :openai
                      :model "gpt-5" :tokens/input 10 :tokens/output 20
                      :tokens/cache-read 0 :tokens/cache-write 0 :tokens/total 30
                      :cost/estimate-usd 0.2 :ok true}
                     {:created/at "t2" :session/id "s" :provider :anthropic
                      :model "opus-4-6" :tokens/input 1 :tokens/output 2
                      :tokens/cache-read 0 :tokens/cache-write 0 :tokens/total 3
                      :cost/estimate-usd 0.0 :ok false}]))
      (testing "index rebuilds from the EDN ledger"
        (is (= 2 (store/index-usage!))))
      (testing "queries return keyword-keyed rows"
        (is (= [{:n 2}] (store/query "select count(*) as n from usage_events"))))
      (testing "failures are queryable"
        (is (= [{:n 1}] (store/query "select count(*) as n from usage_events where ok = 0"))))
      (testing "the write path stays in EDN: SQL is SELECT-only"
        (is (thrown? Exception (store/query "delete from usage_events")))
        (is (thrown? Exception (store/query "insert into usage_events default values")))
        (is (= 2 (store/index-usage!)) "index unchanged after rejected writes")))))
