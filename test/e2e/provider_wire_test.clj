(ns e2e.provider-wire-test
  (:require [bb-agent.provider :as provider]
            [clojure.test :refer [deftest is testing]]))

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
