(ns bb-agent.telegram
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-guard :as guard]
            [bb-agent.telegram-rich :as tr]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn telegram-config []
  (:telegram (config/load-config)))

(defn- require-telegram [cfg key]
  (let [value (get cfg key)]
    (when (str/blank? value)
      (throw (ex-info (str "Missing telegram config: " (name key))
                      {:config/key key})))
    value))

(defn- api-url [{:keys [base-url token]} method]
  (str (str/replace (or base-url "https://api.telegram.org") #"/+$" "")
       "/bot" token "/" method))

(defn- offset-file []
  (fs/path (config/home) "state" "telegram-offset.edn"))

(defn- seen-file []
  (fs/path (config/home) "state" "telegram-seen.edn"))

(defn- load-offset []
  (let [path (offset-file)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn- save-offset! [offset]
  (let [path (offset-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str offset))
    offset))

(defn- load-seen []
  (let [path (seen-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      #{})))

(defn- save-seen! [seen]
  (let [path (seen-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str seen))
    seen))

(defn- api-get [cfg method opts]
  (let [response (http/get (api-url cfg method) (assoc opts :throw false))]
    (json/parse-string (:body response) keyword)))

(defn- get-bot [cfg]
  (:result (api-get cfg "getMe" {:headers {"accept" "application/json"}})))

(defn- get-updates [cfg]
  (let [body (api-get cfg "getUpdates"
                      (cond-> {:headers {"accept" "application/json"}}
                        (load-offset) (assoc :query-params {:offset (load-offset)})))]
    (or (:result body) [])))

(defn- send-message!
  [cfg chat-id text {:keys [parse-mode thread-id reply-to-message-id]}]
  (let [body (cond-> {:chat_id chat-id :text text}
               parse-mode (assoc :parse_mode parse-mode)
               thread-id (assoc :message_thread_id thread-id)
               reply-to-message-id
               (assoc :reply_parameters {:message_id reply-to-message-id}))]
    (http/post (api-url cfg "sendMessage")
               {:throw false
                :headers {"content-type" "application/json"}
                :body (json/generate-string body)})))

(defn- send-html!
  [cfg chat-id html {:keys [thread-id reply-to-message-id]}]
  (doseq [[index chunk] (map-indexed vector (tr/split-message html))]
    (send-message! cfg chat-id chunk
                   {:parse-mode "HTML"
                    :thread-id thread-id
                    :reply-to-message-id (when (zero? index)
                                           reply-to-message-id)})))

(defn- send-approval-request!
  [cfg chat-id thread-id pending]
  (send-message! cfg chat-id
                 (str "Tool approval requested\n"
                      "id=" (:approval/id pending) "\n"
                      "tool=" (:tool/name pending) "\n"
                      (pr-str (:tool/args pending)) "\n"
                      "Reply /approve, /deny, or /approve-rest.")
                 {:thread-id thread-id}))

(defn- process-message!
  [cfg bot message]
  (let [chat-id (get-in message [:chat :id])
        thread-id (group/topic-id message)
        session-id (group/session-id message)
        text (group/normalize-command (:text message) bot)]
    (when (and (seq text)
               (guard/message-allowed? cfg message)
               (group/should-respond? cfg bot message))
      (if-let [decision (approval/telegram-approval-reply text)]
        (send-message! (:telegram cfg) chat-id
                       (approval/approval-reply-text
                        (approval/resolve! session-id decision)
                        decision)
                       {:thread-id thread-id})
        (let [telegram-cfg (:telegram cfg)
              turn (core/run-turn!
                    (assoc cfg
                           :session/id session-id
                           :session/shared? (group/group-chat? message)
                           :approval/ask
                           (approval/waiting-approver
                            {:session-id session-id
                             :channel :telegram
                             :timeout-ms (or (:approval-timeout-ms telegram-cfg) 30000)
                             :notify #(send-approval-request!
                                       telegram-cfg chat-id thread-id %)}))
                    (group/agent-input bot (assoc message :text text)))]
          (send-html! telegram-cfg chat-id
                      (tr/to-html (:assistant/final turn))
                      {:thread-id thread-id
                       :reply-to-message-id (:message_id message)}))))))

(defn poll-once! []
  (let [cfg (config/load-config)
        telegram-cfg (:telegram cfg)
        token (require-telegram telegram-cfg :token)
        telegram-cfg (assoc telegram-cfg :token token)
        cfg (assoc cfg :telegram telegram-cfg)
        bot (get-bot telegram-cfg)
        updates (get-updates telegram-cfg)
        seen (atom (load-seen))
        processed (atom 0)]
    (doseq [update updates]
      (when-not (contains? @seen (:update_id update))
        (when-let [message (:message update)]
          (process-message! cfg bot message))
        (swap! seen conj (:update_id update))
        (save-seen! @seen)
        (save-offset! (inc (:update_id update)))
        (swap! processed inc)))
    {:updates @processed}))

(defn poll-loop!
  "Continuous polling with a sleep between cycles. Stop with ctrl-c."
  [& {:keys [interval-ms] :or {interval-ms 2000}}]
  (loop []
    (try
      (poll-once!)
      (catch Exception e
        (println (str "telegram poll error: " (.getMessage e)))))
    (Thread/sleep (long interval-ms))
    (recur)))
