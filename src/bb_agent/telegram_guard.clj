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

(defn- authorize
  "One policy for every actor: global users anywhere, per-group users in
   that group, open groups only when operators are configured, bots never."
  [cfg {:keys [user-id chat-id bot? group?]}]
  (let [global? (global-user? cfg user-id)
        group-cfg (group/group-config cfg chat-id)
        group-user? (member? (:allowed-user-ids group-cfg) user-id)
        configured? (seq (concat (get-in cfg [:telegram :allowed-user-ids])
                                 (get-in cfg [:telegram :allowed-chat-ids])))]
    (boolean
     (and (not bot?)
          (if group?
            (or global?
                group-user?
                (and configured? (:open group-cfg)))
            (or global?
                (allowed? cfg chat-id)))))))

(defn message-allowed?
  "Authorize the sender in context. Global users may use DMs and groups.
   Per-group users and open-group members gain access only in that group.
   Open mode is inert until at least one global operator is configured.
   Messages from bots are never authorized."
  [cfg message]
  (authorize cfg
             {:user-id (get-in message [:from :id])
              :chat-id (get-in message [:chat :id])
              :bot? (true? (get-in message [:from :is_bot]))
              :group? (group/group-chat? message)}))

(defn callback-allowed?
  "Authorize a callback_query by its clicker. The embedded message supplies
   chat context only; the actor is always callback/from."
  [cfg callback]
  (authorize cfg
             {:user-id (get-in callback [:from :id])
              :chat-id (get-in callback [:message :chat :id])
              :bot? (true? (get-in callback [:from :is_bot]))
              :group? (group/group-chat? (:message callback))}))
