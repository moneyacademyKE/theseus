(ns e2e.telegram-attachment-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [bb-agent.telegram-attachment :as attachment]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(def group-id -1003995594829)
(def owner-id 1608111860)
(def topic-id 4721)

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- bot-info []
  {:id 8511646577 :username "eileenslybot" :is_bot true})

(defn- document-message
  [message-id user-id file-id caption]
  {:message_id message-id
   :message_thread_id topic-id
   :is_topic_message true
   :from {:id user-id
          :is_bot false
          :first_name (if (= owner-id user-id) "moe" "outsider")}
   :chat {:id group-id
          :type "supergroup"
          :title "Sly Theseus"
          :is_forum true}
   :caption caption
   :document {:file_id file-id
              :file_unique_id (str "unique-" file-id)
              :file_name "../quarterly report.txt"
              :mime_type "text/plain"
              :file_size 18}})

(deftest authorized-topic-attachment-is-persisted-and-denied-one-is-not-fetched
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-attachment-"})
        port (free-port)
        calls (atom [])
        updates [{:update_id 1
                  :message (document-message 11 owner-id "AUTHORIZED"
                                             "@eileenslybot inspect")}
                 {:update_id 2
                  :message (document-message 22 999 "DENIED"
                                             "@eileenslybot inspect")}]
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req)
                                :query (:query-string req)
                                :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result (bot-info)})}

               "/botTESTTOKEN/getUpdates"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result updates})}

               "/botTESTTOKEN/getFile"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:file_path "documents/report.txt"}})}

               "/file/botTESTTOKEN/documents/report.txt"
               {:status 200
                :headers {"content-type" "application/octet-stream"}
                :body "authorized payload"}

               "/botTESTTOKEN/sendMessage"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 90}})}

               {:status 404
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false})})))
         {:port port})
        config-file (fs/path home "config.edn")
        session-file (fs/path home "state" "sessions"
                              (str "telegram-" group-id "-topic-" topic-id ".edn"))]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-user-ids [owner-id]
                                :attachment-max-bytes 1024
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))
            get-file-calls (filter #(= "/botTESTTOKEN/getFile" (:uri %)) @calls)
            download-calls (filter #(str/starts-with? (:uri %) "/file/") @calls)
            send-body (->> @calls
                           (filter #(= "/botTESTTOKEN/sendMessage" (:uri %)))
                           first
                           :body
                           (#(json/parse-string % keyword)))
            saved-root (fs/path home "channel_attachments" "telegram"
                                (str group-id) (str "topic-" topic-id))
            saved-files (->> (fs/glob saved-root "**")
                             (filter fs/regular-file?)
                             vec)
            turn (-> session-file str slurp edn/read-string first)]
        (is (= 2 (:updates result)))
        (is (= 1 (count get-file-calls)) "denied attachment must not reach getFile")
        (is (not (str/includes? (str (get-in (first get-file-calls) [:query])) "DENIED")))
        (is (= "file_id=AUTHORIZED" (:query (first get-file-calls))))
        (is (= 1 (count download-calls)))
        (is (= 1 (count saved-files)))
        (is (= "authorized payload" (slurp (str (first saved-files)))))
        (is (not (str/includes? (str (fs/file-name (first saved-files))) "..")))
        (is (str/includes? (:user/input turn) (str (first saved-files))))
        (is (= topic-id (:message_thread_id send-body)))
        (is (= {:message_id 11} (:reply_parameters send-body))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest persist-batch-enforces-cumulative-turn-limit
  (let [home (fs/create-temp-dir {:prefix "theseus-batch-media-"})
        calls (atom [])
        transport (fn [url opts]
                    (swap! calls conj {:url url :opts opts})
                    (if (str/includes? url "getFile")
                      {:status 200
                       :body (json/generate-string
                              {:ok true :result {:file_path "doc/data.bin"}})}
                      {:status 200
                       :body (.getBytes "1234567890")}))
        cfg {:token "TESTTOKEN"
             :attachment-max-bytes 100
             :attachment-turn-max-bytes 25}
        make-msg (fn [id size]
                   {:chat {:id 100}
                    :document {:file_id (str "FILE-" id)
                               :file_unique_id (str "U-" id)
                               :file_name (str "doc" id ".bin")
                               :file_size size}})]
    (try
      (let [messages [(make-msg 1 10)
                      (make-msg 2 10)
                      (make-msg 3 10)] ;; total 30 > 25
            result (attachment/persist-batch! home cfg messages {:transport transport})]
        (is (= 2 (count (:persisted result))) "first two files fit in budget (20 <= 25)")
        (is (= 1 (:skipped result)) "third file exceeded budget and was skipped")
        (is (= 20 (:total-bytes result))))
      (finally
        (fs/delete-tree home)))))
