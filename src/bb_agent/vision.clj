(ns bb-agent.vision
  "Bounded image encoding for provider multimodal requests.

   Telegram persistence remains inert; this namespace is the only seam that
   reads an already-persisted image and turns it into provider data blocks."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def default-max-bytes (* 10 1024 1024))
(def max-max-bytes (* 20 1024 1024))

(def ^:private supported-mime-types
  #{"image/jpeg" "image/png" "image/gif" "image/webp"})

(defn- max-bytes
  [opts]
  (-> (long (or (:max-bytes opts) default-max-bytes))
      (max 1)
      (min max-max-bytes)))

(defn- image-mime
  [item]
  (some-> (or (:mime-type item)
             (when (= :photo (:kind item)) "image/jpeg"))
          str
          str/lower-case
          str/trim))

(defn- read-image
  [item opts]
  (let [path (:path item)
        limit (max-bytes opts)]
    (when-not (and path (fs/regular-file? path))
      (throw (ex-info "Vision image file not found"
                      {:vision/input? true
                       :path (str path)})))
    (let [size (long (fs/size path))
          mime (image-mime item)]
      (when (> size limit)
        (throw (ex-info "Vision image exceeds configured byte limit"
                        {:vision/input? true
                         :path (str path)
                         :bytes size
                         :max-bytes limit})))
      (when-not (contains? supported-mime-types mime)
        (throw (ex-info "Vision image has unsupported MIME type"
                        {:vision/input? true
                         :mime-type mime})))
      (let [bytes (java.nio.file.Files/readAllBytes (fs/path (str path)))]
        (when (> (alength bytes) limit)
          (throw (ex-info "Vision image exceeds configured byte limit"
                          {:vision/input? true
                           :path (str path)
                           :bytes (alength bytes)
                           :max-bytes limit})))
        {:mime-type mime
         :data (.encodeToString (java.util.Base64/getEncoder) bytes)}))))

(defn- user-message?
  [message]
  (contains? #{:user "user"} (:role message)))

(defn- content-blocks
  [style text encoded]
  (let [text-block (case style
                     :openai {"type" "text" "text" text}
                     :anthropic {:type "text" :text text}
                     (throw (ex-info "Unsupported vision wire style"
                                     {:vision/style style})))]
    (into [text-block]
          (map (fn [{:keys [mime-type data]}]
                 (case style
                   :openai {"type" "image_url"
                            "image_url" {"url" (str "data:" mime-type
                                                       ";base64," data)}}
                   :anthropic {:type "image"
                               :source {:type "base64"
                                        :media_type mime-type
                                        :data data}}))
               encoded))))

(defn attach-to-first-user
  "Attach bounded image blocks to the first user message.
   With no images, return the original message value unchanged."
  ([messages images style] (attach-to-first-user messages images style {}))
  ([messages images style opts]
   (if-not (seq images)
     messages
     (let [messages (vec messages)
           index (first (keep-indexed (fn [idx message]
                                        (when (user-message? message) idx))
                                      messages))]
       (when-not (some? index)
         (throw (ex-info "Vision request has no user message"
                         {:vision/input? true})))
       (let [message (nth messages index)
             text (str (or (:content message) ""))
             encoded (mapv #(read-image % opts) images)]
         (assoc messages index
                (assoc message :content (content-blocks style text encoded))))))))
