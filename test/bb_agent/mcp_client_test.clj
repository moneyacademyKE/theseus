(ns bb-agent.mcp-client-test
  (:require [bb-agent.mcp-client :as mcp]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- json-response
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status status :body (json/generate-string body)
    :headers (merge {"content-type" "application/json"} headers)}))

(defn- rpc-result
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- fake-transport
  [responses calls]
  (fn [url request]
    (swap! calls conj {:url url :request request})
    (let [response (first @responses)]
      (swap! responses rest)
      (or response {:status 500 :body "unexpected call"}))))

(defn- successful-client
  [extra-responses]
  (let [responses (atom (concat
                         [(json-response 1 1)] ; replaced below; keeps shape obvious
                         extra-responses))
        calls (atom [])]
    ;; Initialize ids start at 1; request ids start at 2.
    (reset! responses
            (concat [(json-response 200
                                    (rpc-result 1 {:protocolVersion "2025-03-26"
                                                   :serverInfo {:name "neo" :version "1"}
                                                   :capabilities {:tools {}}})
                                    {"mcp-session-id" "session-secret"})
                     {:status 202 :body "" :headers {}}]
                    extra-responses))
    {:calls calls
     :client (mcp/connect {:url "http://127.0.0.1:9210/mcp"
                           :transport (fake-transport responses calls)})}))

(deftest connect-carries-session-and-negotiated-version
  (let [{:keys [client calls]} (successful-client [])]
    (is (:ok? client))
    (is (:initialized? client))
    (is (= "session-secret" (:session-id client)))
    (is (= "2025-03-26" (:protocol-version client)))
    (is (= {:name "neo" :version "1"} (:server-info client)))
    (let [messages (mapv #(json/parse-string (get-in % [:request :body]) true) @calls)]
      (is (= ["initialize" "notifications/initialized"] (mapv :method messages)))
      (is (= [1 nil] (mapv :id messages)))
      (is (= "theseus" (get-in (first messages) [:params :clientInfo :name])))
      (is (= "session-secret" (get-in (second @calls) [:request :headers "Mcp-Session-Id"])))
      (is (= "2025-03-26" (get-in (second @calls) [:request :headers "MCP-Protocol-Version"]))))))

(deftest list-and-call-use-increasing-ids-and-session
  (let [{:keys [client calls]}
        (successful-client
         [(json-response 200 (rpc-result 2 {:tools [{:name "tabs"} {:name "snapshot"}]}))
          (json-response 200 (rpc-result 3 {:content [{:type "text" :text "ok"}]}))])]
    (is (= 2 (count (:tools (:result (mcp/list-tools client))))))
    (is (= "ok" (get-in (mcp/call-tool client "tabs" {:action "list"})
                        [:result :content 0 :text])))
    (let [messages (mapv #(json/parse-string (get-in % [:request :body]) true) @calls)]
      (is (= ["initialize" "notifications/initialized" "tools/list" "tools/call"]
             (mapv :method messages)))
      (is (= [1 nil 2 3] (mapv :id messages)))
      (is (= {:name "tabs" :arguments {:action "list"}}
             (:params (last messages))))
      (is (every? #(= "session-secret"
                      (get-in % [:request :headers "Mcp-Session-Id"]))
                  (rest @calls))))))

(deftest accepts-streamable-http-sse-response
  (let [{:keys [client]}
        (successful-client
         [{:status 200
           :headers {"content-type" "text/event-stream"}
           :body (str "event: message\n"
                      "data: " (json/generate-string
                                (rpc-result 2 {:tools [{:name "tabs"}]})) "\n\n")}])]
    (is (= ["tabs"] (mapv :name (get-in (mcp/list-tools client) [:result :tools]))))))

(deftest rejects-http-jsonrpc-and-id-failures-without-raw-body
  (testing "HTTP failure redacts the response body"
    (let [responses (atom [{:status 503 :body "secret-upstream-body" :headers {}}])
          result (mcp/connect {:url "http://localhost/mcp"
                               :transport (fake-transport responses (atom []))})]
      (is (false? (:ok? result)))
      (is (= 503 (:http/status result)))
      (is (not (str/includes? (pr-str result) "secret-upstream-body")))))
  (testing "JSON-RPC error is surfaced"
    (let [responses (atom [(json-response 200 {:jsonrpc "2.0" :id 1
                                                :error {:code -32601 :message "no method"}})])
          result (mcp/connect {:url "http://localhost/mcp"
                               :transport (fake-transport responses (atom []))})]
      (is (false? (:ok? result)))
      (is (str/includes? (:error result) "-32601"))))
  (testing "mismatched response id fails closed"
    (let [{:keys [client]}
          (successful-client [(json-response 200 (rpc-result 99 {:tools []}))])]
      (is (false? (:ok? (mcp/list-tools client)))))))

(deftest malformed-and-oversized-responses-fail-closed
  (let [responses (atom [{:status 200 :body "not-json secret" :headers {}}])
        result (mcp/connect {:url "http://localhost/mcp"
                             :transport (fake-transport responses (atom []))})]
    (is (false? (:ok? result)))
    (is (not (str/includes? (pr-str result) "secret"))))
  (is (thrown? Exception
               (mcp/decode-messages (apply str (repeat 2000001 "x"))
                                    "application/json"))))

(deftest validates-input-and-notification-status
  (is (false? (:ok? (mcp/connect {:url "file:///tmp/mcp"}))))
  (is (false? (:ok? (mcp/call-tool {:initialized? true :next-id (atom 1)} "" {}))))
  (let [responses (atom [(json-response 200 (rpc-result 1 {}))
                         {:status 500 :body "private" :headers {}}])
        result (mcp/connect {:url "http://localhost/mcp"
                             :transport (fake-transport responses (atom []))})]
    (is (false? (:ok? result)))
    (is (= 500 (:http/status result)))))
