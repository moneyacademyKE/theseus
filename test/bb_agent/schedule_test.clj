(ns bb-agent.schedule-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.schedule :as schedule]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn- tmp-home [f]
  (let [dir (str (fs/create-temp-dir {:prefix "sched-test"}))]
    (fs/create-dirs (str dir "/state"))
    (with-redefs [config/home (fn [& _] dir)]
      (binding [*tmp* dir]
        (f)))))

(use-fixtures :each tmp-home)

(defn- write-schedules! [entries]
  (spit (str *tmp* "/state/schedules.edn") (pr-str (vec entries))))

(deftest run-schedule-carries-constant-approver
  (testing "scheduled turns are non-interactive: :approval/ask must auto-approve
            so shell tools don't stall waiting for a human who isn't there"
    (write-schedules! [{:schedule/id "test-sched" :schedule/prompt "do the thing"}])
    (let [seen (atom nil)]
      (with-redefs [core/run-turn! (fn [cfg _prompt]
                                     (reset! seen cfg)
                                     {:assistant/final "done"})]
        (schedule/run-schedule! {} "test-sched")
        (is (some? (:approval/ask @seen))
            "run-schedule! must inject an approval handler")
        (is (= :approved ((:approval/ask @seen) {:tool/name "shell" :args {}}))
            "the handler auto-approves — the constitution still vetoes upstream")
        (is (= "test-sched" (:session/id @seen))
            "the schedule id is the session id")))))

(deftest run-schedule-honors-schedule-overrides
  (testing ":provider/:model/:cwd from the schedule win over ambient cfg"
    (write-schedules! [{:schedule/id "test-override"
                        :schedule/prompt "p"
                        :cwd "/tmp/override-cwd"
                        :provider :openai-compatible
                        :model "sched-model"}])
    (let [seen (atom nil)]
      (with-redefs [core/run-turn! (fn [cfg _] (reset! seen cfg) {:assistant/final "ok"})]
        (schedule/run-schedule! {:model "ambient-model"} "test-override")
        (is (= "sched-model" (:model @seen))
            "schedule model beats ambient cfg")
        (is (= "/tmp/override-cwd" (:cwd @seen))
            "schedule cwd is selected into the turn cfg")))))

(deftest run-schedule-unknown-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (schedule/run-schedule! {} "no-such-schedule"))))
