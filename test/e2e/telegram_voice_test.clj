(ns e2e.telegram-voice-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [bb-agent.telegram-voice :as voice]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(deftest transcription-is-honest-in-every-path
  (testing "no speech-to-text tool means no transcription and a reason"
    (is (= {:transcribed? false :reason :no-stt-tool}
           (voice/transcribe {:path "/tmp/clip.ogg"}
                             {:resolve-bin (constantly nil)}))))

  (testing "a configured binary that prints text yields the transcript"
    (let [result (voice/transcribe {:path "/tmp/clip.ogg"}
                                   {:resolve-bin (constantly "/usr/local/bin/whisper")
                                    :exec (fn [bin args]
                                            (is (= "/usr/local/bin/whisper" bin))
                                            (is (= ["/tmp/clip.ogg"] args))
                                            {:exit 0 :out "hello from the audio"})})]
      (is (true? (:transcribed? result)))
      (is (= "hello from the audio" (:text result)))))

  (testing "a failing binary reports failure with the stderr tail"
    (let [result (voice/transcribe {:path "/tmp/clip.ogg"}
                                   {:resolve-bin (constantly "/usr/local/bin/whisper")
                                    :exec (fn [_bin _args]
                                            {:exit 1 :out "" :err "cannot decode input"})})]
      (is (false? (:transcribed? result)))
      (is (= :failed (:reason result)))
      (is (str/includes? (:detail result) "cannot decode input"))))

  (testing "transcripts are bounded"
    (let [result (voice/transcribe {:path "/tmp/clip.ogg"}
                                   {:resolve-bin (constantly "/bin/echo-thing")
                                    :max-chars 10
                                    :exec (fn [_bin _args]
                                            {:exit 0 :out (apply str (repeat 100 "x"))})})]
      (is (true? (:transcribed? result)))
      (is (= 10 (count (:text result))))))

  (testing "audio exceeding max duration is honestly declined before execution"
    (let [exec-called? (atom false)
          result (voice/transcribe {:path "/tmp/clip.ogg" :duration 180}
                                   {:resolve-bin (constantly "/bin/echo-thing")
                                    :max-duration-secs 120
                                    :exec (fn [_ _] (reset! exec-called? true))})]
      (is (false? (:transcribed? result)))
      (is (= :duration-exceeded (:reason result)))
      (is (= 180 (:duration result)))
      (is (= 120 (:max-duration-secs result)))
      (is (false? @exec-called?) "exec is not even spawned when duration exceeds limit"))
    (let [ann (voice/annotation {:stt-max-duration-secs 60}
                                {:path "/tmp/clip.ogg" :duration 90})]
      (is (str/includes? ann "duration exceeds limit (90s > 60s)"))
      (is (str/includes? ann "audio saved but not transcribed")))))

(def group-id -1003995594829)
(def owner-id 1608111860)
(def topic-id 4721)

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- bot-info []
  {:id 8511646577 :username "eileenslybot" :is_bot true})

(deftest voice-note-reaches-the-turn-as-honest-context
  (let [home (fs/create-temp-dir {:prefix "theseus-telegram-voice-"})
        port (free-port)
        calls (atom [])
        updates [{:update_id 1
                  :message {:message_id 10
                            :message_thread_id topic-id
                            :is_topic_message true
                            :from {:id owner-id :is_bot false :first_name "moe"}
                            :chat {:id group-id :type "supergroup"
                                   :title "Sly Theseus" :is_forum true}
                            :caption "@eileenslybot what does he say"
                            :voice {:file_id "VOICE1"
                                    :file_unique_id "VU"
                                    :mime_type "audio/ogg"
                                    :file_size 900}}}]
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
               "/botTESTTOKEN/getFile"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:file_path "voice/clip.ogg"}})}
               "/file/botTESTTOKEN/voice/clip.ogg"
               {:status 200
                :headers {"content-type" "audio/ogg"}
                :body "OGGDATA"}
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
                                :stt-bin ""
                                :attachment-max-bytes 10240
                                :groups {group-id {:allowed-user-ids [owner-id]
                                                   :respond-to :mention}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))
            turns (edn/read-string (slurp (str session-file)))
            input (:user/input (first turns))
            saved-root (fs/path home "channel_attachments" "telegram"
                                (str group-id) (str "topic-" topic-id))
            saved-files (->> (fs/glob saved-root "**")
                             (filter fs/regular-file?)
                             vec)]
        (is (= 1 (:updates result)))
        (is (= 1 (count saved-files)) "the voice bytes persist like any attachment")
        (is (str/includes? input "[Voice note")
            "the turn must be told what the voice note is")
        (is (str/includes? input (str (first saved-files)))
            "the turn must know where the audio lives")
        (is (or (str/includes? input "not transcribed")
                (str/includes? input "transcript"))
            "either a transcript or the honest no-STT note must be present"))
      (finally
        (stop-server)
        (fs/delete-tree home)))))
