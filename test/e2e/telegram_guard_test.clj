(ns e2e.telegram-guard-test
  (:require [clojure.test :as t :refer [deftest is]]
            [bb-agent.telegram-guard :as guard]))

(deftest allowed-id-passes
  (let [cfg {:telegram {:allowed-chat-ids [111 222]}}]
    (is (true? (guard/allowed? cfg 111)))
    (is (true? (guard/allowed? cfg 222)))))

(deftest disallowed-id-denied
  (let [cfg {:telegram {:allowed-chat-ids [111 222]}}]
    (is (false? (guard/allowed? cfg 999)))
    (is (false? (guard/allowed? cfg nil)))))

(deftest missing-config-denies
  (is (false? (guard/allowed? {} 111)))
  (is (false? (guard/allowed? {:telegram {}} 111))))

(deftest empty-list-denies
  (is (false? (guard/allowed? {:telegram {:allowed-chat-ids []}} 111))))
