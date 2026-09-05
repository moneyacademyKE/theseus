(ns bb-agent.telegram-presence
  "Fire-and-forget Telegram presence signals: the typing indicator and the
   message reaction ack.

   Enhancers only — a rejected or failing signal is surfaced as data
   ({:ok bool :status n :description s}) and never blocks the turn it
   decorates. HTTP is injectable for deterministic tests."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-ack-emoji "👌")

(defn- api-url
  [{:keys [base-url token]} method]
  (str (str/replace (or base-url "https://api.telegram.org") #"/+$" "")
       "/bot" token "/" method))

(defn- parse-response
  [status body]
  (let [parsed (try
                 (json/parse-string body keyword)
                 (catch Exception _ nil))]
    (merge {:ok (and (map? parsed)
                     (true? (:ok parsed))
                     (>= status 200)
                     (< status 300))
            :status status}
           (select-keys parsed [:description]))))

(defn send-signal!
  "POST one presence signal to the Bot API. Returns response data and
   never throws: network failures collapse into {:ok false :error msg}."
  ([cfg method body] (send-signal! cfg method body {}))
  ([cfg method body {:keys [transport]}]
   (try
     (let [post (or transport
                    (fn [url request]
                      (http/post url (assoc request :throw false))))
           {:keys [status body]} (post (api-url cfg method)
                                       {:headers {"content-type" "application/json"}
                                        :body (json/generate-string body)})]
       (parse-response status body))
     (catch Exception e
       {:ok false :status nil :error (.getMessage e)}))))

(defn typing!
  "Announce typing for chat-id, routed to thread-id when present."
  ([cfg chat-id] (typing! cfg chat-id {}))
  ([cfg chat-id {:keys [thread-id] :as opts}]
   (send-signal! cfg "sendChatAction"
                 (cond-> {:chat_id chat-id :action "typing"}
                   thread-id (assoc :message_thread_id thread-id))
                 opts)))

(defn with-typing-heartbeat
  "Run f while refreshing the typing indicator every :typing-heartbeat-ms
   (default 4000). Telegram's typing TTL is ~5s, so a one-shot indicator
   dies on any real turn. Fire-and-forget like every presence signal: a
   failing beat never touches the turn, and the loop stops within ~100ms
   of f returning."
  [cfg chat-id opts f]
  (if-not (:typing-indicator cfg true)
    (f)
    (let [stop (atom false)
          interval (long (or (:typing-heartbeat-ms cfg) 4000))
          beat (future
                 (while (not @stop)
                   (typing! cfg chat-id opts)
                   (let [deadline (+ (System/currentTimeMillis) interval)]
                     (while (and (not @stop)
                                 (< (System/currentTimeMillis) deadline))
                       (Thread/sleep 100)))))]
      (try
        (f)
        (finally
          (reset! stop true)
          (deref beat 2000 nil))))))

(defn reaction!
  "Ack a received message with the ack emoji (or opts emoji)."
  ([cfg chat-id message-id] (reaction! cfg chat-id message-id {}))
  ([cfg chat-id message-id {:keys [emoji] :as opts}]
   (send-signal! cfg "setMessageReaction"
                 {:chat_id chat-id
                  :message_id message-id
                  :reaction [{:type "emoji" :emoji (or emoji default-ack-emoji)}]}
                 opts)))
