(ns e2e.telegram-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
                           "/botTESTTOKEN/getMe"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string
                                   {:ok true
                                    :result {:id 8511646577
                                             :is_bot true
                                             :username "eileenslybot"}})}

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
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-chat-ids [4242]}}))
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
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-chat-ids [4242]}}))
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
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-chat-ids [4242]}}))
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

(deftest telegram-denies-chats-outside-allowlist
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-telegram-deny-"})
        port (free-port)
        calls (atom [])
        stop-server (server/run-server
                     (fn [req]
                       (swap! calls conj {:uri (:uri req)})
                       (case (:uri req)
                         "/botTESTTOKEN/getUpdates"
                         {:status 200
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string
                                 {:ok true
                                  :result [{:update_id 20
                                            :message {:message_id 90
                                                      :chat {:id 9999}
                                                      :text "say pong"}}]})}
                         {:status 404
                          :headers {"content-type" "application/json"}
                          :body (json/generate-string {:ok false})}))
                     {:port port})
        config-file (fs/path home "config.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-chat-ids [4242]}}))
      (let [result (shell! home "telegram" "poll-once")
            uris (map :uri @calls)]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "telegram-updates=1"))
        (is (some #(str/includes? % "getUpdates") uris))
        (is (not-any? #(str/includes? % "sendMessage") uris)
            "denied chat must never receive a reply")
        (is (not (fs/exists? (fs/path home "state" "sessions" "telegram-9999.edn")))
            "denied chat must not get a session"))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(defn- run-presence-scenario!
  "Boot a fake Bot API where setMessageReaction answers with the given
   status, run one poll-once against a temp home, and return the observed
   outbound calls as parsed bodies."
  [reaction-status]
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-telegram-presence-"})
        port (free-port)
        calls (atom [])
        stop-server (server/run-server
                     (fn [req]
                       (let [uri (:uri req)]
                         (when (= :post (:request-method req))
                           (swap! calls conj {:uri uri
                                              :body (some-> (:body req) slurp)}))
                         (case uri
                           "/botTESTTOKEN/getMe"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string
                                   {:ok true
                                    :result {:id 8511646577
                                             :is_bot true
                                             :username "eileenslybot"}})}

                           "/botTESTTOKEN/getUpdates"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string
                                   {:ok true
                                    :result [{:update_id 1
                                              :message {:message_id 7
                                                        :chat {:id 4242}
                                                        :text "say pong"}}]})}

                           "/botTESTTOKEN/sendChatAction"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:ok true})}

                           "/botTESTTOKEN/setMessageReaction"
                           {:status reaction-status
                            :headers {"content-type" "application/json"}
                            :body (if (= 200 reaction-status)
                                    (json/generate-string {:ok true})
                                    (json/generate-string
                                     {:ok false
                                      :error_code 400
                                      :description "Bad Request: REACTION_INVALID"}))}

                           "/botTESTTOKEN/sendMessage"
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:ok true :result {:message_id 8}})}

                           {:status 404
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:ok false})})))
                     {:port port})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-chat-ids [4242]
                                :typing-indicator true
                                :react-ack true}}))
      (let [result (shell! home "telegram" "poll-once")
            body-for (fn [method]
                       (some-> (some #(when (str/includes? (:uri %) (str "/" method)) %) @calls)
                               :body
                               (json/parse-string keyword)))]
        {:exit (:exit result)
         :err (:err result)
         :typing (body-for "sendChatAction")
         :reaction (body-for "setMessageReaction")
         :reply (body-for "sendMessage")})
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest presence-signals-accompany-turns-and-never-block-them
  (let [observed (run-presence-scenario! 200)]
    (is (= 0 (:exit observed)) (:err observed))
    (is (= {:chat_id 4242 :action "typing"} (:typing observed)))
    (is (= {:chat_id 4242 :message_id 7
            :reaction [{:type "emoji" :emoji "👌"}]}
           (:reaction observed)))
    (is (= "pong" (:text (:reply observed)))))
  (testing "a rejected reaction ack never blocks the turn"
    (let [observed (run-presence-scenario! 400)]
      (is (= 0 (:exit observed)) (:err observed))
      (is (= "pong" (:text (:reply observed)))))))
