(ns bb-agent.loop-break-test
  (:require [bb-agent.loop-break :as lb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest describe-pending-calls-and-exhausted-message-test
  (testing "renders raw dropped call name + args and caps long payloads at 240 chars"
    (let [short-desc (lb/describe-pending-calls [{:tool/name "shell" :tool/args {:cmd "bb test"}}])
          long-arg (apply str (repeat 300 "x"))
          long-desc (lb/describe-pending-calls [{:tool/name "write_file" :tool/args {:content long-arg}}])]
      (is (str/includes? short-desc "shell"))
      (is (str/includes? short-desc "bb test"))
      (is (<= (count long-desc) 241))
      (is (str/ends-with? long-desc "…"))))

  (testing "chain-exhausted-message reports dropped call and provider count"
    (let [msg (lb/chain-exhausted-message "shell" 3 "shell {:cmd \"ls\"}" 2)]
      (is (str/includes? msg "`shell`"))
      (is (str/includes? msg "2 provider"))
      (is (str/includes? msg "dropped call:")))))
