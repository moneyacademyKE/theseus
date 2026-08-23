(ns e2e.cron-edge-test
  "Cron matcher edge cases: the hermes-beam expression matrix plus the
  zone-aware cases its Gleam test never covered — DST spring-forward
  (a wall-clock slot that never exists), fall-back (a slot that occurs
  twice), missed-run catch-up windows, and poll-drift anchoring."
  (:require [bb-agent.cron :as cron]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time ZonedDateTime ZoneId)))

(defn- dt
  ([y m d h mi] (dt y m d h mi "UTC"))
  ([y m d h mi zone]
   (ZonedDateTime/of y m d h mi 0 0 (ZoneId/of zone))))

(def sunday (dt 2026 6 14 9 30))     ; 2026-06-14 is a Sunday
(def wednesday (dt 2026 6 17 12 0))  ; 2026-06-17 is a Wednesday

(deftest expression-matrix-ported-from-hermes-beam
  (testing "wildcards"
    (is (cron/match-cron "* * * * *" sunday)))
  (testing "exact minute and hour"
    (is (cron/match-cron "30 9 * * *" sunday))
    (is (not (cron/match-cron "0 9 * * *" sunday)))
    (is (not (cron/match-cron "30 10 * * *" sunday))))
  (testing "comma lists"
    (is (cron/match-cron "15,30,45 * * * *" sunday))
    (is (not (cron/match-cron "15,45 * * * *" sunday))))
  (testing "ranges"
    (is (cron/match-cron "25-35 * * * *" sunday))
    (is (not (cron/match-cron "0-10 * * * *" sunday))))
  (testing "steps"
    (is (cron/match-cron "*/15 * * * *" sunday))
    (is (not (cron/match-cron "*/20 * * * *" sunday)))
    (is (cron/match-cron "*/10 9 * * *" sunday)))
  (testing "day of week: Sunday is 0 or 7"
    (is (cron/match-cron "* * * * 0" sunday))
    (is (cron/match-cron "* * * * 7" sunday))
    (is (cron/match-cron "* * * * 0,7" sunday))
    (is (not (cron/match-cron "* * * * 1-5" sunday)) "Mon-Fri excludes Sunday")
    (is (cron/match-cron "* * * * 3" wednesday))
    (is (cron/match-cron "* * * * 1-5" wednesday))
    (is (not (cron/match-cron "* * * * 0,6,7" wednesday))))
  (testing "malformed expressions never match"
    (is (not (cron/match-cron "not a cron" sunday)))
    (is (not (cron/match-cron "* * * *" sunday)))))

(deftest dst-spring-forward-skips-nonexistent-slot
  (testing "2:30 AM does not exist on 2026-03-08 in New York (jump 2:00 -> 3:00)"
    (is (nil? (cron/next-match "30 2 * * *" (dt 2026 3 8 1 0 "America/New_York") (* 24 60)))
        "scan bounded to that day: the slot never occurs")
    (let [nm (cron/next-match "30 2 * * *" (dt 2026 3 8 1 0 "America/New_York") (* 48 60))]
      (is (= 9 (.getDayOfMonth nm)))
      (is (= 2 (.getHour nm)))
      (is (= 30 (.getMinute nm)))
      "with headroom, the next day's 2:30 is found")))

(deftest dst-spring-forward-day-has-one-fewer-hour-of-matches
  (let [ms (cron/matches-in-window "*/15 * * * *"
                                   (dt 2026 3 8 0 0 "America/New_York")
                                   (dt 2026 3 9 0 0 "America/New_York"))]
    (is (= 92 (count ms)) "23 wall hours x 4, not 96")))

(deftest dst-fall-back-slot-occurs-twice
  (let [ms (cron/matches-in-window "30 1 * * *"
                                   (dt 2026 11 1 0 0 "America/New_York")
                                   (dt 2026 11 2 0 0 "America/New_York"))]
    (is (= 2 (count ms)) "1:30 happens twice: EDT and EST")
    (is (= 25 (count (cron/matches-in-window "0 * * * *"
                                             (dt 2026 11 1 0 0 "America/New_York")
                                             (dt 2026 11 2 0 0 "America/New_York"))))
       "fall-back day has 25 top-of-hour slots")))

(deftest missed-run-catch-up-window
  (testing "a scheduler replaying a 5-hour outage of */30 collects exactly the missed slots"
    (let [ms (cron/matches-in-window "*/30 * * * *" (dt 2026 6 14 4 0) (dt 2026 6 14 9 0))]
      (is (= 10 (count ms)))
      (is (= [4 4 5 5 6 6 7 7 8 8] (mapv #(.getHour %) ms)))
      (is (= [0 30 0 30 0 30 0 30 0 30] (mapv #(.getMinute %) ms))))))

(deftest late-polling-does-not-drift
  (testing "polling at :07 with */15 anchors to the wall clock, not the poll time"
    (let [nm (cron/next-match "*/15 * * * *" (dt 2026 6 14 9 7))]
      (is (= 15 (.getMinute nm)))
      (is (= 9 (.getHour nm))))))
