(ns e2e.telegram-approvals-test
  (:require [babashka.fs :as fs]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-guard :as guard]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def group-id -1003995594829)
(def owner-id 1608111860)
(def topic-id 4721)
(def approval-id "a1b2c3d4-0000-4000-8000-000000000001")
(def session-id (str "telegram-" group-id "-topic-" topic-id))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- bot-info []
  {:id 8511646577 :username "eileenslybot" :is_bot true})

(defn- forum-message
  [message-id extra]
  (merge {:message_id message-id
          :message_thread_id topic-id
          :is_topic_message true
          :from {:id owner-id :is_bot false :first_name "moe"}
          :chat {:id group-id :type "supergroup"
                 :title "Sly Theseus" :is_forum true}}
         extra))

(deftest callback-data-parses-to-bounded-actions
  (testing "approve, deny, and approve-rest parse with their approval id"
    (is (= {:action :approve :approval-id approval-id}
           (telegram/parse-callback-data (str "appr:" approval-id))))
    (is (= {:action :deny :approval-id approval-id}
           (telegram/parse-callback-data (str "deny:" approval-id))))
    (is (= {:action :approve-rest :approval-id approval-id}
           (telegram/parse-callback-data (str "apprrest:" approval-id)))))
  (testing "anything else is not an approval callback"
    (is (nil? (telegram/parse-callback-data "junk")))
    (is (nil? (telegram/parse-callback-data "appr:")))
    (is (nil? (telegram/parse-callback-data "")))
    (is (nil? (telegram/parse-callback-data nil)))))

(deftest resolve-by-id-applies-only-the-matching-session
  (let [home (fs/create-temp-dir {:prefix "theseus-approvals-unit-"})]
    (try
      (with-redefs [config/home (fn [] (str home))]
        (approval/create-pending! session-id :telegram
                                  {:tool/name "shell" :tool/args {:cmd "ls"}})
        ;; bind the generated pending id to our fixed id for determinism
        (let [state (approval/load-state)
              generated (first (keys (:pending state)))
              pending (get-in state [:pending generated])
              rebound (assoc pending :approval/id approval-id)]
          (approval/save-state!
           (-> state (update :pending dissoc generated)
               (assoc-in [:pending approval-id] rebound)))
          (testing "a different session may not resolve the approval"
            (is (nil? (approval/resolve-by-id! approval-id "telegram-99" :deny)))
            (is (contains? (:pending (approval/load-state)) approval-id)
                "the pending approval must survive a foreign session"))
          (testing "the owning session resolves by id"
            (let [resolved (approval/resolve-by-id! approval-id session-id :deny)]
              (is (some? resolved))
              (is (= :deny (:approval/decision resolved)))
              (let [state* (approval/load-state)]
                (is (not (contains? (:pending state*) approval-id)))
                (is (= :deny (get-in state* [:decisions approval-id]))))))))
      (finally
        (fs/delete-tree home)))))

(deftest callback-clickers-are-authorized-like-message-senders
  (let [cfg {:telegram {:groups {group-id {:allowed-user-ids [owner-id]
                                           :respond-to :mention}}}}]
    (testing "a granted group user may click"
      (is (true? (guard/callback-allowed?
                  cfg {:id "Q1" :from {:id owner-id :is_bot false}
                       :message (forum-message 90 nil) :data "appr:x"}))))
    (testing "a bot may never click"
      (is (false? (guard/callback-allowed?
                   cfg {:id "Q2" :from {:id 777 :is_bot true}
                        :message (forum-message 90 nil) :data "appr:x"}))))
    (testing "an unlisted user in a gated group may not click"
      (is (false? (guard/callback-allowed?
                   cfg {:id "Q3" :from {:id 999 :is_bot false}
                        :message (forum-message 90 nil) :data "appr:x"}))))
    (testing "a DM clicker outside the DM allowlist may not click"
      (is (false? (guard/callback-allowed?
                   {:telegram {:allowed-user-ids [42]}}
                   {:id "Q4" :from {:id 999 :is_bot false}
                    :message {:message_id 5
                              :chat {:id 999 :type "private"}}
                    :data "appr:x"}))))))

