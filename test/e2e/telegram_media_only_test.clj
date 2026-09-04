(ns e2e.telegram-media-only-test
  "Media-only messages (no text, no caption) must persist and run a turn.
   Regression guard: these were silently consumed before bk-f48b."
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

(defn- bot-info []
  {:id 8511646577 :username "eileenslybot" :is_bot true})

(def owner-id 1608111860)

(defn- dm-message
  [update-id message-id media-key media]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id owner-id :is_bot false :first_name "moe"}
             :chat {:id owner-id :type "private"}
             media-key media}})

(defn- run-media-only-poll
  [updates]
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-media-only-"})
        port (free-port)
        calls (atom [])
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
                :body (json/generate-string {:ok true :result updates})}
               "/botTESTTOKEN/getFile"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:file_path "media/probe.bin"}})}
               "/file/botTESTTOKEN/media/probe.bin"
               {:status 200 :headers {"content-type" "application/octet-stream"}
                :body "MEDIABYTES"}
               "/botTESTTOKEN/sendMessage"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 90}})}
               {:status 404 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false})})))
         {:port port})
        config-file (fs/path home "config.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :attachment-max-bytes 10240
                                :allowed-user-ids [owner-id]}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))
            session-file (->> (fs/glob (fs/path home "state" "sessions") "*.edn")
                              (filter fs/regular-file?)
                              first)]
        (assoc result
               :calls @calls
               :session-file session-file
               :home (str home)))
      (finally
        (stop-server)))))

(defn- sent-messages
  [result]
  (->> (:calls result)
       (filter #(str/includes? (:uri %) "sendMessage"))
       (mapv #(json/parse-string (:body %) keyword))))

(defn- turn-input
  [result]
  (when-let [session-file (:session-file result)]
    (when (fs/exists? session-file)
      (let [turns (edn/read-string (slurp (str session-file)))]
        (:user/input (first turns))))))

(deftest captionless-photo-runs-a-turn-and-persists
  (let [result (run-media-only-poll
                [(dm-message 1 10 :photo
                             [{:file_id "PH-S" :file_unique_id "PH-SU"
                               :width 90 :height 90 :file_size 100}
                              {:file_id "PH-L" :file_unique_id "PH-LU"
                               :width 1280 :height 800 :file_size 5000}])])
        saved (->> (fs/glob (fs/path (:home result) "channel_attachments") "**")
                   (filter fs/regular-file?)
                   vec)
        input (turn-input result)]
    (testing "the update is consumed"
      (is (= 1 (:updates result))))
    (testing "the photo persists durably"
      (is (= 1 (count saved)) "expected exactly one persisted attachment file"))
    (testing "a turn ran and the reply was delivered"
      (is (seq (sent-messages result)) "expected sendMessage calls")
      (let [reply (first (sent-messages result))]
        (is (= {:message_id 10} (:reply_parameters reply))
            "reply should quote the photo message")))
    (testing "the session records the turn with the persisted path"
      (is (some? input) "expected a recorded turn")
      (is (every? #(str/includes? input (str %)) saved)))))

(deftest voice-note-runs-a-turn-with-honest-annotation
  (let [result (run-media-only-poll
                [(dm-message 1 10 :voice
                             {:file_id "VO-1" :file_unique_id "VO-U"
                              :mime_type "audio/ogg" :file_size 2048})])
        saved (->> (fs/glob (fs/path (:home result) "channel_attachments") "**")
                   (filter fs/regular-file?)
                   vec)
        input (turn-input result)]
    (testing "the update is consumed"
      (is (= 1 (:updates result))))
    (testing "the voice note persists"
      (is (= 1 (count saved)) "expected exactly one persisted voice file"))
    (testing "a turn ran; the reply was delivered"
      (is (seq (sent-messages result)) "expected sendMessage calls"))
    (testing "the session records the turn with an attachment annotation"
      (is (some? input) "expected a recorded turn")
      (is (str/includes? input "[Telegram attachment")
          "expected the attachment envelope in the turn input")
      (is (every? #(str/includes? input (str %)) saved)))))
