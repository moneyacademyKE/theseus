(ns e2e.tool-advertisement-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.tools :as tools]
            [bb-agent.core :as core]
            [bb-agent.tool :as tool]))

(defn- fake-openai-reply [content]
  (json/generate-string {:choices [{:message {:role "assistant" :content content}}]
                         :usage {:prompt_tokens 1 :completion_tokens 1}}))

(deftest definitions-are-well-formed
  (let [defs tools/definitions
        names (set (map #(get-in % [:function :name]) defs))]
    (is (seq defs))
    (is (every? #(= "function" (:type %)) defs))
    (is (every? #(and (string? (get-in % [:function :description]))
                      (map? (get-in % [:function :parameters]))) defs))
    (is (contains? names "read_file"))
    (is (contains? names "write_file"))
    (is (contains? names "shell"))
    (is (contains? names "search"))))

(deftest openai-body-advertises-tools
  (let [captured (atom nil)
        reply {:status 200
               :body (fake-openai-reply "ok")}
        home (str (fs/create-temp-dir))
        cfg {:provider :openai-compatible
             :model "test-model"
             :session/id "adv-test"
             :providers {:openai-compatible
                         {:base-url "http://127.0.0.1:1/v1"
                          :api-key "test-key"}}}
        result (with-redefs [bb-agent.config/home (constantly home)
                             http/post (fn [_url opts]
                                         (reset! captured (json/parse-string (:body opts) keyword))
                                         reply)]
                 (try
                   (core/run-turn! cfg "hello")
                   (finally (fs/delete-tree home))))]
    (testing "outgoing request body declares the active tool list"
      (is (= (count (tools/active-definitions cfg)) (count (get @captured :tools))))
      (is (some #(= "read_file" (get-in % [:function :name])) (get @captured :tools)))
      (is (some #(= "shell" (get-in % [:function :name])) (get @captured :tools))))
    (testing "optional capabilities are dormant without opt-in (owner 2026-11-12)"
      (let [names (set (map #(get-in % [:function :name]) (get @captured :tools)))]
        (is (not-any? names tools/optional-tools))))
    (testing "turn completed normally against the canned reply"
      (is (some? (:assistant/final result))))))

(deftest optional-tools-opt-in
  (let [captured (atom nil)
        reply {:status 200
               :body (fake-openai-reply "ok")}
        home (str (fs/create-temp-dir))
        cfg {:provider :openai-compatible
             :model "test-model"
             :session/id "adv-optin-test"
             :tools/enable-optional ["web_search"]
             :providers {:openai-compatible
                         {:base-url "http://127.0.0.1:1/v1"
                          :api-key "test-key"}}}
        _ (with-redefs [bb-agent.config/home (constantly home)
                        http/post (fn [_url opts]
                                    (reset! captured (json/parse-string (:body opts) keyword))
                                    reply)]
            (try
              (core/run-turn! cfg "hello")
              (finally (fs/delete-tree home))))
        names (set (map #(get-in % [:function :name]) (get @captured :tools)))]
    (is (names "web_search") "opt-in advertises the tool")
    (is (not (names "browser_read")) "unlisted optional tools stay dormant")
    (is (not (names "a2a_task")) "unlisted optional tools stay dormant")))

(deftest optional-tools-selection-semantics
  (testing "default is the pre-capability registry: every optional tool removed"
    (let [names (->> (tools/active-definitions nil)
                     (map #(get-in % [:function :name]))
                     set)]
      (is (= (- (count tools/definitions) (count tools/optional-tools))
             (count names)))
      (is (not-any? names tools/optional-tools))
      (is (names "read_file") "core tools untouched")))
  (testing "keywords and strings both enable; unknown names enable nothing"
    (let [names (->> (tools/active-definitions {:tools/enable-optional [:web_search "browser_read"]})
                     (map #(get-in % [:function :name]))
                     set)]
      (is (names "web_search"))
      (is (names "browser_read"))
      (is (not (names "a2a_task"))))
    (let [names (->> (tools/active-definitions {:tools/enable-optional ["nonsense" "read_file"]})
                     (map #(get-in % [:function :name]))
                     set)]
      (is (not (names "web_search")) "unknown names cannot smuggle a tool in")
      (is (names "read_file") "core tools are always on; enabling one is a no-op")))
  (testing "dormant tools are refused at dispatch, not executed"
    (let [r (tool/handle-tool-request {:tool/name "a2a_task"
                                       :tool/args {:url "http://127.0.0.1:18790" :text "x"}}
                                      {})]
      (is (= :denied (:status r)))
      (is (false? (:executed? r)))
      (is (= :dormant (:denial/source r)))
      (is (re-find #":tools/enable-optional" (str (:error/message r)))
          "the denial tells the model the config recourse"))))
