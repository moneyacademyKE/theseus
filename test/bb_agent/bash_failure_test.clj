(ns bb-agent.bash-failure-test
  (:require [bb-agent.bash-failure :as bf]
            [bb-agent.rsi :as rsi]
            [clojure.test :refer [deftest is testing]]))

(deftest classify-shell-failures-test
  (testing "model errors take precedence over environmental phrases"
    (is (= :model-error (bf/classify "Command exited with code 2\nSTDERR:\nbash: syntax error near unexpected token `)'")))
    (is (= :model-error (bf/classify "STDERR:\nzsh: command not found: frobnicate")))
    (is (= :model-error (bf/classify "STDERR:\ngrep: invalid option -- 'Z'\nNo such file or directory"))
        "model error must win even when environmental text also appears"))

  (testing "environmental errors on stderr classify as :environmental"
    (is (= :environmental (bf/classify "STDERR:\ncat: /nope.txt: No such file or directory")))
    (is (= :environmental (bf/classify "STDERR:\ncurl: (7) Failed to connect: Connection refused")))
    (is (= :environmental (bf/classify "STDERR:\nPermission denied"))))

  (testing "stdout content cannot launder a real error into :environmental"
    (is (= :unknown (bf/classify "STDOUT:\nNo such file or directory\nSTDERR:\nboom failed"))))

  (testing "unknown failures stay :unknown (counted as defects, never dropped)"
    (is (= :unknown (bf/classify "STDERR:\nAssertionError: expected 1 got 2")))
    (is (= :unknown (bf/classify nil)))))

(deftest recoverable-tool-failure-and-rsi-exclusion-test
  (testing "recoverable policy separates environmental shell & stale-edit misses from defects"
    (is (true? (bf/recoverable-tool-failure? "shell" "STDERR:\nConnection refused")))
    (is (false? (bf/recoverable-tool-failure? "shell" "STDERR:\nzsh: command not found: foo")))
    (is (true? (bf/recoverable-tool-failure? "edit_file" "old_text not found in file")))
    (is (false? (bf/recoverable-tool-failure? "read_file" "unexpected crash"))))

  (testing "rsi/tools-aggregate excludes :tool/recoverable? errors from :fail denominator"
    (let [events [{:usage/event :tool :tool/name "shell" :tool/status :ok :ok true}
                  {:usage/event :tool :tool/name "shell" :tool/status :error :ok false :tool/recoverable? true}
                  {:usage/event :tool :tool/name "shell" :tool/status :error :ok false :tool/recoverable? true}
                  {:usage/event :tool :tool/name "shell" :tool/status :error :ok false :tool/recoverable? false}
                  {:usage/event :tool :tool/name "shell" :tool/status :ok :ok true}]
          agg (get (rsi/tools-aggregate events) "shell")]
      (is (= 5 (:calls agg)))
      (is (= 2 (:ok agg)))
      (is (= 1 (:fail agg)))
      (is (= 2 (:recoverable agg))))))
