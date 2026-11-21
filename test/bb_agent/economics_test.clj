(ns bb-agent.economics-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.saver-mode :as saver]
            [bb-agent.tool :as tool]
            [bb-agent.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest anthropic-wire-adds-ephemeral-cache-breakpoints
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [_url opts]
                              (reset! captured (json/parse-string (:body opts) true))
                              {:status 200
                               :body (json/generate-string
                                      {:role "assistant"
                                       :content [{:type "text" :text "ok"}]})})]
      (provider/complete :anthropic-compatible
                         {:model "claude-sonnet-4"
                          :messages [{:role :system :content "Always-on brain identity"}
                                     {:role :user :content "turn 1"}
                                     {:role :assistant :content "reply 1"}
                                     {:role :user :content "turn 2"}]
                          :provider/config {:base-url "http://127.0.0.1:1/v1"
                                            :api-key "k"}})
      (let [sys (:system @captured)
            msgs (:messages @captured)]
        (is (vector? sys) "system is structured content block vector for cache_control")
        (is (= {:type "ephemeral"} (:cache_control (first sys))))
        (is (= {:type "ephemeral"} (get-in (nth msgs 1) [:content 0 :cache_control]))
            "second-to-last message gets ephemeral cache breakpoint")
        (is (= {:type "ephemeral"} (get-in (nth msgs 2) [:content 0 :cache_control]))
            "last message gets ephemeral cache breakpoint")
        (is (string? (:content (first msgs)))
            "older messages stay plain strings")))))

(deftest deferred-tool-discovery-unlocks-optional-tools-mid-turn
  (testing "defer-optional hides optional tools initially and advertises discover_tools"
    (let [cfg {:tools/defer-optional true :tools/enable-optional ["web_search" "browser_read"]}
          names (set (map #(get-in % [:function :name]) (tools/active-definitions cfg)))]
      (is (contains? names "discover_tools"))
      (is (not (contains? names "web_search")))
      (is (not (contains? names "browser_read")))))
  (testing "discover_tools handler finds matching deferred schemas"
    (let [res (tool/handle-tool-request
               {:tool/name "discover_tools"
                :approval/policy :auto-safe
                :tool/args {:query "web search"}}
               {:tools/defer-optional true :tools/enable-optional ["web_search" "browser_read"]})]
      (is (= :ok (:status res)))
      (is (some #(= "web_search" %) (:discovered res)))))
  (testing "run-turn! advertises newly discovered tool on the next round"
    (let [home (fs/create-temp-dir {:prefix "theseus-discover-"})
          seen-tools (atom [])
          rounds (atom 0)]
      (try
        (with-redefs [config/home (constantly (str home))
                      provider/complete
                      (fn [_prov req]
                        (swap! seen-tools conj (set (map #(get-in % [:function :name]) (:provider/tools req))))
                        (if (= 1 (swap! rounds inc))
                          {:content nil
                           :tool/requests [{:tool/name "discover_tools"
                                            :approval/policy :auto-safe
                                            :tool/args {:query "browser"}}]}
                          {:content "found it"}))]
          (let [turn (core/run-turn! {:provider :fake
                                      :model "m"
                                      :session/id "disc-1"
                                      :tools/defer-optional true
                                      :tools/enable-optional ["web_search" "browser_read"]}
                                     "find browser tool")]
            (is (= "found it" (:assistant/final turn)))
            (is (not (contains? (first @seen-tools) "browser_read")))
            (is (contains? (second @seen-tools) "browser_read"))))
        (finally
          (fs/delete-tree home))))))

(deftest token-saver-mode-auto-engages-and-scales-limits
  (testing "auto engages at >= 75% of daily token budget"
    (let [low (saver/evaluate {:saver/mode :auto :saver/daily-token-budget 10000} 5000)
          high (saver/evaluate {:saver/mode :auto :saver/daily-token-budget 10000} 8000)]
      (is (false? (:active? low)))
      (is (true? (:active? high)))))
  (testing "apply-cfg shrinks rounds, history budget, and max-tokens"
    (let [cfg {:provider :anthropic-compatible
               :max-tool-rounds 10
               :history-budget-chars 20000
               :providers {:anthropic-compatible {:max-tokens 4000}}}
          scaled (saver/apply-cfg cfg {:active? true})]
      (is (true? (:saver/active? scaled)))
      (is (= 6 (:max-tool-rounds scaled)))
      (is (= 10000 (:history-budget-chars scaled)))
      (is (= 2400 (get-in scaled [:providers :anthropic-compatible :max-tokens])))))
  (testing "run-turn! injects saver system hint when :saver/mode is :on"
    (let [home (fs/create-temp-dir {:prefix "theseus-saver-"})
          captured-msgs (atom nil)]
      (try
        (with-redefs [config/home (constantly (str home))
                      provider/complete (fn [_ req]
                                          (reset! captured-msgs (:messages req))
                                          {:content "terse ok"})]
          (core/run-turn! {:provider :fake :model "m" :session/id "sv-1" :saver/mode :on} "hi")
          (is (some #(str/includes? (str (:content %)) "Token Saver Mode") @captured-msgs)))
        (finally
          (fs/delete-tree home))))))
