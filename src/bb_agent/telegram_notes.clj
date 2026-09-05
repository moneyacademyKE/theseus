(ns bb-agent.telegram-notes
  "Durable, bounded context notes for Telegram edits.

   An edit changes history; it does not authorize a second execution. Notes
   are consumed when the next ordinary message for the same session runs."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
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

(defn add!
  "Append one bounded edit note for a session. Empty edits are ignored."
  [home session-id message]
  (let [note (format-note message)
        path (note-file home session-id)]
    (when (seq (str/trim note))
      (fs/create-dirs (fs/parent path))
      (let [notes (if (fs/regular-file? path)
                    (edn/read-string (slurp (str path)))
                    [])
            updated (conj (vec notes) note)]
        (spit (str path) (pr-str (vec (take-last max-notes updated))))
        note))))

(defn add-raw!
  "Append one bounded pre-formatted context note (reactions and other
   non-edit signals) for a session. Empty notes are ignored."
  [home session-id note]
  (let [note (bounded (str note))
        path (note-file home session-id)]
    (when (seq (str/trim note))
      (fs/create-dirs (fs/parent path))
      (let [notes (if (fs/regular-file? path)
                    (edn/read-string (slurp (str path)))
                    [])
            updated (conj (vec notes) note)]
        (spit (str path) (pr-str (vec (take-last max-notes updated))))
        note))))

(defn take!
  "Return and clear pending edit notes for a session."
  [home session-id]
  (let [path (note-file home session-id)]
    (if (fs/regular-file? path)
      (let [notes (vec (edn/read-string (slurp (str path))))]
        (spit (str path) "[]")
        notes)
      [])))
