(ns bb-agent.telegram-guard
  "Pure Telegram authorization. DMs and groups deliberately use different
   keys: a chat identifies the conversation; a user identifies the actor."
  (:require [bb-agent.telegram-group :as group]))

(defn- member?
  [ids value]
  (boolean
   (some #(= (str %) (str value)) ids)))

(defn allowed?
  "Legacy DM allowlist check retained for config compatibility."
  [cfg chat-id]
  (member? (get-in cfg [:telegram :allowed-chat-ids]) chat-id))

(defn- global-user?
  [cfg user-id]
  (member? (concat (get-in cfg [:telegram :allowed-user-ids])
                   (get-in cfg [:telegram :allowed-chat-ids]))
           user-id))

(defn message-allowed?
  "Authorize the sender in context. Global users may use DMs and groups.
   Per-group users and open-group members gain access only in that group.
   Open mode is inert until at least one global operator is configured.
   Messages from bots are never authorized."
  [cfg message]
  (let [chat-id (get-in message [:chat :id])
        user-id (get-in message [:from :id])
        group? (group/group-chat? message)
        sender-bot? (true? (get-in message [:from :is_bot]))
        global? (global-user? cfg user-id)
        group-cfg (group/group-config cfg chat-id)
        group-user? (member? (:allowed-user-ids group-cfg) user-id)
        configured? (seq (concat (get-in cfg [:telegram :allowed-user-ids])
                                 (get-in cfg [:telegram :allowed-chat-ids])))]
    (boolean
     (and (not sender-bot?)
          (if group?
            (or global?
                group-user?
                (and configured? (:open group-cfg)))
            (or global?
                (allowed? cfg chat-id)))))))
