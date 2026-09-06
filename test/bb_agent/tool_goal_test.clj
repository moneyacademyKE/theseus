(ns bb-agent.tool-goal-test
  "launch_goal: the seam that lets ordinary prompts reach the supervised
   goal runner. Covers the LLM-facing definition, dispatch with the turn's
   chat context threaded to the bridge, blank-spec handling, and one full
   turn through the real run-turn! loop with a stubbed provider."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.provider :as provider]
            [bb-agent.tool :as tool]
            [bb-agent.tools :as tools]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-temp-home [f]
  (let [home (str (fs/create-temp-dir {:prefix "tool-goal-"}))]
    (try
      (with-redefs [config/home (fn [] home)]
        (f))
      (finally
        (fs/delete-tree home)))))

(deftest launch-goal-is-advertised-to-the-llm
  (let [names (set (map #(get-in % [:function :name]) tools/definitions))]
    (is (contains? names "launch_goal")
        "the provider must see the tool or it can never choose it")))

(deftest dispatch-threads-turn-context-to-the-bridge
  (with-temp-home
   (fn []
     (let [calls (atom [])]
       (with-redefs [goal-bridge/launch-spec!
                     (fn [spec chat-id thread-id emit]
                       (swap! calls conj [spec chat-id thread-id emit])
                       (str "🚀 Goal `" spec "` launched"))]
         (let [result (tool/handle-tool-request
                       {:tool/name "launch_goal"
                        :tool/args {"spec" "a file named x.txt containing hi"}
                        :approval/policy :auto-all}
                       {:telegram/send-context {:chat-id -1001 :thread-id 4721}})]
           (is (= :ok (:status result)))
           (is (true? (:executed? result)))
           (is (str/includes? (:outcome result) "launched"))
           (is (= [["a file named x.txt containing hi" -1001 4721 nil]] @calls)
               "the turn's chat/thread ids must ride to the bridge so the
                watcher reports back to the requesting topic; no enclosing
                turn flow here, so the emitter is nil")))))))

(deftest blank-spec-is-an-error-not-a-crash
  (with-temp-home
   (fn []
     (let [result (tool/handle-tool-request
                   {:tool/name "launch_goal"
                    :tool/args {"spec" "   "}
                    :approval/policy :auto-all}
                   {})]
       (is (= :error (:status result)))
       (is (str/includes? (str (:error/message result)) "spec"))))))

(deftest full-turn-runs-the-tool-through-the-real-loop
  (with-temp-home
   (fn []
     (let [calls (atom [])
           rounds (atom 0)]
       (with-redefs [provider/complete
                     (fn [_provider _request]
                       ;; one tool round, then a final answer — a provider
                       ;; that repeats the identical call gets stopped by
                       ;; the loop guard, which is correct behavior
                       (if (= 1 (swap! rounds inc))
                         {:content nil
                          :tool/requests [{:tool/name "launch_goal"
                                           :approval/policy :auto-all
                                           :tool/args {"spec" "build the thing"}}]}
                         {:content "Goal launched — the outcome lands in this topic."}))
                     goal-bridge/launch-spec!
                     (fn [spec _chat-id _thread-id _emit]
                       (swap! calls conj spec)
                       (str "🚀 Goal `" spec "` launched"))]
         (let [turn (core/run-turn! {:provider :fake :model "m"
                                     :react-ack false
                                     :telegram/send-context {:chat-id -1001 :thread-id 4721}}
                                    "build the thing")]
           (is (str/includes? (:assistant/final turn) "launched")
               "the model must be able to relay the outcome to the chat")
           (is (= ["build the thing"] @calls)
               "the real dispatch must reach the bridge")))))))
