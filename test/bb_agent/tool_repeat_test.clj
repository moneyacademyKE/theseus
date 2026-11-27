(ns bb-agent.tool-repeat-test
  (:require [bb-agent.tool-repeat :as tr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest consecutive-repeat-tracker-test
  (testing "nudges once at threshold (default 4) and resets on different call or reset!"
    (let [t (tr/create-tracker)]
      (is (= {:verdict :fresh :count 1} (tr/observe! t "shell" {:cmd "ls" :cwd "/a"})))
      (is (= {:verdict :repeating :count 2} (tr/observe! t "shell" {:cwd "/a" :cmd "ls"}))
          "map key order must not affect signature")
      (is (= {:verdict :repeating :count 3} (tr/observe! t "shell" {:cmd "ls" :cwd "/a"})))
      (let [v4 (tr/observe! t "shell" {:cmd "ls" :cwd "/a"})]
        (is (= :nudge-now (:verdict v4)))
        (is (= 4 (:count v4)))
        (is (str/includes? (:nudge v4) "`shell`"))
        (is (str/includes? (:nudge v4) "4 times in a row")))
      (is (= {:verdict :repeating :count 5} (tr/observe! t "shell" {:cmd "ls" :cwd "/a"}))
          "nudges only once per consecutive run")
      (tr/reset! t)
      (is (= {:verdict :fresh :count 1} (tr/observe! t "shell" {:cmd "ls" :cwd "/a"}))
          "replay reset clears streak"))))

(deftest repetitive-tool-error-and-history-pruner-test
  (testing "detects provider repetitive tool guardrail errors"
    (is (true? (tr/repetitive-tool-error? "HTTP 500: Repetitive tool calls detected in conversation")))
    (is (false? (tr/repetitive-tool-error? "HTTP 500: Internal Server Error"))))

  (testing "collapses consecutive duplicate pure-tool rounds while keeping first and preserving prose rounds"
    (let [call-a {:role :assistant :tool/requests [{:tool/name "shell" :tool/args {:cmd "pwd"}}]}
          res-a  {:role :tool :content "ok" :tool/results [{:tool/name "shell" :status :ok}]}
          prose  {:role :assistant :content "checking" :tool/requests [{:tool/name "shell" :tool/args {:cmd "pwd"}}]}
          msgs   [{:role :user :content "hi"}
                  call-a res-a
                  call-a res-a
                  call-a res-a
                  prose res-a
                  call-a res-a]
          {:keys [messages removed]} (tr/prune-repetitive-tool-calls msgs)]
      (is (= 4 removed) "drops 2 duplicate tool_use + 2 paired tool_result messages")
      (is (= 7 (count messages)))
      (is (= call-a (nth messages 1)))
      (is (= prose (nth messages 3)) "prose-bearing tool round is never collapsed and resets run"))))
