(ns e2e.telegram-group-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.core :as core]
            [bb-agent.memory :as memory]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-guard :as guard]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def group-id -1003995594829)
(def owner-id 1608111860)
(def bot-id 8511646577)

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- bot-info []
  {:id bot-id :username "eileenslybot" :is_bot true})

(defn- group-message
  [{:keys [message-id topic-id text user-id username reply-to bot-sender?]
    :or {user-id owner-id username "designpoa"}}]
  (cond-> {:message_id message-id
           :message_thread_id topic-id
           :is_topic_message (some? topic-id)
           :from {:id user-id
                  :is_bot (boolean bot-sender?)
                  :first_name (if bot-sender? "Other Bot" "moe")
                  :username username}
           :chat {:id group-id
                  :type "supergroup"
                  :title "Sly Theseus"
                  :is_forum true}
           :text text}
    reply-to (assoc :reply_to_message reply-to)))

(def owner-config
  {:telegram {:allowed-chat-ids [owner-id]
              :allowed-user-ids [owner-id]
              :respond-to :mention
              :groups {group-id {:allowed-user-ids [owner-id]
                                 :respond-to :mention}}}})

(deftest authorization-is-user-and-group-scoped
  (let [base {:telegram {:allowed-user-ids [owner-id]
                         :groups {group-id {:allowed-user-ids [293040805]}}}}
        owner-msg (group-message {:message-id 1 :text "@eileenslybot hi"})
        group-user-msg (group-message {:message-id 2
                                       :user-id 293040805
                                       :username "undomestcated"
                                       :text "@eileenslybot hi"})
        outsider-msg (group-message {:message-id 3
                                     :user-id 999
                                     :username "outsider"
                                     :text "@eileenslybot hi"})
        outsider-dm {:message_id 4
                     :from {:id 293040805 :is_bot false}
                     :chat {:id 293040805 :type "private"}
                     :text "hi"}]
    (is (guard/message-allowed? base owner-msg))
    (is (guard/message-allowed? base group-user-msg))
    (is (not (guard/message-allowed? base outsider-msg)))
    (is (not (guard/message-allowed? base outsider-dm))
        "a group grant must never become a DM grant")))

(deftest explicitly-open-group-stays-group-scoped
  (let [cfg {:telegram {:allowed-user-ids [owner-id]
                        :groups {group-id {:open true}}}}
        member (group-message {:message-id 1 :user-id 999 :text "@eileenslybot hi"})
        dm {:message_id 2
            :from {:id 999 :is_bot false}
            :chat {:id 999 :type "private"}
            :text "hi"}]
    (is (guard/message-allowed? cfg member))
    (is (not (guard/message-allowed? cfg dm)))
    (is (not (guard/message-allowed?
              {:telegram {:groups {group-id {:open true}}}}
              member))
        "open mode requires a configured global operator")))

(deftest numeric-and-string-group-config-keys-resolve-identically
  (let [group-rules {:allowed-user-ids [owner-id] :respond-to :all}]
    (is (= group-rules
           (group/group-config {:telegram {:groups {group-id group-rules}}} group-id)))
    (is (= group-rules
           (group/group-config {:telegram {:groups {(str group-id) group-rules}}} group-id)))))

(deftest group-activation-is-directed-and-loop-safe
  (let [bot (bot-info)
        reply-to-bot {:message_id 40
                      :from bot
                      :text "Earlier answer"}
        mention (group-message {:message-id 1 :text "@eileenslybot status"})
        reply (group-message {:message-id 2 :text "continue" :reply-to reply-to-bot})
        ambient (group-message {:message-id 3 :text "people talking"})
        other-bot (group-message {:message-id 4
                                  :text "@eileenslybot loop"
                                  :user-id 9999
                                  :username "other_bot"
                                  :bot-sender? true})
        redirected (group-message {:message-id 5
                                   :text "@another_helper_bot handle this"
                                   :reply-to reply-to-bot})]
    (is (group/should-respond? owner-config bot mention))
    (is (group/should-respond? owner-config bot reply))
    (is (not (group/should-respond? owner-config bot ambient)))
    (is (not (group/should-respond? owner-config bot other-bot)))
    (is (not (group/should-respond? owner-config bot redirected)))
    (is (group/should-respond? owner-config bot
                               (assoc redirected :text "@eileenslybot @another_helper_bot coordinate"))
        "an explicit mention of us wins even when another bot is also named")
    (is (group/should-respond?
         (assoc-in owner-config [:telegram :groups group-id :respond-to] :all)
         bot
         ambient))))

