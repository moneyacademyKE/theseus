(ns e2e.telegram-media-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [bb-agent.telegram-media :as media]
            [bb-agent.telegram-notes :as notes]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(deftest batches-preserve-order-and-coalesce-albums
  (let [updates [{:update_id 10
                  :message {:chat {:id 7} :media_group_id "album-a" :message_id 1}}
                 {:update_id 11
                  :message {:chat {:id 7} :media_group_id "album-b" :message_id 2}}
                 {:update_id 12
                  :message {:chat {:id 7} :media_group_id "album-a" :message_id 3}}
                 {:update_id 13
                  :message {:chat {:id 8} :media_group_id "album-a" :message_id 4}}
                 {:update_id 14
                  :message {:chat {:id 7} :message_id 5}}]
        batches (media/batches updates)]
    (is (= [[10 12] [11] [13] [14]]
           (mapv #(mapv :update_id (:updates %)) batches)))
    (is (= [1 3] (mapv :message_id (:messages (first batches)))))
    (is (true? (:album? (first batches))))
    (is (not (:album? (last batches))))))

(deftest edited-updates-are-classified-without-being-message-batches
  (let [edited {:update_id 20
                :edited_message {:chat {:id 7}
                                 :message_id 9
                                 :text "new text"}}]
    (is (= :edited (media/update-kind edited)))
    (is (empty? (media/batches [edited])))))

(deftest edit-notes-are-bounded-and-consumed-once
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-notes-"})
        message {:chat {:id 7 :type "private"}
                 :from {:id 42 :first_name "Ada"}
                 :message_id 9
                 :edit_date 123
                 :text (apply str (repeat 3000 "x"))}]
    (try
      (is (= [] (notes/take! home "telegram-7")))
      (notes/add! home "telegram-7" message)
      (let [taken (notes/take! home "telegram-7")]
        (is (= 1 (count taken)))
        (is (< (count (first taken)) 2100))
        (is (clojure.string/includes? (first taken) "edited")))
      (is (= [] (notes/take! home "telegram-7")))
      (finally
        (fs/delete-tree home)))))

(def group-id -1003995594829)
(def owner-id 1608111860)
(def topic-id 4721)

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

(deftest album-is-one-turn-media-persists-and-edits-become-notes
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-media-e2e-"})
        port (free-port)
        calls (atom [])
        photo-message (fn [message-id file-id caption]
                        (forum-message message-id
                                       {:caption caption
                                        :media_group_id "probe-album"
                                        :photo [{:file_id (str file-id "-small")
                                                 :file_unique_id (str file-id "-su")
                                                 :width 90 :height 90 :file_size 100}
                                                {:file_id file-id
                                                 :file_unique_id (str file-id "-u")
                                                 :width 1280 :height 800
                                                 :file_size 5000}]}))
        updates [{:update_id 1
                  :edited_message (forum-message 5 {:edit_date 99
                                                    :text "the edited correction"})}
                 {:update_id 2
                  :message (photo-message 10 "PHOTA" "@eileenslybot inspect")}
                 {:update_id 3
                  :message (photo-message 11 "PHOTB" nil)}]
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
                       {:ok true :result {:file_path "photos/big.jpg"}})}

               "/file/botTESTTOKEN/photos/big.jpg"
               {:status 200
                :headers {"content-type" "image/jpeg"}
                :body "JPGDATA"}

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
                                :attachment-max-bytes 10240
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))
            saved-root (fs/path home "channel_attachments" "telegram"
                                (str group-id) (str "topic-" topic-id))
            saved-files (->> (fs/glob saved-root "**")
                             (filter fs/regular-file?)
                             vec)
            turns (edn/read-string (slurp (str session-file)))
            input (:user/input (first turns))]
        (is (= 3 (:updates result)))
        (is (= 1 (count turns)) "an album must be exactly one agent turn")
        (is (= 2 (count saved-files)) "captionless album members still persist")
        (is (str/includes? input "[Telegram edited message from moe #5]"))
        (is (str/includes? input "the edited correction"))
        (is (every? #(str/includes? input %) (mapv str saved-files)))
        (let [send (->> @calls
                        (filter #(= "/botTESTTOKEN/sendMessage" (:uri %)))
                        first
                        :body
                        (#(json/parse-string % keyword)))]
          (is (= topic-id (:message_thread_id send)))
          (is (= {:message_id 10} (:reply_parameters send)))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))
