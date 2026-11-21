(ns bb-agent.telegram-notes
  "Durable, bounded context notes for Telegram edits.

   An edit changes history; it does not authorize a second execution. Notes
   are consumed when the next ordinary message for the same session runs."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [clojure.string :as str]))

(def ^:private max-note-chars 2000)
(def ^:private max-notes 10)

(defn- note-file
  [home session-id]
  (fs/path home "state" "telegram-edits"
           (str (str/replace (or session-id "default")
                             #"[^A-Za-z0-9._-]" "_") ".edn")))

(defn- bounded
  [text]
  (let [text (str/trim (str (or text "")))]
    (if (<= (count text) max-note-chars)
      text
      (str (subs text 0 (- max-note-chars 13)) "[truncated]"))))

(defn format-note
  [message]
  (let [sender (or (get-in message [:from :username])
                   (get-in message [:from :first_name])
                   "unknown")
        message-id (:message_id message)
        text (or (:text message) (:caption message) "")]
    (str "[Telegram edited message from " sender
         " #" message-id "]: " (bounded text))))

(defn- append-note! [home session-id note]
  (when (seq (str/trim (str note)))
    (let [path (note-file home session-id)]
      (aio/atomic-mutate-edn!
       path []
       (fn [notes]
         (let [current (if (sequential? notes) (vec notes) [])
               updated (vec (take-last max-notes (conj current note)))]
           {:state' updated :result note}))))))

(defn add!
  "Append one bounded edit note for a session. Empty edits are ignored."
  [home session-id message]
  (append-note! home session-id (format-note message)))

(defn add-raw!
  "Append one bounded pre-formatted context note (reactions and other
   non-edit signals) for a session. Empty notes are ignored."
  [home session-id note]
  (append-note! home session-id (bounded (str note))))

(defn take!
  "Return and clear pending edit notes for a session."
  [home session-id]
  (let [path (note-file home session-id)]
    (if (fs/regular-file? path)
      (aio/atomic-mutate-edn!
       path []
       (fn [notes]
         (let [current (if (sequential? notes) (vec notes) [])]
           {:state' [] :result current})))
      [])))
