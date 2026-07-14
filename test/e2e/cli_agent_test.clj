(ns e2e.cli-agent-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"OPENCRABS_HOME" (str home)}}
           "bb" "agent" prompt))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest fake-provider-persists-session-turn
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-e2e-"})
        result (run-agent home "say pong")
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (is (= 0 (:exit result)) (:err result))
      (is (= "pong\n" (:out result)))
      (is (fs/regular-file? session-file))
      (let [turns (edn/read-string (slurp (str session-file)))]
        (is (= 1 (count turns)))
        (is (= {:session/id "default"
                :user/input "say pong"
                :provider :fake
                :model "fake-deterministic"
                :assistant/final "pong"}
               (select-keys (first turns)
                            [:session/id :user/input :provider :model :assistant/final]))))
      (finally
        (fs/delete-tree home)))))

(deftest openai-compatible-provider-persists-session-turn
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-http-e2e-"})
        port (free-port)
        captured (promise)
        stop-server (server/run-server
                     (fn [req]
                       (deliver captured req)
                       {:status 200
                        :headers {"content-type" "application/json"}
                         :body (json/generate-string
                                {:choices [{:message {:role "assistant"
                                                      :content "pong-http"}}]
                                 :usage {:prompt_tokens 11
                                         :completion_tokens 7}})})
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "http-provider.edn")]
    (try
      (fs/create-dirs home)
      (spit (str config-file)
            (pr-str {:provider :openai-compatible
                     :model "test-model"
                     :session/id "http-provider"
                     :providers {:openai-compatible
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-key"}}}))
      (let [result (run-agent home "say pong")
            req (deref captured 1000 nil)]
        (is (= 0 (:exit result)) (:err result))
        (is (= "pong-http\n" (:out result)))
        (is (= :post (:request-method req)))
        (is (= "/v1/chat/completions" (:uri req)))
        (is (= "Bearer test-key" (get-in req [:headers "authorization"])))
        (let [body (json/parse-string (slurp (:body req)) keyword)]
          (is (= "test-model" (:model body)))
          (is (= "say pong" (get-in body [:messages 0 :content]))))
        (let [turns (edn/read-string (slurp (str session-file)))
              usage-events (edn/read-string (slurp (str (fs/path home "state" "usage.edn"))))
              turn (first turns)]
          (is (= 1 (count turns)))
          (is (= "http-provider" (:session/id turn)))
          (is (= :openai-compatible (:provider turn)))
          (is (= "test-model" (:model turn)))
          (is (= "pong-http" (:assistant/final turn)))
          (is (= 11 (:tokens/input (first usage-events))))
          (is (= 7 (:tokens/output (first usage-events))))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest openai-compatible-provider-renders-clear-errors
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-http-error-e2e-"})
        port (free-port)
        stop-server (server/run-server
                     (fn [_req]
                       {:status 500
                        :headers {"content-type" "application/json"}
                        :body (json/generate-string {:error {:message "boom"}})})
                     {:port port})
        config-file (fs/path home "config.edn")]
    (try
      (fs/create-dirs home)
      (spit (str config-file)
            (pr-str {:provider :openai-compatible
                     :model "test-model"
                     :providers {:openai-compatible
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-key"}}}))
      (let [result (run-agent home "say pong")]
        (is (= 1 (:exit result)))
        (is (= "" (:out result)))
        (is (re-find #"Error: Provider request failed with status 500" (:err result))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest openai-compatible-provider-parses-tool-calls
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-http-tools-e2e-"})
        port (free-port)
        calls (atom 0)
        stop-server (server/run-server
                     (fn [_req]
                       (let [n (swap! calls inc)]
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                 (if (= 1 n)
                                   {:choices [{:message
                                               {:role "assistant"
                                                :tool_calls
                                                [{:function
                                                  {:name "read_file"
                                                   :arguments (json/generate-string
                                                               {:path (str (fs/path home "note.txt"))})}}]}}]}
                                   {:choices [{:message {:role "assistant"
                                                         :content "read complete"}}]}))}))
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "http-tools.edn")]
    (try
      (fs/create-dirs home)
      (spit (str (fs/path home "note.txt")) "hello from tool")
      (spit (str config-file)
            (pr-str {:provider :openai-compatible
                     :model "test-model"
                     :session/id "http-tools"
                     :providers {:openai-compatible
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-key"}}}))
      (let [result (run-agent home "read note")
            turn (first (edn/read-string (slurp (str session-file))))]
        (is (= 0 (:exit result)) (:err result))
        (is (re-find #":status :denied" (:out result)))
        (is (= 1 @calls))
        (is (= ["read_file"] (mapv :tool/name (:tool/requests turn))))
        (is (= [:denied] (mapv :status (:tool/results turn))))
        (is (= false (get-in turn [:tool/results 0 :executed?]))))
       (finally
         (stop-server)
         (fs/delete-tree home)))))

(deftest session-metadata-and-usage-report-are-persisted
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-session-usage-"})
        workspace (fs/path home "workspace")]
    (try
      (fs/create-dirs workspace)
      (let [agent (run-agent home "say pong")
            current (shell! home "session" "current")
            list-result (shell! home "session" "list")
            set-cwd (shell! home "session" "set-cwd" "default" (str workspace))
            usage-result (shell! home "usage" "report")]
        (is (= 0 (:exit agent)) (:err agent))
        (is (= 0 (:exit current)) (:err current))
        (is (= 0 (:exit list-result)) (:err list-result))
        (is (= 0 (:exit set-cwd)) (:err set-cwd))
        (is (= 0 (:exit usage-result)) (:err usage-result))
        (is (str/includes? (:out current) ":provider :fake"))
        (is (str/includes? (:out list-result) ":session/id \"default\""))
        (is (str/includes? (:out set-cwd) (str workspace)))
        (let [report (edn/read-string (:out usage-result))]
          (is (= 1 (:usage/events report)))
          (is (pos? (:tokens/total report)))))
      (finally
        (fs/delete-tree home)))))

(deftest ask-flag-approves-one-tool-interactively
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-ask-"})
        side-effect-file (fs/path home "approved")
        command (str "touch " side-effect-file)]
    (try
      (let [result (p/process ["bb" "agent" "--ask" (str "try denied shell " command)]
                              {:out :string
                               :err :string
                               :in "y\n"
                               :continue true
                               :extra-env {"OPENCRABS_HOME" (str home)}})
            completed @result]
        (is (= 0 (:exit completed)) (:err completed))
        (is (fs/exists? side-effect-file))
        (is (str/includes? (:err completed) "Tool request: shell")))
      (finally
        (fs/delete-tree home)))))
