(ns e2e.provider-timeout-test
  (:require [bb-agent.provider :as provider]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def ^:private completion-body
  (str "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
       "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}],"
       "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"))

(defn- with-slow-server
  "Serve one OpenAI-shaped completion after delay-ms; run f with the port."
  [delay-ms f]
  (let [reply (fn [req]
                (Thread/sleep (long delay-ms))
                {:status 200
                 :headers {"content-type" "application/json"}
                 :body completion-body})
        stop (server/run-server reply {:port 0})]
    (try
      (f (:local-port (meta stop)))
      (finally (stop)))))

(defn- call-provider
  ([port] (call-provider port nil))
  ([port timeout-ms]
   (provider/complete
    :openai-compatible
    {:model "m"
     :messages [{:role :user :content "hi"}]
     :provider/config (cond-> {:base-url (str "http://127.0.0.1:" port)
                               :api-key "test-key"}
                        timeout-ms (assoc :timeout-ms timeout-ms))})))

(deftest timeout-ms-is-honored
  (with-slow-server
   400
   (fn [port]
     (testing "a request exceeding :timeout-ms fails"
       (is (thrown? Exception (call-provider port 50))))
     (testing "a request within :timeout-ms succeeds"
       (is (= "pong" (:content (call-provider port 10000))))))))

(deftest default-timeout-still-allows-fast-replies
  (with-slow-server
   0
   (fn [port]
     (is (= "pong" (:content (call-provider port)))))))
