(ns bb-agent.stop-intent-test
  (:require [bb-agent.stop-intent :as si]
            [clojure.test :refer [deftest is testing]]))

(deftest stop-intent-single-vs-multi-word-test
  (testing "bare single-word stops, punctuation, slash commands, mentions, and address terms cancel"
    (is (true? (si/stop-intent? "stop")))
    (is (true? (si/stop-intent? "STOP!!!")))
    (is (true? (si/stop-intent? "  stop.  ")))
    (is (true? (si/stop-intent? "/stop")))
    (is (true? (si/stop-intent? "/stop@eileenslybot")))
    (is (true? (si/stop-intent? "stop eileen")))
    (is (true? (si/stop-intent? "cancel please"))))

  (testing "single-word stops inside normal instructions NEVER false-cancel"
    (is (false? (si/stop-intent? "stop the docker container")))
    (is (false? (si/stop-intent? "cancel the cron job for Monday")))
    (is (false? (si/stop-intent? "wait for the build to finish and report")))
    (is (false? (si/stop-intent? "eileen")))
    (is (false? (si/stop-intent? "")))
    (is (false? (si/stop-intent? nil))))

  (testing "multi-word interrupts match whole message AND leading clauses"
    (is (true? (si/stop-intent? "hold on")))
    (is (true? (si/stop-intent? "hold on, let me check something first")))
    (is (true? (si/stop-intent? "wait a sec — check the other branch")))
    (is (true? (si/stop-intent? "never mind please")))))
