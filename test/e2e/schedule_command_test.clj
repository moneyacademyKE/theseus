(ns e2e.schedule-command-test
  "Command-lane schedules: :schedule/command executes directly — no LLM,
  no session, no fabrication surface (rsi-daily, 2026-09-28: three weeks
  of plausible-looking finals, zero tool calls). Failure is the command's
  own exit, logged honestly. Delivery rides the durable outbox."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string :err :string :continue true
                  :extra-env {"THESEUS_HOME" (str home)}}
         "bb" args))

(defn- write-schedule! [home entry]
  (fs/create-dirs (fs/path home "state"))
  (let [path (fs/path home "state" "schedules.edn")]
    (spit (str path) (pr-str [entry]))
    path))

(deftest command-lane-executes-directly-and-logs-honestly
  (let [home (fs/create-temp-dir {:prefix "theseus-cmdlane-e2e-"})]
    (try
      (write-schedule! home {:schedule/id "cmd-ok"
                             :schedule/command "echo command-lane-works"
                             :cwd (System/getProperty "user.dir")
                             :schedule/cron "0 0 * * *"
                             :schedule/deliver-to {:chat-id -1003995594829 :thread-id 5}})
      (let [run (shell! home "schedule" "run" "cmd-ok")]
        (is (= 0 (:exit run)) (:err run))
        (is (str/includes? (:out run) "command-lane-works"))
        (let [runs (edn/read-string (slurp (str (fs/path home "state" "schedule-runs.edn"))))]
          (is (= 1 (count runs)))
          (is (= :ok (:status (first runs))))
          (is (= 0 (:command/exit (first runs))))
          (is (str/includes? (:assistant/final (first runs)) "command-lane-works"))))
      (is (not (fs/exists? (fs/path home "state" "sessions")))
          "the lane never touches an LLM — no session is ever created")
      (let [intents (fs/glob (fs/path home "state" "outbox") "*.edn")]
        (is (= 1 (count intents)) "one durable delivery intent")
        (let [rec (edn/read-string (slurp (str (first intents))))]
          (is (= "sendMessage" (:method rec)))
          (is (= -1003995594829 (get-in rec [:params :chat_id])))
          (is (= 5 (get-in rec [:params :message_thread_id])))
          (is (str/includes? (get-in rec [:params :text]) "command-lane-works"))))
      (finally
        (fs/delete-tree home)))))

(deftest command-lane-failure-is-the-commands-own
  (let [home (fs/create-temp-dir {:prefix "theseus-cmdlane-fail-"})]
    (try
      (write-schedule! home {:schedule/id "cmd-fail"
                             :schedule/command "false"
                             :cwd (System/getProperty "user.dir")})
      (let [run (shell! home "schedule" "run" "cmd-fail")]
        (is (= 0 (:exit run)) (:err run))
        (let [runs (edn/read-string (slurp (str (fs/path home "state" "schedule-runs.edn"))))]
          (is (= :failed (:status (first runs))))
          (is (= 1 (:command/exit (first runs))))
          ;; Terse notice contract (2026-10-01, owner: "unacceptable format"):
          ;; the topic gets one line; the raw crash box lives in the ledger.
          (is (str/includes? (:assistant/final (first runs)) "failed (exit 1)"))
          (is (not (str/includes? (:assistant/final (first runs)) "-----"))
              "no raw crash stack in the failure notice")))
      (finally
        (fs/delete-tree home)))))
