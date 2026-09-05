(ns bb-agent.telegram-state
  "Durable poll-cursor state for the Telegram channel: the getUpdates
   offset (so acked updates are never replayed) and the seen-update set
   (so a replayed offset window is still idempotent). Both live under the
   agent home's state/ directory as EDN."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]))

(defn- offset-file []
  (fs/path (config/home) "state" "telegram-offset.edn"))

(defn- seen-file []
  (fs/path (config/home) "state" "telegram-seen.edn"))

(defn load-offset []
  (let [path (offset-file)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn save-offset! [offset]
  (let [path (offset-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str offset))
    offset))

(defn load-seen []
  (let [path (seen-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      #{})))

(defn save-seen! [seen]
  (let [path (seen-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str seen))
    seen))

(defn- reply-file [chat-id]
  (fs/path (config/home) "state" "telegram-replies" (str chat-id ".edn")))

(defn record-reply! [chat-id user-msg-id bot-reply-msg-id]
  (when (and chat-id user-msg-id bot-reply-msg-id)
    (let [path (reply-file chat-id)]
      (fs/create-dirs (fs/parent path))
      (let [m (if (fs/regular-file? path)
                (or (try (edn/read-string (slurp (str path))) (catch Exception _ {})) {})
                {})]
        (spit (str path) (pr-str (assoc m (long user-msg-id) (long bot-reply-msg-id))))
        bot-reply-msg-id))))

(defn lookup-reply [chat-id user-msg-id]
  (when (and chat-id user-msg-id)
    (let [path (reply-file chat-id)]
      (when (fs/regular-file? path)
        (get (try (edn/read-string (slurp (str path))) (catch Exception _ nil))
             (long user-msg-id))))))
