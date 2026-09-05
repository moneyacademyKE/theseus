(ns bb-agent.telegram-attachment
  "Durable storage for authorized inbound Telegram documents and media.

   This module only persists bytes and returns inert metadata. Authorization
   remains in the caller, before any getFile or download request. File names
   are reduced to a basename and prefixed with stable Telegram identity so a
   sender cannot escape the attachment root or overwrite another message."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-max-bytes (* 20 1024 1024))

(defn- api-root
  [{:keys [base-url]}]
  (str/replace (or base-url "https://api.telegram.org") #"/+$" ""))

(defn- file-root
  [cfg]
  (or (:file-base-url cfg) (api-root cfg)))

(defn- checked-json
  [response operation]
  (let [body (try
               (json/parse-string (:body response) keyword)
               (catch Exception _ nil))]
    (when-not (and (<= 200 (:status response) 299)
                   (true? (:ok body)))
      (throw (ex-info (str "Telegram " operation " failed")
                      {:telegram/attachment? true
                       :operation operation
                       :status (:status response)
                       :description (:description body)})))
    body))

(defn- document-entry
  [message]
  (when-let [document (:document message)]
    {:kind :document
     :file-id (:file_id document)
     :unique-id (:file_unique_id document)
     :file-name (:file_name document)
     :mime-type (:mime_type document)
     :file-size (:file_size document)}))

(defn- sized-photo
  [message]
  (when-let [photo (last (sort-by #(or (:file_size %) 0) (:photo message)))]
    {:kind :photo
     :file-id (:file_id photo)
     :unique-id (:file_unique_id photo)
     :file-name "photo.jpg"
     :mime-type "image/jpeg"
     :file-size (:file_size photo)}))

(defn- media-entry
  [message key kind default-name]
  (when-let [media (get message key)]
    (cond-> {:kind kind
             :file-id (:file_id media)
             :unique-id (:file_unique_id media)
             :file-name (or (:file_name media) default-name)
             :mime-type (:mime_type media)
             :file-size (:file_size media)}
      (:duration media) (assoc :duration (:duration media)))))

(defn attachment
  "Return one normalized attachment descriptor from a Telegram message.
   Documents are preferred, then the largest photo, video, audio, voice,
   animation, or video note. Content remains inert."
  [message]
  (or (document-entry message)
      (sized-photo message)
      (media-entry message :video :video "video.mp4")
      (media-entry message :audio :audio "audio.bin")
      (media-entry message :voice :voice "voice.ogg")
      (media-entry message :animation :animation "animation.bin")
      (media-entry message :video_note :video-note "video-note.mp4")))

(defn- safe-file-name
  [{:keys [file-name unique-id file-id]}]
  (let [base (some-> (or file-name "attachment.bin") fs/file-name str)
        base (str/replace base #"[^A-Za-z0-9._ -]" "_")
        prefix (str/replace (or unique-id file-id "file") #"[^A-Za-z0-9_-]" "_")]
    (str prefix "-" (if (str/blank? base) "attachment.bin" base))))

(defn- target-dir
  [home message]
  (let [chat-id (get-in message [:chat :id])
        thread-id (when (true? (:is_topic_message message))
                    (:message_thread_id message))]
    (cond-> (fs/path home "channel_attachments" "telegram" (str chat-id))
      thread-id (fs/path (str "topic-" thread-id)))))

(defn- allowed-remote-path
  [path]
  (let [path (str path)]
    (when (or (str/blank? path)
              (str/starts-with? path "/")
              (str/includes? path "..")
              (str/includes? path "\\"))
      (throw (ex-info "Telegram returned an unsafe attachment path"
                      {:telegram/attachment? true
                       :operation "getFile"})))
    path))

(defn- get-file-path
  [cfg file-id transport]
  (-> (transport (str (api-root cfg) "/bot" (:token cfg) "/getFile")
                 {:throw false
                  :query-params {:file_id file-id}
                  :headers {"accept" "application/json"}})
      (checked-json "getFile")
      (get-in [:result :file_path])
      allowed-remote-path))

(defn persist!
  "Download one attachment under home/channel_attachments/telegram after
   the caller authorizes the message. Returns inert metadata, or nil when
   no supported attachment exists."
  ([home cfg message] (persist! home cfg message {}))
  ([home cfg message {:keys [transport]
                      :or {transport http/get}}]
   (when-let [{:keys [file-id file-size] :as item} (attachment message)]
     (let [max-bytes (or (:attachment-max-bytes cfg) default-max-bytes)]
       (when (and file-size (> file-size max-bytes))
         (throw (ex-info "Telegram attachment exceeds configured byte limit"
                         {:telegram/attachment? true
                          :file-size file-size
                          :max-bytes max-bytes})))
       (let [remote-path (get-file-path cfg file-id transport)
             response (transport (str (file-root cfg) "/file/bot" (:token cfg)
                                      "/" remote-path)
                                 {:throw false :as :bytes})
             bytes (:body response)]
         (when-not (<= 200 (:status response) 299)
           (throw (ex-info "Telegram attachment download failed"
                           {:telegram/attachment? true
                            :status (:status response)})))
         (when (> (alength bytes) max-bytes)
           (throw (ex-info "Telegram attachment exceeds configured byte limit"
                           {:telegram/attachment? true
                            :file-size (alength bytes)
                            :max-bytes max-bytes})))
         (let [dir (target-dir home message)
               path (fs/path dir (safe-file-name item))]
           (fs/create-dirs dir)
           (fs/write-bytes path bytes)
           (assoc item
                  :path (str path)
                  :bytes (alength bytes)
                  :chat-id (get-in message [:chat :id])
                  :thread-id (when (true? (:is_topic_message message))
                               (:message_thread_id message)))))))))

(defn persistable?
  "True when the message carries media that persist! can store (any of the
   normalized kinds: document, photo, video, audio, voice, animation, note)."
  [message]
  (some? (attachment message)))
