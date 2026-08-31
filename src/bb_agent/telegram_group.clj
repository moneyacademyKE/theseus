(ns bb-agent.telegram-group
  "Pure Telegram chat, forum-topic, and group-addressing rules."
  (:require [clojure.string :as str]))

(defn group-chat?
  [message]
  (contains? #{"group" "supergroup" "channel"}
             (get-in message [:chat :type])))

(defn topic-id
  "A thread isolates a session only when Telegram marks it as a genuine
   forum-topic message. Plain reply threads and General return nil."
  [message]
  (when (true? (:is_topic_message message))
    (:message_thread_id message)))

(defn session-id
  [message]
  (let [chat-id (get-in message [:chat :id])]
    (str "telegram-" chat-id
         (when-let [thread-id (topic-id message)]
           (str "-topic-" thread-id)))))

(defn group-config
  [cfg chat-id]
  (let [groups (get-in cfg [:telegram :groups])]
    (or (get groups chat-id)
        (get groups (str chat-id)))))

(defn respond-mode
  [cfg chat-id]
  (or (:respond-to (group-config cfg chat-id))
      (get-in cfg [:telegram :respond-to])
      :mention))

(defn- mentions
  [text]
  (->> (re-seq #"(?i)@[A-Z0-9_]{5,}" (or text ""))
       (map #(-> % (subs 1) str/lower-case))
       set))

(defn- bot-username
  [bot]
  (some-> (:username bot) (str/replace #"^@" "") str/lower-case))

(defn- mentioned?
  [bot text]
  (contains? (mentions text) (bot-username bot)))

(defn- mentions-other-bot?
  [bot text]
  (let [ours (bot-username bot)]
    (boolean
     (some #(and (not= % ours) (str/ends-with? % "bot"))
           (mentions text)))))

(defn- replied-to-bot?
  [bot message]
  (let [sender (get-in message [:reply_to_message :from])]
    (or (= (:id bot) (:id sender))
        (and (:is_bot sender)
             (= (bot-username bot)
                (some-> (:username sender) str/lower-case))))))

(defn should-respond?
  "DMs keep their existing behavior. Groups require an authorized human and,
   by default, a direct mention or reply to this bot. `:all` is opt-in."
  [cfg bot message]
  (if-not (group-chat? message)
    true
    (let [text (:text message)
          sender-bot? (true? (get-in message [:from :is_bot]))
          own-mention? (mentioned? bot text)
          reply-to-us? (replied-to-bot? bot message)
          addressed? (or own-mention?
                         (and reply-to-us?
                              (not (mentions-other-bot? bot text))))]
      (and (not sender-bot?)
           (case (respond-mode cfg (get-in message [:chat :id]))
             :all true
             :mention addressed?
             :auto addressed?
             false)))))

(defn normalize-command
  "Remove Telegram's `@our_bot` suffix from a command, leaving commands aimed
   at other bots untouched."
  [text bot]
  (let [text (or text "")
        [_ command target tail] (re-matches #"^(/[^@\s]+)@([^\s]+)(.*)$" text)]
    (if (and command
             (= (some-> target str/lower-case)
                (bot-username bot)))
      (str command tail)
      text)))

(defn- sender-label
  [message]
  (let [{:keys [id first_name last_name username]} (:from message)
        name (str/trim (str (or first_name "unknown")
                            (when (seq last_name) (str " " last_name))))]
    (str name
         (when (seq username) (str " (@" username ")"))
         ", ID " id)))

(defn- reply-label
  [bot reply]
  (if (or (= (:id bot) (get-in reply [:from :id]))
          (and (get-in reply [:from :is_bot])
               (= (bot-username bot)
                  (some-> (get-in reply [:from :username]) str/lower-case))))
    "assistant"
    (sender-label reply)))

(defn- bounded
  [text]
  (let [text (str/trim (or text ""))]
    (if (> (count text) 2000)
      (str (subs text 0 2000) "…")
      text)))

(defn agent-input
  "Make shared-chat identity and reply context explicit to the agent."
  [bot message]
  (if-not (group-chat? message)
    (:text message)
    (let [chat-title (or (get-in message [:chat :title]) "unknown")
          thread-id (topic-id message)
          reply (:reply_to_message message)
          reply-text (bounded (or (:text reply) (:caption reply)))
          header (str "[Telegram group \"" chat-title "\""
                      (when thread-id (str " — topic " thread-id))
                      " — from " (sender-label message) "]")
          context (when (seq reply-text)
                    (str "[Replying to " (reply-label bot reply)
                         ": \"" reply-text "\"]\n"))]
      (str header "\n" context (or (:text message) "")))))
