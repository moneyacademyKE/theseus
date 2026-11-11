(ns e2e.web-a2a-test
  "A2A compatibility pins against OpenCrabs' actual a2a_send wire shape."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.web :as web]
            [bb-agent.web-turn :as turn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- body-json [response]
  (json/parse-string (:body response) true))

(defn- request
  ([uri method payload token]
   {:request-method method
    :uri uri
    :headers (cond-> {"host" "127.0.0.1:8787"}
               token (assoc "authorization" (str "Bearer " token)))
    :body (some-> payload json/generate-string
                  java.io.StringReader.)}))

(defn- rpc
  ([payload] (rpc payload nil))
  ([payload token]
   (web/app (request "/a2a/v1" :post payload token))))

(defn- wait-for-task
  [id token timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [result (:result (body-json
                             (rpc {:jsonrpc "2.0" :id 2
                                   :method "tasks/get"
                                   :params {:id id}}
                                  token)))
            state (get-in result [:status :state])]
        (if (or (contains? #{"completed" "failed" "canceled"} state)
                (>= (System/currentTimeMillis) deadline))
          result
          (do (Thread/sleep 50) (recur)))))))

(deftest discovery-matches-opencrabs-and-standard-card-paths
  (doseq [uri ["/.well-known/agent.json" "/.well-known/agent-card.json"]]
    (let [response (web/app (request uri :get nil nil))
          card (body-json response)]
      (is (= 200 (:status response)) uri)
      (is (= "Theseus" (:name card)))
      (is (= "1.0" (:protocolVersion card)))
      (is (= "JSONRPC" (:preferredTransport card)))
      (is (str/ends-with? (:url card) "/a2a/v1"))
      (is (= "JSONRPC" (get-in card [:supportedInterfaces 0 :protocolBinding])))
      (is (false? (get-in card [:capabilities :streaming]))))))

(deftest authenticated-rpc-reuses-the-real-web-turn-lane
  (let [home (fs/create-temp-dir {:prefix "theseus-a2a-e2e-"})]
    (fs/create-dirs (fs/path home "state"))
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (testing "anonymous calls are refused and bootstrap a mode-600 token"
        (is (= 401 (:status (rpc {:jsonrpc "2.0" :id 1
                                  :method "tasks/get"
                                  :params {:id "missing"}}))))
        (let [token-path (fs/path home "state" "a2a-token")
              permissions (str (fs/posix-file-permissions token-path))]
          (is (fs/regular-file? token-path))
          (is (str/includes? permissions "OWNER_READ"))
          (is (not (str/includes? permissions "GROUP")))))
      (let [token (str/trim (slurp (str (fs/path home "state" "a2a-token"))))]
        (testing "OpenCrabs' bare text part and contextId are accepted"
          (let [response (rpc {:jsonrpc "2.0" :id 1
                               :method "message/send"
                               :params {:message
                                        {:role "user"
                                         :contextId "a2a-contract"
                                         :parts [{:text "say pong"}]}}}
                              token)
                result (:result (body-json response))]
            (is (= 200 (:status response)))
            (is (string? (:id result)))
            (is (= "a2a-contract" (:contextId result)))
            (let [task (wait-for-task (:id result) token 20000)]
              (is (= "completed" (get-in task [:status :state])))
              (is (= "pong" (get-in task [:artifacts 0 :parts 0 :text]))))))
        (testing "bad params, unknown tasks, and unknown methods stay JSON-RPC"
          (is (= -32602 (get-in (body-json
                                 (rpc {:jsonrpc "2.0" :id 3
                                       :method "message/send"
                                       :params {:message {:parts [{:text "  "}]}}}
                                      token))
                                [:error :code])))
          (is (= -32001 (get-in (body-json
                                 (rpc {:jsonrpc "2.0" :id 4
                                       :method "tasks/get"
                                       :params {:id "job-does-not-exist"}}
                                      token))
                                [:error :code])))
          (is (= -32601 (get-in (body-json
                                 (rpc {:jsonrpc "2.0" :id 5
                                       :method "not/a-method" :params {}}
                                      token))
                                [:error :code]))))))))
