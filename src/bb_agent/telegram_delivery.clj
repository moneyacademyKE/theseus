(ns bb-agent.telegram-delivery
  "Checked Telegram sendMessage delivery.

   One result-aware ladder for every outbound reply: validate the Bot API
   response, retry only Retry-After (429) failures with a bounded attempt
   count and a strict inline wait cap, fall back once from a rejected HTML
   chunk to plain text, and surface terminal failures as data so callers
   never mistake silence for delivery. HTTP and sleep are injectable for
   deterministic tests."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [bb-agent.telegram-rich :as tr]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def max-attempts 3)
(def max-inline-wait-ms 30000)

(def ^:private default-runtime
  {:transport (fn [url request] (http/post url (assoc request :throw false)))
   :sleep-fn (fn [ms] (Thread/sleep (long ms)))})

(defn- safe-long
  [value]
  (cond
    (int? value) value
    (string? value) (try
                      (Long/parseLong (str/trim value))
                      (catch Exception _ nil))
    :else nil))

(defn- rate-limit?
  [failure]
  (and (= 429 (:status failure))
       (pos? (or (:retry-after failure) 0))))

(defn- deliver-failure
  [failure {:keys [chat-id thread-id attempts]}]
  (let [data {:telegram/delivery? true
              :failure/kind (if (rate-limit? failure) :rate-limited :terminal)
              :failure/status (:status failure)
              :failure/retry-after (:retry-after failure)
              :failure/description (:description failure)
              :chat-id chat-id
              :thread-id thread-id
              :attempts attempts}]
    (throw (ex-info (str "Telegram delivery failed: " (name (:failure/kind data)))
                    data))))

(defn- parse-response
  [{:keys [status body]}]
  (let [parsed (try
                 (json/parse-string body keyword)
                 (catch Exception _ nil))
        ok? (and (map? parsed)
                 (true? (:ok parsed))
                 (>= status 200)
                 (< status 300))]
    (if ok?
      {:ok true
       :message-id (get-in parsed [:result :message_id])}
      {:ok false
       :status status
       :retry-after (safe-long (get-in parsed [:parameters :retry_after]))
       :description (:description parsed)})))

(defn- request-body
  [chat-id text {:keys [parse-mode thread-id reply-to-message-id]}]
  (cond-> {:chat_id chat-id
           :text text}
    parse-mode (assoc :parse_mode parse-mode)
    thread-id (assoc :message_thread_id thread-id)
    reply-to-message-id (assoc :reply_parameters {:message_id reply-to-message-id})))

(defn- api-request-url
  [{:keys [base-url token]} method]
  (str (str/replace (or base-url "https://api.telegram.org") #"/+$" "")
       "/bot" token "/" method))

(defn- post-api
  "One result-aware ladder for any Bot API POST: validate the response,
   retry only Retry-After failures, throw structured terminal data.
   `request` is the raw http-client request map (JSON body or multipart)."
  [cfg method request {:keys [chat-id thread-id]} {:keys [transport sleep-fn]}]
  (loop [attempt 1]
    (let [failure (parse-response
                   (transport (api-request-url cfg method)
                              (assoc request :throw false)))]
      (cond
        (:ok failure)
        {:attempts attempt :message-id (:message-id failure)}

        (and (rate-limit? failure) (< attempt max-attempts))
        (do (sleep-fn (min (* 1000 (or (:retry-after failure) 5))
                           max-inline-wait-ms))
            (recur (inc attempt)))

        :else
        (deliver-failure failure
                         {:chat-id chat-id
                          :thread-id thread-id
                          :attempts attempt})))))

(defn- post-message
  [cfg chat-id text opts {:keys [transport sleep-fn]}]
  (post-api cfg "sendMessage"
            {:headers {"content-type" "application/json"}
             :body (json/generate-string
                    (request-body chat-id text opts))}
            {:chat-id chat-id :thread-id (:thread-id opts)}
            {:transport transport :sleep-fn sleep-fn}))

(def document-max-bytes 52428800)
(def photo-max-bytes 10485760)
(def max-caption-chars 1024)

(def ^:private kind-caps {:document document-max-bytes :photo photo-max-bytes})
(def ^:private kind-endpoints {:document "sendDocument" :photo "sendPhoto"})
(def ^:private kind-fields {:document "document" :photo "photo"})

(defn- validate-local-file
  [path kind max-bytes]
  (let [file (io/file (str path))]
    (if (or (str/blank? (str path))
            (not (fs/exists? file))
            (not (fs/regular-file? file)))
      (throw (ex-info (str "Telegram send-file: file not found: " (pr-str (str path)))
                      {:telegram/send-file? true :kind kind}))
      (let [size (fs/size file)]
        (when (> size max-bytes)
          (throw (ex-info (str "Telegram send-file exceeds the " (name kind)
                               " byte limit")
                          {:telegram/send-file? true
                           :file-size size
                           :max-bytes max-bytes})))
        file))))

(defn send-file!
  "Send one local file as a document or photo through the bounded ladder.
   Existence, size, and caption length are validated before any network
   call. Returns {:attempts n :message-id id} or throws structured
   delivery failure data."
  ([cfg chat-id path kind] (send-file! cfg chat-id path kind {}))
  ([cfg chat-id path kind
    {:keys [caption thread-id max-bytes transport sleep-fn]
     :or {max-bytes (get kind-caps kind)}
     :as opts}]
   (when-not (contains? kind-caps kind)
     (throw (ex-info (str "Telegram send-file: unsupported kind " (pr-str kind))
                     {:telegram/send-file? true :kind kind})))
   (when (and caption (> (count caption) max-caption-chars))
     (throw (ex-info (str "Telegram send-file caption exceeds "
                          max-caption-chars " characters")
                     {:telegram/send-file? true
                      :caption-chars (count caption)})))
   (let [file (validate-local-file path kind max-bytes)
         runtime (merge default-runtime (select-keys opts [:transport :sleep-fn]))
         parts (cond-> [{:name "chat_id" :content (str chat-id)}]
                 caption (conj {:name "caption" :content caption})
                 thread-id (conj {:name "message_thread_id"
                                  :content (str thread-id)})
                 :always (conj {:name (get kind-fields kind) :content file}))]
     (post-api cfg (get kind-endpoints kind)
               {:multipart parts}
               {:chat-id chat-id :thread-id thread-id}
               runtime))))

(def ^:private pre-code-re #"(?s)<(pre|code)[^>]*>(.*?)</\1>")
(def ^:private link-re #"(?s)<a\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>")
(def ^:private tag-re #"(?s)<[^>]+>")

(defn- preserve-pre-code
  [[_ _ body]]
  (str " " (str/trim body) " "))

(defn- preserve-link
  [[_ href body]]
  (str body " (" href ")"))

(defn to-plain
  "Strip Telegram HTML to safe plain text: pre/code keep spacing, links keep
   their target in parentheses, entities decode, tags drop. Pure."
  [html]
  (when (some? html)
    (let [decoded (-> html
                      (str/replace "&lt;" "<")
                      (str/replace "&gt;" ">")
                      (str/replace "&quot;" "\"")
                      (str/replace "&#39;" "'")
                      (str/replace "&amp;" "&"))
          stripped (-> decoded
                       (str/replace pre-code-re preserve-pre-code)
                       (str/replace link-re preserve-link)
                       (str/replace tag-re ""))]
      (str/trim (str/replace stripped #"\s+" " ")))))

(defn- markup-rejection?
  [error]
  (let [data (ex-data error)
        description (str/lower-case (or (:failure/description data) ""))]
    (and (= :terminal (:failure/kind data))
         (= 400 (:failure/status data))
         (or (str/includes? description "parse entities")
             (str/includes? description "can't parse")
             (str/includes? description "unsupported start tag")
             (str/includes? description "wrong tag")
             (str/includes? description "entity")))))

(defn- send-with-fallback
  [cfg chat-id text {:keys [parse-mode] :as opts} runtime]
  (try
    (post-message cfg chat-id text opts runtime)
    (catch Exception error
      (if (and (= "HTML" parse-mode) (markup-rejection? error))
        (post-message cfg chat-id (to-plain text)
                      (dissoc opts :parse-mode) runtime)
        (throw error)))))

(defn send-message!
  "Send one plain text message through the bounded ladder. Returns
   {:attempts n} or throws structured delivery failure data."
  ([cfg chat-id text] (send-message! cfg chat-id text {}))
  ([cfg chat-id text opts]
   (let [runtime (select-keys opts [:transport :sleep-fn])
         delivery-opts (apply dissoc opts (keys runtime))]
     (post-message cfg chat-id text delivery-opts
                   (merge default-runtime runtime)))))

(defn send-html!
  "Send an HTML message, split into Telegram-safe chunks. Every chunk rides
   the bounded ladder; a chunk rejected as markup falls back once to plain
   text through the same ladder. A terminal failure stops the sequence and
   throws. Returns delivered chunk results in order."
  ([cfg chat-id html opts]
   (let [runtime (select-keys opts [:transport :sleep-fn :split-fn])
         delivery-opts (apply dissoc opts (keys runtime))]
     (send-html! cfg chat-id html delivery-opts runtime)))
  ([cfg chat-id html
    {:keys [thread-id reply-to-message-id] :as opts}
    {:keys [split-fn] :or {split-fn tr/split-message} :as runtime}]
   (let [runtime (dissoc (merge default-runtime runtime) :split-fn)]
     (loop [[chunk & more] (split-fn html)
            index 0
            delivered []]
       (if chunk
         (let [result (send-with-fallback
                       cfg chat-id chunk
                       (assoc opts
                              :parse-mode "HTML"
                              :reply-to-message-id (when (zero? index)
                                                     reply-to-message-id))
                       runtime)]
           (recur more (inc index) (conj delivered result)))
         delivered)))))
