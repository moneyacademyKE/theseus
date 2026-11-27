(ns bb-agent.queued-fold-test
  (:require [bb-agent.queued-fold :as qf]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest queued-fold-injected-context-test
  (testing "prefixes undelivered-draft note when prior round produced a draft"
    (let [ctx (qf/injected-context true "also check the logs")]
      (is (str/includes? ctx "never delivered"))
      (is (str/includes? ctx "as above"))
      (is (str/ends-with? ctx "also check the logs"))))

  (testing "leaves queued message untouched when prior round had no draft"
    (is (= "also check the logs" (qf/injected-context false "also check the logs")))))
