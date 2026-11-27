(ns bb-agent.behavioral-wiring-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.telegram-intake :as intake]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest mid-sentence-eos-continuation-and-degenerate-retry-wired-test
  (let [home (fs/create-temp-dir {:prefix "theseus-eos-"})
        calls (atom 0)
        seen (atom [])]
    (try
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [_ req]
                      (swap! seen conj (:messages req))
                      (case (swap! calls inc)
                        1 {:role :assistant
                           :finish/reason "stop"
                           :content "I investigated the failing socket handler in detail, and it crashed because"}
                        2 {:role :assistant
                           :finish/reason "stop"
                           :content "`"}
                        {:role :assistant
                         :finish/reason "stop"
                         :content " the upstream peer closed the connection cleanly."}))]
        (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "eos-1"} "explain the crash")]
          (is (= 3 @calls) "initial cutoff + degenerate retry + stitched completion")
          (is (str/includes? (:assistant/final turn) "crashed because"))
          (is (str/includes? (:assistant/final turn) "closed the connection cleanly."))
          (is (some #(str/includes? (str (:content %)) "exact characters:") (second @seen)))
          (is (some #(str/includes? (str (:content %)) "discarded") (nth @seen 2)))))
      (finally
        (fs/delete-tree home)))))

(deftest loop-break-promotes-fallback-provider-wired-test
  (let [home (fs/create-temp-dir {:prefix "theseus-lb-fb-"})
        providers-seen (atom [])]
    (try
      (with-redefs [config/home (constantly (str home))
                    tool/handle-tool-request
                    (fn [_ _] {:tool/name "git_status" :status :ok :output "clean"})
                    provider/complete
                    (fn [p _]
                      (swap! providers-seen conj p)
                      (if (= p :fake)
                        {:role :assistant
                         :tool/requests [{:tool/name "git_status" :tool/args {}}]}
                        {:role :assistant :content "Rescued by fallback provider."}))]
        (let [turn (core/run-turn! {:provider :fake
                                    :model "m1"
                                    :session/id "lb-fb-1"
                                    :loop-guard-threshold 3
                                    :provider/fallbacks [{:provider :backup-llm :model "m2"}]
                                    :providers {:backup-llm {:default-model "m2"}}}
                                   "check repo")]
          (is (= "Rescued by fallback provider." (:assistant/final turn)))
          (is (some #{:backup-llm} @providers-seen))
          (is (= :backup-llm (:fallback/served-by turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest unrun-fenced-shell-guard-wired-test
  (let [home (fs/create-temp-dir {:prefix "theseus-fenced-"})
        calls (atom 0)
        seen (atom [])]
    (try
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [_ req]
                      (swap! seen conj (:messages req))
                      (if (= 1 (swap! calls inc))
                        {:role :assistant :content "Run this:\n```bash\nbb test\n```"}
                        {:role :assistant :content "Ran tests: all green."}))]
        (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "fs-1"}
                                   "run the test suite")]
          (is (= 2 @calls))
          (is (some #(str/includes? (str (:content %)) "nothing runs inside your reasoning")
                    (second @seen)))
          (is (= "Ran tests: all green." (:assistant/final turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest stop-intent-and-queued-fold-wired-test
  (testing "telegram-intake maps STOP!!! to :stop and triggers registered cancel atom"
    (let [cancel-a (atom false)]
      (intake/register-turn-cancel! "sess-stop-1" cancel-a)
      (try
        (is (= :stop (intake/chat-command "STOP!!!")))
        (is (= :stop (intake/chat-command "hold on, wait")))
        (is (nil? (intake/chat-command "stop the docker container")))
        (let [reply (intake/handle-chat-command :stop "sess-stop-1" "STOP!!!" nil nil)]
          (is (true? @cancel-a))
          (is (str/includes? reply "Stopping")))
        (finally
          (intake/unregister-turn-cancel! "sess-stop-1")))))

  (testing "core/run-turn! stops immediately when :turn/cancel? is true"
    (let [home (fs/create-temp-dir {:prefix "theseus-cancel-"})]
      (try
        (with-redefs [config/home (constantly (str home))]
          (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "c-1"
                                      :turn/cancel? (atom true)}
                                     "do work")]
            (is (true? (:turn/cancelled? turn)))
            (is (= "🛑 Stopped." (:assistant/final turn)))))
        (finally
          (fs/delete-tree home)))))

  (testing "core/run-turn! folds mid-turn queued follow-up with undelivered-draft note"
    (let [home (fs/create-temp-dir {:prefix "theseus-fold-"})
          queued (atom "also check the logs")
          calls (atom 0)
          seen (atom [])]
      (try
        (with-redefs [config/home (constantly (str home))
                      provider/complete
                      (fn [_ req]
                        (swap! seen conj (:messages req))
                        (if (= 1 (swap! calls inc))
                          {:role :assistant :content "First draft answer."}
                          {:role :assistant :content "Combined answer covering both requests."}))]
          (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "f-1"
                                      :turn/drain-queued #(let [v @queued] (reset! queued nil) v)}
                                     "check status")]
            (is (= 2 @calls))
            (is (some #(str/includes? (str (:content %)) "never delivered") (second @seen)))
            (is (= "Combined answer covering both requests." (:assistant/final turn)))))
        (finally
          (fs/delete-tree home))))))
