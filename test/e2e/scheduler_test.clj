(ns e2e.scheduler-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(deftest schedule-commands-and-daemon-run-one-shot-workflow
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-scheduler-e2e-"})
        schedules-file (fs/path home "state" "schedules.edn")
        log-file (fs/path home "state" "schedule-runs.edn")
        session-file (fs/path home "state" "sessions" "daily.edn")]
    (try
      (let [add (shell! home "schedule" "add" "daily" "say pong")
            list-before (shell! home "schedule" "list")
            run-once (shell! home "schedule" "run" "daily")
             daemon (shell! home "daemon" "start" "--max-runs" "2" "--interval-ms" "1")
            remove (shell! home "schedule" "remove" "daily")
            list-after (shell! home "schedule" "list")]
        (is (= 0 (:exit add)) (:err add))
        (is (= 0 (:exit list-before)) (:err list-before))
        (is (= 0 (:exit run-once)) (:err run-once))
        (is (= 0 (:exit daemon)) (:err daemon))
        (is (= 0 (:exit remove)) (:err remove))
        (is (= 0 (:exit list-after)) (:err list-after))
        (is (fs/regular-file? schedules-file))
        (is (fs/regular-file? log-file))
        (is (fs/regular-file? session-file))
        (is (str/includes? (:out list-before) "daily"))
        (is (str/includes? (:out run-once) "pong"))
        (is (str/includes? (:out daemon) "daemon-cycles=2 daemon-ran=2"))
        (is (not (str/includes? (:out list-after) "daily")))
        (let [runs (edn/read-string (slurp (str log-file)))
              sessions (edn/read-string (slurp (str session-file)))]
          (is (= 3 (count runs)))
          (is (= ["daily" "daily" "daily"] (mapv :schedule/id runs)))
          (is (= [:ok :ok :ok] (mapv :status runs)))
          (is (= ["pong" "pong" "pong"] (mapv :assistant/final sessions)))))
      (finally
        (fs/delete-tree home)))))