(deftest forum-topics-have-stable-distinct-session-ids
  (let [topic-a (group-message {:message-id 1 :topic-id 101 :text "a"})
        topic-b (group-message {:message-id 2 :topic-id 202 :text "b"})
        general (group-message {:message-id 3 :text "general"})
        plain-reply-thread (assoc general :message_thread_id 99 :is_topic_message false)]
    (is (= (str "telegram-" group-id "-topic-101") (group/session-id topic-a)))
    (is (= (str "telegram-" group-id "-topic-202") (group/session-id topic-b)))
    (is (not= (group/session-id topic-a) (group/session-id topic-b)))
    (is (= (str "telegram-" group-id) (group/session-id general)))
    (is (= (group/session-id general) (group/session-id plain-reply-thread))
        "ordinary reply threads must not become forum-topic sessions")))

(deftest group-input-carries-sender-and-reply-context
  (let [message (group-message
                 {:message-id 2
                  :topic-id 202
                  :text "continue"
                  :reply-to {:message_id 40
                             :from (bot-info)
                             :text "Earlier answer"}})
        input (group/agent-input (bot-info) message)]
    (is (str/includes? input "Sly Theseus"))
    (is (str/includes? input "moe (@designpoa), ID 1608111860"))
    (is (str/includes? input "topic 202"))
    (is (str/includes? input "Replying to assistant"))
    (is (str/includes? input "Earlier answer"))
    (is (str/ends-with? input "continue"))))

(deftest shared-group-turns-do-not-read-or-index-private-memory
  (let [memory-called? (atom false)
        semantic-called? (atom false)
        indexed? (atom false)
        turn (with-redefs [memory/attach-memories
                           (fn [_] (reset! memory-called? true) [{:memory/text "private"}])
                           semantic-memory/attach-context
                           (fn [_ _] (reset! semantic-called? true) "private context")
                           semantic-memory/index-session!
                           (fn [_] (reset! indexed? true))]
               (core/run-turn! {:provider :fake
                                :model "fake-deterministic"
                                :session/id "telegram-shared-test"
                                :session/shared? true
                                :semantic-memory {:enabled true}}
                               "say pong"))]
    (is (= "pong" (:assistant/final turn)))
    (is (not @memory-called?))
    (is (not @semantic-called?))
    (is (not @indexed?))
    (is (empty? (:memory/matches turn)))
    (is (nil? (:semantic/context turn)))))

(deftest telegram-command-suffix-is-normalized
  (is (= "/approve" (group/normalize-command "/approve@eileenslybot" (bot-info))))
  (is (= "/approve@otherbot" (group/normalize-command "/approve@otherbot" (bot-info))))
  (is (= "plain" (group/normalize-command "plain" (bot-info)))))

