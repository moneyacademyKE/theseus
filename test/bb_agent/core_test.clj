(ns bb-agent.core-test
  "The nudge feature: checkpoint nudges at :nudge-interval, and a final
   converge-NOW extension window instead of an instant Exceeded-tool-rounds
   death. Driven through the real run-turn! loop with a stubbed provider —
   the loop mechanics are the product under test, so tool dispatch is redef'd
   to a constant success and each tool call carries unique args (identical
   repeated calls are the loop guard's job, not this suite's)."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-temp-home [f]
  (let [home (str (fs/create-temp-dir {:prefix "core-nudge-"}) )]
    (try
      (with-redefs [config/home (fn [] home)]
        (f))
      (finally
        (fs/delete-tree home)))))

(defn- drive-turn!
  "Run a turn whose model calls a unique tool for tool-rounds rounds, then
   answers 'done'. Returns [turn-or-throw captured-requests]."
  [{:keys [max-rounds nudge-interval nudge-final-rounds tool-rounds]}]
  (let [requests (atom [])
        rounds (atom 0)]
    (with-redefs [provider/complete
                  (fn [_provider request]
                    (swap! requests conj (:messages request))
                    (if (< (swap! rounds inc) (inc tool-rounds))
                      {:content nil
                       :tool/requests [{:tool/name "search"
                                        :approval/policy :auto-all
                                        :tool/args {"q" (str "round-" @rounds)}}]}
                      {:content "done"}))
                  tool/handle-tool-request
                  (fn [_req _cfg] {:status :ok :tool/result "ok"})]
      (let [cfg (cond-> {:provider :fake
                         :model "m"
                         :session/id (str "nudge-test-" (rand-int 1000000))
                         :max-tool-rounds max-rounds
                         :react-ack false}
                  nudge-interval (assoc :nudge-interval nudge-interval)
                  nudge-final-rounds (assoc :nudge-final-rounds nudge-final-rounds))]
        [(try (core/run-turn! cfg "test prompt")
              (catch clojure.lang.ExceptionInfo e e))
         @requests]))))

