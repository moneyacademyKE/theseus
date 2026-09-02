(ns bb-agent.telegram-media
  "Pure classification and batching for Telegram update envelopes.

   Telegram albums are one user event represented by several updates. This
   namespace groups only ordinary message updates; edited messages remain
   separate context notes and are never turned into an automatic agent turn."
  (:require [clojure.string :as str]))

(defn update-kind
  "Classify the update payload without interpreting its text."
  [update]
  (cond
    (:message update) :message
    (:edited_message update) :edited
    (:channel_post update) :message
    (:edited_channel_post update) :edited
    :else :other))

(defn message
  "Return the ordinary message payload carried by an update, if any."
  [update]
  (or (:message update) (:channel_post update)))

(defn edited-message
  "Return an edited message payload carried by an update, if any."
  [update]
  (or (:edited_message update) (:edited_channel_post update)))

(defn- album-key
  [update]
  (let [message (message update)
        group-id (:media_group_id message)
        chat-id (get-in message [:chat :id])]
    (when (and group-id chat-id)
      [:album chat-id (str group-id)])))

(defn- batch-key
  [update]
  (or (album-key update)
      [:single (:update_id update)]))

(defn- batch
  [updates]
  (let [messages (mapv message updates)]
    {:updates (vec updates)
     :messages messages
     :album? (some? (album-key (first updates)))
     :key (batch-key (first updates))}))

(defn batches
  "Group ordinary message updates by chat + media_group_id.
   Album order follows first appearance in the poll response; member order
   follows Telegram's update order. Non-message updates produce no batches."
  [updates]
  (let [groups (reduce (fn [{:keys [order by-key]} update]
                         (if (= :message (update-kind update))
                           (let [key (batch-key update)]
                             (if (contains? by-key key)
                               {:order order
                                :by-key (update-in by-key [key]
                                                   (fn [existing]
                                                     (batch (conj (:updates existing) update))))}
                               {:order (conj order key)
                                :by-key (assoc by-key key (batch [update]))}))
                           {:order order :by-key by-key}))
                       {:order [] :by-key {}}
                       updates)]
    (mapv #(get-in groups [:by-key %]) (:order groups))))

(defn activation-text
  "Choose the first non-blank text/caption in an album batch."
  [messages]
  (some (fn [message]
          (let [text (or (:text message) (:caption message))]
            (when (seq (str/trim (str (or text "")))) text)))
        messages))
