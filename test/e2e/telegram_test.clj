(ns e2e.telegram-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest telegram-polling-reuses-core-turn-loop
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-telegram-e2e-"})
        port (free-port)
        calls (atom [])
        stop-server (server/run-server
                     (fn [req]
                       (let [uri (:uri req)]
                         (swap! calls conj {:uri uri
                                            :method (:request-method req)
                                            :body (when-let [body (:body req)] (slurp body))})
                         (case uri
                            "/botTESTTOKEN/getUpdates"
                            {:status 200
                             :headers {"content-type" "application/json"}
                             :body (json/generate-string
                                    {:ok true
                                     :result [{:update_id 1
                                              :message {:message_id 7
                                                        :chat {:id 4242}
                                                        :text "say pong"}}]})}

                           "/botTESTTOKEN/sendMessage"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:ok true :result {:message_id 8}})}

                           {:status 404
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:ok false})})))
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "telegram-4242.edn")
        offset-file (fs/path home "state" "telegram-offset.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)}}))
      (let [result (shell! home "telegram" "poll-once")
            send-call (some #(when (= "/botTESTTOKEN/sendMessage" (:uri %)) %) @calls)
            updates-call (some #(when (= "/botTESTTOKEN/getUpdates" (:uri %)) %) @calls)
            send-body (some-> send-call :body (json/parse-string keyword))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "telegram-updates=1"))
        (is updates-call)
        (is send-call)
        (is (= 4242 (:chat_id send-body)))
        (is (= "pong" (:text send-body)))
        (is (= 2 (edn/read-string (slurp (str offset-file)))))
        (is (fs/regular-file? session-file))
        (let [turns (edn/read-string (slurp (str session-file)))
              turn (first turns)]
          (is (= "say pong" (:user/input turn)))
          (is (= "pong" (:assistant/final turn)))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest telegram-approval-replies-do-not-run-agent-turns
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-telegram-approval-"})
        port (free-port)
        calls (atom [])
        stop-server (server/run-server
                     (fn [req]
                       (swap! calls conj {:uri (:uri req)
                                          :method (:request-method req)
                                          :body (when-let [body (:body req)] (slurp body))})
                       (case (:uri req)
                         "/botTESTTOKEN/getUpdates"
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                 {:ok true
                                  :result [{:update_id 10
                                            :message {:message_id 70
                                                      :chat {:id 4242}
                                                      :text "/approve"}}]})}
                         "/botTESTTOKEN/sendMessage"
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:ok true})}
                         {:status 404
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:ok false})}))
                     {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions" "telegram-4242.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)}}))
      (let [result (shell! home "telegram" "poll-once")
            send-call (some #(when (= "/botTESTTOKEN/sendMessage" (:uri %)) %) @calls)
            send-body (some-> send-call :body (json/parse-string keyword))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:text send-body) "No pending tool approval"))
        (is (not (fs/exists? session-file))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest telegram-approval-reply-resolves-pending-request
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-telegram-resolve-"})
        port (free-port)
        calls (atom [])
        stop-server (server/run-server
                     (fn [req]
                       (swap! calls conj {:uri (:uri req)
                                          :body (when-let [body (:body req)] (slurp body))})
                       (case (:uri req)
                         "/botTESTTOKEN/getUpdates"
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                 {:ok true
                                  :result [{:update_id 20
                                            :message {:message_id 71
                                                      :chat {:id 4242}
                                                      :text "/approve"}}]})}
                         "/botTESTTOKEN/sendMessage"
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:ok true})}
                         {:status 404
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:ok false})}))
                     {:port port})
        config-file (fs/path home "config.edn")
        approvals-file (fs/path home "state" "approvals.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)}}))
      (fs/create-dirs (fs/parent approvals-file))
      (spit (str approvals-file)
            (pr-str {:pending {"p1" {:approval/id "p1"
                                      :session/id "telegram-4242"
                                      :tool/name "shell"}}
                     :decisions {}
                     :approve-rest #{}}))
      (let [result (shell! home "telegram" "poll-once")
            send-call (some #(when (= "/botTESTTOKEN/sendMessage" (:uri %)) %) @calls)
            send-body (some-> send-call :body (json/parse-string keyword))
            state (edn/read-string (slurp (str approvals-file)))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:text send-body) "Approved pending tool"))
        (is (empty? (:pending state)))
        (is (= :approve (get-in state [:decisions "p1"]))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))
