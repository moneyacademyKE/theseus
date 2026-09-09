(ns e2e.state-hygiene-test
  "Pins the state/ directory contract: only allowlisted durable entries
   at top level. Fixture tests are hermetic; the live gate runs against
   OPENCRABS_HOME when it points at a home with a state/ dir."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.state-hygiene :as hygiene]
            [clojure.test :refer [deftest is]]))

(deftest clean-state-dir-has-no-violations
  (let [home (fs/create-temp-dir {:prefix "state-hygiene-clean-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (doseq [e ["sessions" "outbox" "memory.edn" "usage.edn"
                 "telegram-offset.edn" "telegram-poll.log"]]
        (spit (str (fs/path home "state" e)) ""))
      (is (= [] (hygiene/violations (fs/path home "state"))))
      (finally (fs/delete-tree home)))))

(deftest scratch-entries-are-flagged
  (let [home (fs/create-temp-dir {:prefix "state-hygiene-dirty-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (doseq [e ["sessions" "probe.clj" "debug.log" "x.bak-verify" "ocr.swift"]]
        (spit (str (fs/path home "state" e)) ""))
      (is (= ["debug.log" "ocr.swift" "probe.clj" "x.bak-verify"]
             (hygiene/violations (fs/path home "state"))))
      (finally (fs/delete-tree home)))))

(deftest missing-state-dir-is-not-a-violation
  (is (= [] (hygiene/violations (fs/path (fs/create-temp-dir {:prefix "state-hygiene-absent-"}) "state")))))

(deftest live-state-dir-is-clean
  (let [state-dir (fs/path (config/home) "state")]
    (if (fs/directory? state-dir)
      (is (= [] (hygiene/violations state-dir))
          (str "scratch in " state-dir " — move to scratch/"))
      (is true "no state dir on this host"))))
