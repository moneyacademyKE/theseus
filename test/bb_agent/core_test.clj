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
