(ns bb-agent.telegram-group-context
  "Rolling per-chat buffer of observed group messages, injected into group
   turns so the agent can follow conversational references. DM turns never
   see history. Entries are redacted at record time."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-size 30)

(defn- buffer-file ^java.io.File [chat-id]
  (io/file (str (config/home)) "state" "group-context" (str chat-id ".edn")))

(defn record!
  "Append one observed group message {:message-id :from :text} to the chat's
   rolling buffer, trimming to `size` (default 30). No-op for DMs or empty
   text."
  [chat-id {:keys [message-id from text] :as entry} & {:keys [size] :or {size default-size}}]
  (when (and (neg? (long chat-id)) (not (str/blank? (str text))))
    (let [f (buffer-file chat-id)
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
  "The newest `size` buffered entries for chat-id, oldest first. Empty for
   DMs or missing buffers."
  ([chat-id] (recent chat-id default-size))
  ([chat-id size]
   (if-not (neg? (long chat-id))
     []
     (let [f (buffer-file chat-id)]
       (if (fs/exists? f)
         (try (vec (take-last size (edn/read-string (slurp f))))
              (catch Exception _ []))
         [])))))

(defn history-block
  "The injectable history prefix for a group turn: buffered entries with
   message-id < before-id, rendered in the OpenCrabs envelope. nil when
   empty."
  [chat-id before-id & {:keys [size] :or {size default-size}}]
  (let [entries (filter #(< (long (:message-id %)) (long before-id))
                        (recent chat-id size))]
    (when (seq entries)
      (str "[Recent group history (" (count entries)
           " messages) — prior context from various senders, NOT the person you are replying to now:\n"
           (str/join "\n" (map (fn [{:keys [from text]}]
                                 (str from ": " text))
                               entries))
           "\n--- end history ---]\n\n"))))