(deftest approval-prompts-carry-inline-buttons
  (let [home (fs/create-temp-dir {:prefix "theseus-approvals-prompt-"})
        port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/sendMessage"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 90}})}
               {:status 404
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false})})))
         {:port port})]
    (try
      (with-redefs [config/home (fn [] (str home))]
        (telegram/send-approval-request!
         {:token "TESTTOKEN" :base-url (str "http://127.0.0.1:" port)}
         group-id topic-id
         {:approval/id approval-id :tool/name "shell"
          :tool/args {:cmd "cargo test"}})
        (let [send (->> @calls
                        (filter #(= "/botTESTTOKEN/sendMessage" (:uri %)))
                        first :body
                        (#(json/parse-string % keyword)))
              markup (json/parse-string (:reply_markup send) keyword)
              rows (:inline_keyboard markup)
              buttons (flatten rows)]
          (testing "the prompt is bound to the thread"
            (is (= topic-id (:message_thread_id send))))
          (testing "the keyboard offers approve, deny, approve-rest"
            (is (= 2 (count rows)))
            (is (= 3 (count buttons)))
            (is (= #{"appr:a1b2c3d4-0000-4000-8000-000000000001"
                     "deny:a1b2c3d4-0000-4000-8000-000000000001"
                     "apprrest:a1b2c3d4-0000-4000-8000-000000000001"}
                   (set (mapv :callback_data buttons))))
            (is (every? #(str/includes? % approval-id)
                        (mapv :callback_data buttons))))
          (testing "every button data parses back to its action"
            (is (= [:approve :deny :approve-rest]
                   (mapv #(-> % :callback_data telegram/parse-callback-data :action)
                         buttons))))
          (testing "the text names the tool and the id"
            (is (str/includes? (:text send) "shell"))
            (is (str/includes? (:text send) approval-id)))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest callback-queries-resolve-pending-approvals
  (let [home (fs/create-temp-dir {:prefix "theseus-approvals-e2e-"})
        port (free-port)
        calls (atom [])
        callback (fn [update-id query-id clicker data]
                   {:update_id update-id
                    :callback_query {:id query-id
                                     :from {:id clicker :is_bot false}
                                     :message (forum-message 90 nil)
                                     :data data}})
        updates [(callback 1 "CBQ-OK" owner-id (str "appr:" approval-id))
                 (callback 2 "CBQ-BAD" 999 (str "deny:" approval-id))]
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
               "/botTESTTOKEN/answerCallbackQuery"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result true})}
               "/botTESTTOKEN/editMessageText"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result true})}
               {:status 404
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false})})))
         {:port port})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "approvals.edn"))
            (pr-str {:pending {approval-id {:approval/id approval-id
                                            :session/id session-id
                                            :channel :telegram
                                            :tool/name "shell"
                                            :tool/args {:cmd "ls"}
                                            :created/at "2026-09-02T00:00:00Z"}}
                     :decisions {}
                     :approve-rest #{}}))
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))
            state (edn/read-string
                   (slurp (str (fs/path home "state" "approvals.edn"))))
            answers (->> @calls
                         (filter #(= "/botTESTTOKEN/answerCallbackQuery" (:uri %)))
                         (mapv #(json/parse-string (:body %) keyword)))
            edits (->> @calls
                       (filter #(= "/botTESTTOKEN/editMessageText" (:uri %)))
                       (mapv #(json/parse-string (:body %) keyword)))
            by-query (fn [q] (first (filter #(= q (:callback_query_id %)) answers)))]
        (testing "both callbacks were consumed"
          (is (= 2 (:updates result)))
          (is (false? (:conflict? result))))
        (testing "the authorized click resolves the pending approval"
          (let [answer (by-query "CBQ-OK")]
            (is (some? answer))
            (is (str/includes? (str/lower-case (str (:text answer))) "approved")))
          (is (not (contains? (:pending state) approval-id)))
          (is (= :approve (get-in state [:decisions approval-id])))
          (testing "the prompt is edited to the receipt with no keyboard"
            (is (= 1 (count edits)))
            (is (= group-id (:chat_id (first edits))))
            (is (= 90 (:message_id (first edits))))
            (is (str/includes? (:text (first edits)) approval-id))
            (is (= {} (:reply_markup (first edits))))))
        (testing "an unauthorized clicker gets a refusal and changes nothing"
          (let [answer (by-query "CBQ-BAD")]
            (is (some? answer))
            (is (str/includes? (str/lower-case (str (:text answer))) "not authorized")))
          (is (= :approve (get-in state [:decisions approval-id]))
              "the unauthorized deny must not overwrite the approval")))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest unknown-and-expired-callbacks-answer-honestly
  (let [home (fs/create-temp-dir {:prefix "theseus-approvals-expired-"})
        port (free-port)
        calls (atom [])
        callback (fn [update-id query-id clicker data]
                   {:update_id update-id
                    :callback_query {:id query-id
                                     :from {:id clicker :is_bot false}
                                     :message (forum-message 90 nil)
                                     :data data}})
        updates [(callback 1 "CBQ-EXPIRED" owner-id (str "appr:" approval-id))
                 (callback 2 "CBQ-GARBAGE" owner-id "release-the-crabs")]
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
               "/botTESTTOKEN/answerCallbackQuery"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result true})}
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
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [_ (with-redefs [config/home (fn [] (str home))]
                (telegram/poll-once!))
            answers (->> @calls
                         (filter #(= "/botTESTTOKEN/answerCallbackQuery" (:uri %)))
                         (mapv #(json/parse-string (:body %) keyword)))
            by-query (fn [q] (first (filter #(= q (:callback_query_id %)) answers)))]
        (testing "every callback is answered - no spinning clients"
          (is (= 2 (count answers))))
        (testing "an unknown or expired token says so truthfully"
          (is (str/includes? (str (:text (by-query "CBQ-EXPIRED")))
                             "No pending")))
        (testing "garbage data is named, not guessed"
          (let [answer (by-query "CBQ-GARBAGE")]
            (is (str/includes? (str (:text answer)) "Unsupported")))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest delivery-callback-helpers-ride-the-json-ladder
  (let [port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             {:status 200
              :headers {"content-type" "application/json"}
              :body (json/generate-string {:ok true :result true})}))
         {:port port})
        cfg {:token "TESTTOKEN" :base-url (str "http://127.0.0.1:" port)}]
    (try
      (delivery/answer-callback-query! cfg "CBQ-1" "Denied pending tool")
      (delivery/edit-message-text! cfg group-id 90 "Denied pending tool" {})
      (let [by-uri (fn [u] (->> @calls
                                (filter #(= u (:uri %)))
                                first
                                :body
                                (#(json/parse-string % keyword))))]
        (is (= {:callback_query_id "CBQ-1" :text "Denied pending tool"}
               (select-keys (by-uri "/botTESTTOKEN/answerCallbackQuery")
                            [:callback_query_id :text])))
        (let [edit (by-uri "/botTESTTOKEN/editMessageText")]
          (is (= group-id (:chat_id edit)))
          (is (= 90 (:message_id edit)))
          (is (= {} (:reply_markup edit)) "the keyboard must drop")))
      (finally
        (stop-server)))))