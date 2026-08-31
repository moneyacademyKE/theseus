(ns e2e.anthropic-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"OPENCRABS_HOME" (str home)}}
           "bb" "agent" prompt))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest anthropic-provider-persists-session-turn
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-anthropic-e2e-"})
        port (free-port)
        captured (promise)
        stop-server (server/run-server
                     (fn [req]
                       (deliver captured req)
                       {:status 200
                        :headers {"content-type" "application/json"}
                        :body (json/generate-string
                               {:content [{:type "text"
                                           :text "pong-anthropic"}]
                                :role "assistant"})})
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "anthropic-session.edn")]
    (try
      (fs/create-dirs home)
      (spit (str config-file)
            (pr-str {:provider :anthropic-compatible
                     :model "claude-3-5-sonnet-20241022"
                     :session/id "anthropic-session"
                     :providers {:anthropic-compatible
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-anthropic-key"}}}))
      (let [result (run-agent home "say pong")
            req (deref captured 1000 nil)]
        (is (= 0 (:exit result)) (:err result))
        (is (= "pong-anthropic\n" (:out result)))
        (is (= :post (:request-method req)))
        (is (= "/v1/messages" (:uri req)))
        (is (= "test-anthropic-key" (get-in req [:headers "x-api-key"])))
        (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
        (let [body (json/parse-string (slurp (:body req)) keyword)]
          (is (= "claude-3-5-sonnet-20241022" (:model body)))
          (is (= "say pong" (get-in body [:messages 0 :content]))))
        (let [turns (edn/read-string (slurp (str session-file)))
              turn (first turns)]
          (is (= 1 (count turns)))
          (is (= "anthropic-session" (:session/id turn)))
          (is (= :anthropic-compatible (:provider turn)))
          (is (= "claude-3-5-sonnet-20241022" (:model turn)))
          (is (= "pong-anthropic" (:assistant/final turn)))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest anthropic-provider-parses-tool-use
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-anthropic-tool-e2e-"})
        port (free-port)
        calls (atom 0)
        stop-server (server/run-server
                     (fn [_req]
                       (let [n (swap! calls inc)]
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                 (if (= 1 n)
                                   {:content [{:type "tool_use"
                                               :id "toolu_test_1"
                                               :name "read_file"
                                               :input {:path (str (fs/path home "note.txt"))}}]
                                    :role "assistant"}
                                   {:content [{:type "text"
                                               :text "read complete"}]
                                    :role "assistant"}))}))
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "anthropic-tool-session.edn")]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "note.txt")) "hello from anthropic")
      (spit (str config-file)
            (pr-str {:provider :anthropic-compatible
                     :model "claude-3-5-sonnet-20241022"
                     :session/id "anthropic-tool-session"
                     :providers {:anthropic-compatible
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-anthropic-key"}}}))
      (let [result (run-agent home "read note")
            turn (first (edn/read-string (slurp (str session-file))))]
        (is (= 0 (:exit result)) (:err result))
        (is (re-find #":status :denied" (:out result)))
        (is (= 1 @calls))
        (is (= ["read_file"] (mapv :tool/name (:tool/requests turn))))
        (is (= "toolu_test_1" (get-in turn [:tool/requests 0 :tool/id])))
        (is (= [:denied] (mapv :status (:tool/results turn))))
        (is (= false (get-in turn [:tool/results 0 :executed?]))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))
