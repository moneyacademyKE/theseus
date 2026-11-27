(ns bb-agent.telegram-auto-register
  "Auto-register any Telegram group, supergroup, or channel where an existing
   authorized user adds/promotes the bot as administrator.
   Covers both:
   1. Live `my_chat_member` promotion updates from Telegram getUpdates.
   2. Pre-existing admin groups/channels on the next message from an existing
      authorized user (verified via Telegram `getChatMember`)."
  (:require [bb-agent.config :as config]
            [bb-agent.telegram-group :as group]
            [clojure.string :as str]))

(def ^:private admin-statuses #{"administrator" "creator"})
(def ^:private group-channel-types #{"group" "supergroup" "channel"})

(defn admin-status? [status]
  (contains? admin-statuses (some-> status str str/lower-case)))

(defn- member-id? [ids v]
  (boolean
   (and (some? v)
        (not (str/blank? (str v)))
        (some #(and (some? %) (= (str %) (str v))) ids))))

(defn existing-user?
  "True when `user-id` belongs to an already-authorized Telegram user in config."
  [cfg user-id]
  (member-id? (concat (get-in cfg [:telegram :allowed-user-ids])
                      (get-in cfg [:telegram :allowed-chat-ids]))
              user-id))

(defn eligible-admin-event?
  "True when a `my_chat_member` payload represents an existing non-bot user
   adding/promoting the bot to administrator in a group, supergroup, or channel."
  [cfg my-chat-member]
  (let [chat-type (get-in my-chat-member [:chat :type])
        chat-id (get-in my-chat-member [:chat :id])
        actor-id (get-in my-chat-member [:from :id])
        actor-bot? (true? (get-in my-chat-member [:from :is_bot]))
        new-status (get-in my-chat-member [:new_chat_member :status])]
    (boolean
     (and (some? chat-id)
          (contains? group-channel-types chat-type)
          (not actor-bot?)
          (existing-user? cfg actor-id)
          (admin-status? new-status)))))

(defn register-chat!
  "Idempotently register `chat-id` in [:telegram :groups chat-id] and persist
   to config.edn. Returns {:registered? bool :already-registered? bool :chat-id id :cfg cfg*}."
  [cfg chat-id]
  (if (group/group-config cfg chat-id)
    {:registered? false :already-registered? true :chat-id chat-id :cfg cfg}
    (let [disk-cfg (or (try (config/load-config) (catch Exception _ nil)) cfg)
          allowed (vec (or (seq (get-in disk-cfg [:telegram :allowed-user-ids]))
                           (seq (get-in disk-cfg [:telegram :allowed-chat-ids]))
                           []))
          entry {:allowed-user-ids allowed
                 :open true
                 :respond-to :all}
          updated (assoc-in disk-cfg [:telegram :groups chat-id] entry)]
      (config/write-config! updated)
      {:registered? true
       :already-registered? false
       :chat-id chat-id
       :cfg (assoc-in cfg [:telegram :groups chat-id] entry)})))

(defn handle-my-chat-member!
  "Process a Telegram `my_chat_member` update. Auto-registers the chat when an
   existing authorized user adds/promotes the bot as admin."
  [cfg my-chat-member]
  (if-not (eligible-admin-event? cfg my-chat-member)
    {:registered? false :already-registered? false :cfg cfg}
    (register-chat! cfg (get-in my-chat-member [:chat :id]))))

(defn maybe-register-on-message!
  "Fallback for groups/channels where the bot was ALREADY added as admin before
   `my_chat_member` tracking was enabled. When an existing authorized user sends
   a message in an unregistered group/channel, verifies bot admin status via
   `(get-chat-member-fn chat-id bot-id)` and auto-registers on the spot.
   Returns the (possibly updated) cfg."
  [cfg bot message get-chat-member-fn]
  (let [chat-id (get-in message [:chat :id])
        actor-id (get-in message [:from :id])
        actor-bot? (true? (get-in message [:from :is_bot]))
        bot-id (:id bot)]
    (if (and chat-id
             bot-id
             (group/group-chat? message)
             (nil? (group/group-config cfg chat-id))
             (not actor-bot?)
             (existing-user? cfg actor-id))
      (let [member (try (get-chat-member-fn chat-id bot-id)
                        (catch Exception _ nil))]
        (if (admin-status? (:status member))
          (:cfg (register-chat! cfg chat-id))
          cfg))
      cfg)))
