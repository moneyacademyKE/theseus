(ns e2e.tool-advertisement-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.tools :as tools]
            [bb-agent.core :as core]))

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
    (testing "outgoing request body declares the tool list"
      (is (= (count tools/definitions) (count (get @captured :tools))))
      (is (some #(= "read_file" (get-in % [:function :name])) (get @captured :tools)))
      (is (some #(= "shell" (get-in % [:function :name])) (get @captured :tools))))
    (testing "turn completed normally against the canned reply"
      (is (some? (:assistant/final result))))))
