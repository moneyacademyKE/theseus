(ns e2e.provider-wire-test
  (:require [bb-agent.error-classifier :as ec]
            [bb-agent.provider :as provider]
            [bb-agent.tools :as tools]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(deftest plain-messages-pass-through
  (is (= [{"role" "system" "content" "sys"}
          {"role" "user" "content" "hi"}]
         (provider/openai-wire-messages
          [{:role :system :content "sys"}
           {:role :user :content "hi"}]))))

(deftest tool-round-becomes-tool-calls
  (let [wire (provider/openai-wire-messages
              [{:role :user :content "read it"}
               {:role :assistant :tool/requests
                [{:tool/name "read_file" :tool/args {:path "a.md"}}]}
               {:role :tool :content "(anything)"
                :tool/results [{:tool/name "read_file" :status :ok :value "data"}]}])]
    (is (= 3 (count wire)))
    (is (= {"role" "assistant"
            "tool_calls"
            [{"id" "call-0" "type" "function"
              "function" {"name" "read_file"
                          "arguments" "{\"path\":\"a.md\"}"}}]}
           (nth wire 1)))
    (let [tool-msg (nth wire 2)]
      (is (= "tool" (get tool-msg "role")))
      (is (= "call-0" (get tool-msg "tool_call_id")))
      (is (string? (get tool-msg "content")))
      (is (nil? (get tool-msg "tool/results"))))))

(deftest multiple-rounds-get-distinct-ids
  (let [wire (provider/openai-wire-messages
              [{:role :user :content "go"}
               {:role :assistant :tool/requests
                [{:tool/name "shell" :tool/args {:cmd "ls"}}]}
               {:role :tool :content "(r1)"
                :tool/results [{:tool/name "shell" :status :ok}]}
               {:role :assistant :tool/requests
                [{:tool/name "read_file" :tool/args {:path "b"}}]}
               {:role :tool :content "(r2)"
                :tool/results [{:tool/name "read_file" :status :ok}]}])]
    (is (= "call-0" (get-in (nth wire 1) ["tool_calls" 0 "id"])))
    (is (= "call-1" (get-in (nth wire 3) ["tool_calls" 0 "id"])))
    (is (= "call-0" (get (nth wire 2) "tool_call_id")))
    (is (= "call-1" (get (nth wire 4) "tool_call_id")))))

(deftest multiple-requests-pair-by-position
  (let [wire (provider/openai-wire-messages
              [{:role :user :content "go"}
               {:role :assistant :tool/requests
                [{:tool/name "shell" :tool/args {:cmd "ls"}}
                 {:tool/name "shell" :tool/args {:cmd "pwd"}}]}
               {:role :tool :content "(rs)"
                :tool/results [{:tool/name "shell" :status :ok :value "a"}
                               {:tool/name "shell" :status :ok :value "b"}]}])]
    (is (= 2 (count (get (nth wire 1) "tool_calls"))))
    (is (= ["call-0" "call-1"] (mapv #(get % "tool_call_id") (drop 2 wire))))))

(deftest assistant-content-preserved-alongside-tool-calls
  (let [wire (provider/openai-wire-messages
              [{:role :assistant :content "thinking"
                :tool/requests [{:tool/name "shell" :tool/args {:cmd "ls"}}]}
               {:role :tool :content "(r)"
                :tool/results [{:tool/name "shell" :status :ok}]}])]
    (is (= "thinking" (get (nth wire 0) "content")))
    (is (= 1 (count (get (nth wire 0) "tool_calls"))))))

(deftest non-json-5xx-fails-with-retryable-status-not-json-parse
  (let [stop-server (server/run-server
                     (fn [_req]
                       {:status 502
                        :headers {"content-type" "text/html"}
                        :body "<html><body>502 Bad Gateway</body></html>"})
                     {:port 0 :ip "127.0.0.1"})
        port (:local-port (meta stop-server))]
    (try
      (doseq [prov [:openai-compatible :anthropic-compatible]]
        (testing (str "HTML 502 from " (name prov) " throws retryable status error")
          (let [err (try
                      (provider/complete
                       prov
                       {:model "m"
                        :messages [{:role :user :content "hi"}]
                        :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                          :api-key "k"}})
                      nil
                      (catch Exception e e))]
            (is (some? err))
            (is (= 502 (:status (ex-data err))))
            (is (re-find #"status 502" (ex-message err)))
            (is (= :infra (:kind (ec/classify (ex-message err)))))
            (is (true? (ec/retryable? (ec/classify (ex-message err))))))))
      (finally
        (stop-server)))))

(deftest anthropic-tools-shapes-active-definitions
  (let [converted (provider/anthropic-tools (tools/active-definitions nil))
        first-tool (first converted)]
    (is (= (count (tools/active-definitions nil)) (count converted)))
    (is (string? (:name first-tool)))
    (is (string? (:description first-tool)))
    (is (map? (:input_schema first-tool)))))

(deftest anthropic-wire-payload-extracts-system-and-pairs-tool-ids
  (let [payload (provider/anthropic-wire-payload
                 [{:role :system :content "sys-1"}
                  {:role :system :content "sys-2"}
                  {:role :user :content "read a and b"}
                  {:role :assistant :content "checking"
                   :tool/requests [{:tool/id "toolu_exact" :tool/name "read_file" :tool/args {:path "a"}}
                                   {:tool/name "read_file" :tool/args {:path "b"}}]}
                  {:role :tool :content "(results)"
                   :tool/results [{:tool/name "read_file" :status :ok :value "A"}
                                  {:tool/name "read_file" :status :ok :value "B"}]}])
        msgs (:messages payload)]
    (is (= "sys-1\n\nsys-2" (:system payload)))
    (is (not-any? #(= "system" (or (:role %) (get % "role"))) msgs))
    (is (= 3 (count msgs)))
    (is (= "user" (:role (nth msgs 0))))
    (is (= "assistant" (:role (nth msgs 1))))
    (is (= [{:type "text" :text "checking"}
            {:type "tool_use" :id "toolu_exact" :name "read_file" :input {:path "a"}}
            {:type "tool_use" :id "toolu-0" :name "read_file" :input {:path "b"}}]
           (:content (nth msgs 1))))
    (is (= "user" (:role (nth msgs 2))))
    (is (= "tool_result" (get-in msgs [2 :content 0 :type])))
    (is (= "toolu_exact" (get-in msgs [2 :content 0 :tool_use_id])))
    (is (= "tool_result" (get-in msgs [2 :content 1 :type])))
    (is (= "toolu-0" (get-in msgs [2 :content 1 :tool_use_id])))))
