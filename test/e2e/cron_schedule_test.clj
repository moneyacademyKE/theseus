(ns e2e.cron-schedule-test
  "Cron executor gating: the caller that makes bb-agent.cron true.
  Schedules without :schedule/cron keep firing on every run-all tick
  (existing behavior); schedules with one fire only when the
  expression matched at some minute since their last run. Time is an
  explicit argument everywhere, so DST logic stays the matcher's and
  gating logic stays a function. last-run maps mirror the runs file:
  id -> ISO-8601 instant string."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.schedule :as schedule]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

(defn- utc [y mo d h mi]
  (java.time.ZonedDateTime/of y mo d h mi 0 0 (java.time.ZoneId/of "UTC")))

(deftest cron-due-never-run-fires-on-observed-match
  (let [expr "*/5 * * * *"]
    (is (true? (schedule/cron-due? expr nil (utc 2026 8 29 10 5)))
        "never-run + matching minute -> due")
    (is (false? (schedule/cron-due? expr nil (utc 2026 8 29 10 3)))
        "never-run + non-matching minute -> stays quiet")))

(deftest cron-due-windows-catch-up-without-double-firing
  (let [expr "*/5 * * * *"
        ran-at-10 (java.time.Instant/parse "2026-08-29T10:00:00Z")]
    (is (false? (schedule/cron-due? expr ran-at-10 (utc 2026 8 29 10 3)))
        "no match in (10:00, 10:03] -> not due")
    (is (true? (schedule/cron-due? expr ran-at-10 (utc 2026 8 29 10 6)))
        "match at 10:05 in (10:00, 10:06] -> due (catch-up)")
    (is (true? (schedule/cron-due? expr ran-at-10 (utc 2026 8 29 10 5)))
        "the 10:05 slot itself is due")
    (is (false? (schedule/cron-due? expr ran-at-10 (utc 2026 8 29 10 0)))
        "window starts after the last run -> not due")))

(deftest due-schedules-gates-cron-and-preserves-free
  (let [m5 {:schedule/id "m5" :schedule/prompt "p" :schedule/cron "*/5 * * * *"}
        free {:schedule/id "always" :schedule/prompt "p"}
        last-runs {"m5" "2026-08-29T10:00:00Z"}]
    (is (= ["always"]
           (mapv :schedule/id (schedule/due-schedules [m5 free] last-runs (utc 2026 8 29 10 3))))
        "cron schedule quiet, free schedule unchanged")
    (is (= ["m5" "always"]
           (mapv :schedule/id (schedule/due-schedules [m5 free] last-runs (utc 2026 8 29 10 5))))
        "cron schedule catches up, order preserved")))

(deftest run-all-fires-on-cron-with-catch-up
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-cron-gate-"})
        runs-file (fs/path home "state" "schedule-runs.edn")]
    (with-redefs [config/home (constantly (str home))]
      (try
        (let [now (-> (java.time.ZonedDateTime/now (java.time.ZoneId/of "UTC"))
                      (.truncatedTo java.time.temporal.ChronoUnit/HOURS))]
          (schedule/add-schedule! "gated" "say pong" "*/5 * * * *")
          (schedule/add-schedule! "free" "say pong")
          (let [ran (schedule/run-all-schedules! {} now)]
            (is (= ["gated" "free"] (mapv :session/id ran))
                "matching minute: both fire")
            (is (every? #(= "pong" (:assistant/final %)) ran)
                "run-all returns completed turns"))
          (is (= ["free"]
                 (mapv :session/id (schedule/run-all-schedules! {} (.plusMinutes now 1))))
              "one minute later: gated holds, free still fires")
          (schedule/run-all-schedules! {} (.plusMinutes now 60))
          (let [runs (edn/read-string (slurp (str runs-file)))]
            (is (= ["gated" "free" "free" "gated" "free"]
                   (mapv :schedule/id runs))
                "next hour: gated catches up all its missed slots in one run")
            (is (every? string? (mapv :at runs)) "every run stamped with :at")))
        (finally
          (fs/delete-tree home))))))
