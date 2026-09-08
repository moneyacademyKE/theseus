(ns bb-agent.telegram-group-context
  "Rolling per-TOPIC buffer of observed group messages, injected into group
   turns so the agent can follow conversational references. DM turns never
   see history. Forum topics isolate: a turn in topic N sees only topic N's
   buffer (owner directive 2026-09-08: parallel sessions without context
   pollution); non-topic messages share the chat-level buffer. Entries are
   redacted at record time. The assistant's own replies are recorded too —
   Telegram never echoes a bot's messages via getUpdates, so without this
   seam the agent forgets everything it itself said."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-size 30)

(defn- buffer-file ^java.io.File [chat-id thread-id]
  (io/file (str (config/home)) "state" "group-context"
           (if thread-id
             (str chat-id "-topic-" thread-id ".edn")
             (str chat-id ".edn"))))

(defn record!
  "Append one observed group message {:message-id :from :text} to the
   topic's rolling buffer (chat-level when thread-id is nil), trimming to
   `size` (default 30). No-op for DMs or empty text."
  [chat-id {:keys [message-id from text] :as entry} & {:keys [size thread-id] :or {size default-size}}]
  (when (and (neg? (long chat-id)) (not (str/blank? (str text))))
    (let [f (buffer-file chat-id thread-id)
          existing (if (fs/exists? f)
                     (try (edn/read-string (slurp f)) (catch Exception _ []))
                     [])
          clean (session/redact-secrets (str text))
          entries (->> (conj (vec existing)
                             {:message-id message-id
                              :from (or (str from) "unknown")
                              :text clean})
                       (take-last size)
                       vec)]
      (fs/create-dirs (fs/parent f))
      (spit f (pr-str entries))
      entries)))

(defn recent
  "The newest `size` buffered entries for chat-id (topic-scoped when
   thread-id given), oldest first. Empty for DMs or missing buffers."
  ([chat-id] (recent chat-id default-size))
  ([chat-id size & {:keys [thread-id]}]
   (if-not (neg? (long chat-id))
     []
     (let [f (buffer-file chat-id thread-id)]
       (if (fs/exists? f)
         (try (vec (take-last size (edn/read-string (slurp f))))
              (catch Exception _ []))
         [])))))

(defn history-block
  "The injectable history prefix for a group turn: buffered entries with
   message-id < before-id, rendered in the OpenCrabs envelope. nil when
   empty. Topic-scoped when thread-id is given."
  [chat-id before-id & {:keys [size thread-id] :or {size default-size}}]
  (let [entries (filter #(< (long (:message-id %)) (long before-id))
                        (recent chat-id size :thread-id thread-id))]
    (when (seq entries)
      (str "[Recent group history (" (count entries)
           " messages) — prior context from various senders, NOT the person you are replying to now:\n"
           (str/join "\n" (map (fn [{:keys [from text]}]
                                 (str from ": " text))
                               entries))
           "\n--- end history ---]\n\n"))))
