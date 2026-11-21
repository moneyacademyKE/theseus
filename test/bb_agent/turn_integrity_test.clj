(ns bb-agent.turn-integrity-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.crash-flag :as crash-flag]
            [bb-agent.provider :as provider]
            [bb-agent.tool :as tool]
            [bb-agent.turn-integrity :as ti]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest provider-emits-finish-reason-and-core-recovers-truncated-response
  (testing "openai-compatible and anthropic-compatible both emit :finish/reason"
    (with-redefs [http/post (fn [_ _]
                              {:status 200
                               :body (json/generate-string
                                      {:choices [{:finish_reason "length"
                                                  :message {:role "assistant" :content "partial..."}}]})})]
      (let [res (provider/complete :openai-compatible
                                   {:model "m"
                                    :messages [{:role :user :content "hi"}]
                                    :provider/config {:base-url "http://127.0.0.1:1/v1" :api-key "k"}})]
        (is (= :truncated (ti/classify-finish-reason (:finish/reason res)))))))
  (testing "core/run-turn! injects continuation prompt on :length truncation"
    (let [home (fs/create-temp-dir {:prefix "theseus-trunc-"})
          calls (atom 0)
          seen (atom [])]
      (try
        (with-redefs [config/home (constantly (str home))
                      provider/complete
                      (fn [_ req]
                        (swap! seen conj (:messages req))
                        (if (= 1 (swap! calls inc))
                          {:role :assistant :content "halfway..." :finish/reason "length"}
                          {:role :assistant :content "completed cleanly" :finish/reason "stop"}))]
          (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "tr-1"} "write report")]
            (is (= 2 @calls))
            (is (= "completed cleanly" (:assistant/final turn)))
            (is (some #(str/includes? (str (:content %)) "truncated by the token limit") (second @seen)))))
        (finally
          (fs/delete-tree home))))))

(deftest execute-mode-narration-guard-catches-fake-done-without-mutations
  (is (true? (ti/narration-without-mutation?
              "fix the bug in the handler file"
              {:tool/results []}
              "I've fixed the bug in the handler.")))
  (is (false? (ti/narration-without-mutation?
               "write a poem about rain"
               {:tool/results []}
               "I've written the poem.")))
  (let [home (fs/create-temp-dir {:prefix "theseus-exec-guard-"})
        calls (atom 0)]
    (try
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [_ _]
                      (case (swap! calls inc)
                        1 {:role :assistant :content "I've fixed the bug in the handler file."}
                        2 {:role :assistant
                           :tool/requests [{:tool/name "write_file"
                                            :approval/policy :auto-all
                                            :tool/args {:path (str (fs/path home "a.clj")) :content "(def x 1)"}}]}
                        {:role :assistant :content "Actually fixed now."}))]
        (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "eg-1"}
                                   "fix the bug in the handler file")]
          (is (= 3 @calls))
          (is (= "Actually fixed now." (:assistant/final turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest silent-turn-after-successful-tools-synthesizes-factual-receipt
  (let [home (fs/create-temp-dir {:prefix "theseus-silent-receipt-"})
        calls (atom 0)]
    (try
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [_ _]
                      (if (= 1 (swap! calls inc))
                        {:role :assistant
                         :tool/requests [{:tool/name "write_file"
                                          :approval/policy :auto-all
                                          :tool/args {:path (str (fs/path home "out.txt")) :content "hi"}}]}
                        {:role :assistant :content nil}))
                    tool/handle-tool-request
                    (fn [_ _] {:tool/name "write_file" :status :ok :bytes 2 :path "out.txt"})]
        (let [turn (core/run-turn! {:provider :fake :model "m" :session/id "sr-1"
                                    :reasoning-nudge-threshold 2}
                                   "write out.txt")]
          (is (not (false? (:turn/ok turn))))
          (is (true? (:turn/synthesized-receipt? turn)))
          (is (str/includes? (:assistant/final turn) "Completed actions:"))
          (is (str/includes? (:assistant/final turn) "Wrote"))))
      (finally
        (fs/delete-tree home)))))

(deftest two-stage-stall-watchdog-emits-soft-then-hard-events
  (let [events (atom [])
        hb (atom (System/currentTimeMillis))]
    (ti/with-stall-watchdog
      {:status/emit #(swap! events conj (:status %))
       :stall/soft-ms 40
       :stall/hard-ms 90}
      hb
      #(Thread/sleep 150))
    (is (= [:turn/still-working :turn/stalled] @events))))

(deftest crash-flag-roundtrips-and-clears-on-consume
  (let [home (fs/create-temp-dir {:prefix "theseus-crash-flag-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (is (nil? (crash-flag/consume-crash-flag!)))
        (crash-flag/write-crash-flag! {:reason "sigterm-in-cycle" :task "telegram-poll"})
        (let [consumed (crash-flag/consume-crash-flag!)]
          (is (= "sigterm-in-cycle" (get-in consumed [:record :reason])))
          (is (str/includes? (:notice consumed) "telegram-poll")))
        (is (nil? (crash-flag/read-crash-flag)) "consume clears the flag"))
      (finally
        (fs/delete-tree home)))))
