(ns e2e.telegram-reactions-test
  "Incoming message_reaction updates must be requested from the Bot API and
   surfaced as session context notes, like edits — a 👍 on a reply should
   not be invisible."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def owner-id 1608111860)

(defn- dm-message [update-id message-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id owner-id :is_bot false :first_name "moe"}
             :chat {:id owner-id :type "private"}
             :text text}})

(defn- reaction-update [update-id emoji]
  {:update_id update-id
   :message_reaction {:chat {:id owner-id :type "private"}
                      :message_id 90
                      :user {:id owner-id :is_bot false :first_name "moe"}
                      :date 1700000000
                      :old_reaction []
                      :new_reaction [{:type "emoji" :emoji emoji}]}})

(defn- run-poll [updates]
  (let [home (fs/create-temp-dir {:prefix "theseus-reactions-"})
        port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
             (let [body (when-let [stream (:body req)] (slurp stream))]
               (swap! calls conj {:uri (:uri req) :query-string (:query-string req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:id 1 :username "eileenslybot" :is_bot true}})}
               "/botTESTTOKEN/getUpdates"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result updates})}
               "/botTESTTOKEN/sendMessage"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 91}})}
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result true})})))
         {:port port})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :react-ack false
                                :typing-indicator false
                                :allowed-user-ids [owner-id]}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))]
        {:calls @calls :home (str home) :result result})
      (finally
        (stop-server)))))

(deftest getupdates-requests-reactions
  (testing "the poll asks Telegram for message_reaction updates"
    (let [{:keys [home calls]} (run-poll [])]
      (try
        (let [gu (first (filter #(str/includes? (:uri %) "getUpdates") calls))]
          (is gu "getUpdates was called")
          (is (str/includes? (or (:query-string gu) "") "message_reaction")
              "message_reaction in allowed_updates"))
        (finally
          (fs/delete-tree home))))))

(deftest reaction-becomes-context-for-the-next-turn
  (testing "turn 1, then a 👍 reaction, then turn 2 sees the reaction note"
    (let [{:keys [home]} (run-poll [(dm-message 1 10 "first question")
                                    (reaction-update 2 "👍")
                                    (dm-message 3 11 "second question")])
          inputs (->> (fs/glob (fs/path home "state" "sessions") "*.edn")
                      (filter fs/regular-file?)
                      (mapcat (fn [f] (edn/read-string (slurp (str f)))))
                      (map :user/input))
          second-input (last inputs)]
      (try
        (is (= 2 (count inputs)) "both turns ran")
        (is (str/includes? second-input "👍") "reaction emoji surfaced")
        (is (str/includes? second-input "moe") "reactor named")
        (finally
          (fs/delete-tree home))))))