(deftest topic-scoped-approval-replies-stay-in-their-topic
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-topic-approval-"})
        port (free-port)
        calls (atom [])
        topic 707
        session-id (str "telegram-" group-id "-topic-" topic)
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result (bot-info)})}
               "/botTESTTOKEN/getUpdates"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true
                        :result [{:update_id 70
                                  :message (group-message
                                            {:message-id 700
                                             :topic-id topic
                                             :text "/approve@eileenslybot"})}]})}
               "/botTESTTOKEN/sendMessage"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true})}
               {:status 404 :body (json/generate-string {:ok false})})))
         {:port port})
        config-file (fs/path home "config.edn")
        approvals-file (fs/path home "state" "approvals.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-user-ids [owner-id]
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (fs/create-dirs (fs/parent approvals-file))
      (spit (str approvals-file)
            (pr-str {:pending {"p-topic" {:approval/id "p-topic"
                                           :session/id session-id
                                           :tool/name "shell"}}
                     :decisions {}
                     :approve-rest #{}}))
      (let [result (shell! home "telegram" "poll-once")
            sent (->> @calls
                      (filter #(= "/botTESTTOKEN/sendMessage" (:uri %)))
                      first
                      :body
                      (#(json/parse-string % keyword)))
            state (edn/read-string (slurp (str approvals-file)))]
        (is (= 0 (:exit result)) (:err result))
        (is (= topic (:message_thread_id sent)))
        (is (str/includes? (:text sent) "Approved pending tool"))
        (is (empty? (:pending state)))
        (is (= :approve (get-in state [:decisions "p-topic"]))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest polling-isolates-topics-and-preserves-thread-on-replies
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-group-"})
        port (free-port)
        calls (atom [])
        updates [{:update_id 1
                  :message (group-message {:message-id 11
                                           :topic-id 101
                                           :text "@eileenslybot alpha"})}
                 {:update_id 2
                  :message (group-message
                            {:message-id 22
                             :topic-id 202
                             :text "continue beta"
                             :reply-to {:message_id 20
                                        :from (bot-info)
                                        :text "Earlier answer"}})}
                 {:update_id 3
                  :message (group-message {:message-id 33
                                           :topic-id 303
                                           :text "ambient discussion"})}
                 {:update_id 4
                  :message (group-message {:message-id 44
                                           :topic-id 404
                                           :text "@eileenslybot loop"
                                           :user-id 9999
                                           :username "another_bot"
                                           :bot-sender? true})}
                 {:update_id 5
                  :message (group-message {:message-id 55
                                           :topic-id 505
                                           :text "@eileenslybot unauthorized"
                                           :user-id 777
                                           :username "outsider"})}]
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result (bot-info)})}

               "/botTESTTOKEN/getUpdates"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result updates})}

               "/botTESTTOKEN/sendMessage"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 900}})}

               {:status 404
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false})})))
         {:port port})
        config-file (fs/path home "config.edn")
        session-a (fs/path home "state" "sessions"
                           (str "telegram-" group-id "-topic-101.edn"))
        session-b (fs/path home "state" "sessions"
                           (str "telegram-" group-id "-topic-202.edn"))]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-user-ids [owner-id]
                                :respond-to :mention
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [result (shell! home "telegram" "poll-once")
            sent (->> @calls
                      (filter #(= "/botTESTTOKEN/sendMessage" (:uri %)))
                      (map #(json/parse-string (:body %) keyword))
                      vec)
            by-thread (into {} (map (juxt :message_thread_id identity) sent))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "telegram-updates=5"))
        (is (= #{101 202} (set (keys by-thread))))
        (is (= {:message_id 11} (:reply_parameters (get by-thread 101))))
        (is (= {:message_id 22} (:reply_parameters (get by-thread 202))))
        (is (fs/regular-file? session-a))
        (is (fs/regular-file? session-b))
        (let [turn-a (first (edn/read-string (slurp (str session-a))))
              turn-b (first (edn/read-string (slurp (str session-b))))]
          (is (str/includes? (:user/input turn-a) "alpha"))
          (is (not (str/includes? (:user/input turn-a) "beta")))
          (is (str/includes? (:user/input turn-b) "beta"))
          (is (str/includes? (:user/input turn-b) "Earlier answer")))
        (doseq [topic [303 404 505]]
          (is (not (fs/exists?
                    (fs/path home "state" "sessions"
                             (str "telegram-" group-id "-topic-" topic ".edn")))))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))
