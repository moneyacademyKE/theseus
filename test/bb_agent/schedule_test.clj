(ns bb-agent.schedule-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.outbox :as outbox]
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

;; ---------- command-lane delivery shape (2026-10-01, owner: "unacceptable format") ----------
;;
;; A raw babashka crash box posted into a Telegram topic is a failure
;; reaching the reader, not a report reaching him. Contract:
;; 1. failures deliver ONE terse line naming the cause, never stderr dumps
;; 2. self-delivering lanes (:schedule/deliver-on-success false) post nothing
;;    on success — their content went out under their own name
;; 3. the run ledger always keeps the FULL output either way

(def crash-box
  "----- Error --------------------------------------------------------------------
Type:     clojure.lang.ExceptionInfo
Message:  no quote for AMD
Location: brief.clj:321:19")

(deftest failure-notice-is-one-terse-line
  (testing "extracts the Message: line from a babashka crash box"
    (let [notice (schedule/failure-notice "ai-stock-brief-hourly" 1 "" crash-box)]
      (is (re-find #"ai-stock-brief-hourly" notice) "names the schedule")
      (is (re-find #"exit 1" notice) "names the exit code")
      (is (re-find #"no quote for AMD" notice) "extracts the cause")
      (is (not (re-find #"-----" notice)) "no crash-box furniture")
      (is (not (re-find #"Location:" notice)) "no file/line frames")
      (is (< (count notice) 300) "one line, not a dump")))
  (testing "falls back to trimmed stdout, then a placeholder"
    (is (re-find #"boom happened" (schedule/failure-notice "s" 2 "boom happened" "")))
    (is (re-find #"no output" (schedule/failure-notice "s" 2 "" "")))))

(deftest command-lane-delivery-shape
  (testing "deliver-on-success false: a lane that delivers its own content
            (brief posts its document itself) must not ALSO dump operational
            stdout into the topic on success"
    (write-schedules! [{:schedule/id "self-delivering"
                        :schedule/command "echo 'brief posted: AMD'"
                        :schedule/deliver-to {:chat-id 1 :thread-id 2}
                        :schedule/deliver-on-success false}])
    (let [enqueued (atom [])]
      (with-redefs [outbox/enqueue! (fn [_ record] (swap! enqueued conj record))]
        (let [{:keys [status]} (schedule/run-schedule! {} "self-delivering")]
          (is (= :ok status))
          (is (empty? @enqueued)
              "success + deliver-on-success false = nothing posted by the lane")))))

  (testing "the same lane still posts the terse notice on failure"
    (write-schedules! [{:schedule/id "self-delivering"
                        :schedule/command "false"
                        :schedule/deliver-to {:chat-id 1 :thread-id 2}
                        :schedule/deliver-on-success false}])
    (let [enqueued (atom [])]
      (with-redefs [outbox/enqueue! (fn [_ record] (swap! enqueued conj record))]
        (let [{:keys [status]} (schedule/run-schedule! {} "self-delivering")]
          (is (= :failed status))
          (is (= 1 (count @enqueued)))
          (is (re-find #"failed \(exit 1\)" (get-in (first @enqueued) [:params :text]))
              "the notice carries the exit code")))))

  (testing "default lanes still deliver success output (back-compat)"
    (write-schedules! [{:schedule/id "plain"
                        :schedule/command "echo daily report"
                        :schedule/deliver-to {:chat-id 1 :thread-id 2}}])
    (let [enqueued (atom [])]
      (with-redefs [outbox/enqueue! (fn [_ record] (swap! enqueued conj record))]
        (schedule/run-schedule! {} "plain")
        (is (= 1 (count @enqueued)))
        (is (re-find #"daily report" (get-in (first @enqueued) [:params :text])))))))

(deftest command-lane-ledger-keeps-full-output
  (testing "the run ledger records the FULL stderr crash even though the
            topic only ever sees the terse notice"
    (write-schedules! [{:schedule/id "noisy-failure"
                        :schedule/command "false"
                        :schedule/deliver-to {:chat-id 1 :thread-id 2}}])
    (let [enqueued (atom [])]
      (with-redefs [outbox/enqueue! (fn [_ record] (swap! enqueued conj record))]
        (schedule/run-schedule! {} "noisy-failure")
        (is (= :failed (:status (last (edn/read-string
                                       (slurp (str *tmp* "/state/schedule-runs.edn")))))))
        (is (< (count (get-in (first @enqueued) [:params :text])) 300)
            "topic gets the short line; the crash box lives in the ledger")))))
