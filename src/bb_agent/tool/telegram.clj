(ns bb-agent.tool.telegram
  "telegram_send_file handler. Outbound files from an agent turn. The chat
   binding is injected by the dispatcher from the live session context and
   the handler refuses without it; the Bot API token never transits tool
   args (approval prompts print those verbatim). File size and caption
   limits are enforced in the delivery seam before any network call."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-upload :as upload]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

(defn- arg
  [args key]
  (or (get args key) (get args (name key))))

(defn send-file
  [args]
  (let [tool-name "telegram_send_file"
        session (arg args :telegram-session)
        chat-id (:chat-id session)]
    (if (or (nil? chat-id) (str/blank? (str chat-id)))
      (common/error-result tool-name
                           "telegram_send_file is only available inside a Telegram session"
                           {:executed? false})
      (try
        (let [raw-path (str (arg args :path))
              path (if (str/starts-with? raw-path "/")
                     raw-path
                     (str (fs/file (or (some-> (arg args :cwd) str) ".") raw-path)))
              kind (if (arg args :photo?) :photo :document)
              result (upload/send-file!
                      (:telegram (config/load-config)) chat-id path kind
                      {:caption (arg args :caption)
                       :thread-id (:thread-id session)})]
          (common/ok-result tool-name
                            {:message-id (:message-id result)
                             :output (str "sent " (name kind) " as message "
                                          (:message-id result))}))
        (catch Exception e
          (common/error-result tool-name
                               (or (ex-message e) (.getName (class e)))
                               {:exception/type (str (class e))}))))))

(def handlers
  {"telegram_send_file" send-file})