(defn- all-text [requests]
  (str/join "\n" (mapcat #(map :content %) requests)))

(deftest final-nudge-extends-instead-of-killing
  (with-temp-home
   (fn []
     (let [[turn requests] (drive-turn! {:max-rounds 3
                                         :nudge-final-rounds 2
                                         :tool-rounds 4})]
       (is (not (instance? clojure.lang.ExceptionInfo turn))
           (str "the extension must let the turn land, got: "
                (when (instance? clojure.lang.ExceptionInfo turn) (ex-message turn))))
       (is (= "done" (:assistant/final turn)))
       (is (str/includes? (all-text requests) "Final 2 rounds")
           "the model must SEE the converge-NOW nudge")
       (is (str/includes? (all-text requests) "No new exploration"))))))

(deftest checkpoint-nudges-fire-at-the-interval
  (with-temp-home
   (fn []
     (let [[turn requests] (drive-turn! {:max-rounds 8
                                         :nudge-interval 3
                                         :nudge-final-rounds 2
                                         :tool-rounds 7})
           text (all-text requests)]
       (is (= "done" (:assistant/final turn)))
       (is (str/includes? text "used 3 of 8") "first checkpoint at the interval")
       (is (str/includes? text "used 6 of 8") "second checkpoint at 2x the interval")
       (is (not (str/includes? text "Final")) "no final nudge before exhaustion")))))

(deftest exhaustion-still-throws-after-the-extension
  (with-temp-home
   (fn []
     (let [[turn _requests] (drive-turn! {:max-rounds 2
                                          :nudge-final-rounds 1
                                          :tool-rounds 99})]
       (is (instance? clojure.lang.ExceptionInfo turn)
           "a model that never converges must still be stopped")
       (is (str/includes? (ex-message turn) "Exceeded tool rounds"))
       (is (true? (:final-nudge-used? (ex-data turn)))
           "the throw must report that the extension was already spent")))))

(deftest no-nudges-when-unconfigured
  (with-temp-home
   (fn []
     (let [[turn requests] (drive-turn! {:max-rounds 5 :tool-rounds 4})]
       (is (= "done" (:assistant/final turn)))
       (is (not (str/includes? (all-text requests) "Checkpoint"))
           "interval nudges stay off unless :nudge-interval is set")))))

(defn- drive-repeat-turn!
  "Run a turn whose model emits the SAME tool call repeat-rounds times, then
   answers 'done'. Returns [turn handler-call-count captured-requests]."
  [{:keys [repeat-rounds max-rounds] :or {max-rounds 8}}]
  (let [requests (atom [])
        handler-calls (atom 0)
        rounds (atom 0)]
    (with-redefs [provider/complete
                  (fn [_provider request]
                    (swap! requests conj (:messages request))
                    (if (< (swap! rounds inc) (inc repeat-rounds))
                      {:content nil
                       :tool/requests [{:tool/name "read_file"
                                        :approval/policy :auto-all
                                        :tool/args {:path "same.txt"}}]}
                      {:content "done"}))
                  tool/handle-tool-request
                  (fn [_req _cfg]
                    (swap! handler-calls inc)
                    {:tool/name "read_file" :status :ok :tool/result "file-bytes"})]
      [(core/run-turn! {:provider :fake
                        :model "m"
                        :session/id (str "loop-nudge-test-" (rand-int 1000000))
                        :max-tool-rounds max-rounds
                        :react-ack false}
                       "read the same file forever")
       @handler-calls
       @requests])))

(deftest repetition-nudge-skips-second-execution
  (with-temp-home
   (fn []
     (let [[turn handler-calls requests] (drive-repeat-turn! {:repeat-rounds 2})]
       (is (= "done" (:assistant/final turn))
           "the model heeds the nudge and answers")
       (is (= 1 handler-calls)
           "the identical repeat is NOT executed — the warning rides back as its result")
       (is (some #(= :nudged (:loop-guard %)) (:tool/results turn))
           "the skipped call is marked :loop-guard :nudged in the turn record")
       (is (str/includes? (all-text requests) "Loop guard notice")
           "the model must SEE the warning")
       (is (str/includes? (all-text requests) "result will not change"))))))

(deftest repetition-ladder-ends-at-the-kill-line
  (with-temp-home
   (fn []
     (let [[turn handler-calls _requests] (drive-repeat-turn! {:repeat-rounds 5})]
       (is (false? (:turn/ok turn)) "a model that ignores the nudge still loses the turn")
       (is (str/includes? (:assistant/final turn) "Loop guard")
           "the kill message names the guard")
       (is (= 1 handler-calls)
           "ladder: execute once, nudge once, kill before the third")))))

(defn- drive-empty-turn!
  "Run a turn whose model produces empty rounds (no content, no tools)
   empty-rounds times, then answers 'done'. Returns [turn captured-requests]."
  [{:keys [empty-rounds]}]
  (let [requests (atom [])
        rounds (atom 0)]
    (with-redefs [provider/complete
                  (fn [_provider request]
                    (swap! requests conj (:messages request))
                    (if (< (swap! rounds inc) (inc empty-rounds))
                      {:content nil}
                      {:content "done"}))]
      [(core/run-turn! {:provider :fake
                        :model "m"
                        :session/id (str "reason-nudge-test-" (rand-int 1000000))
                        :react-ack false}
                       "think out loud")
       @requests])))

(deftest reasoning-without-answering-gets-nudged-not-killed
  (with-temp-home
   (fn []
     (let [[turn requests] (drive-empty-turn! {:empty-rounds 1})]
       (is (= "done" (:assistant/final turn))
           "one empty round is a nudge, not a death")
       (is (not (false? (:turn/ok turn)))
           "the turn is not marked failed")
       (is (str/includes? (all-text requests) "no answer and no tool calls")
           "the model must SEE the converge warning")
       (is (str/includes? (all-text requests) "Empty round 1 of 1"))))))

(deftest persistent-empty-rounds-end-the-turn
  (with-temp-home
   (fn []
     (let [[turn _requests] (drive-empty-turn! {:empty-rounds 5})]
       (is (false? (:turn/ok turn)) "a stuck reasoner still loses the turn")
       (is (str/includes? (:assistant/final turn) "reasoning without answering")
           "the death message names the real cause")))))
