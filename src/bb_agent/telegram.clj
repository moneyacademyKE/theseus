(ns bb-agent.telegram
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.rich :as rich]
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
       "/bot"
       token
       "/"
       method))

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

(defn- get-updates [cfg]
  (let [url (api-url cfg "getUpdates")
        response (http/get url (cond-> {:throw false
                                        :headers {"accept" "application/json"}}
                                 (load-offset)
                                 (assoc :query-params {:offset (load-offset)})))
        body (json/parse-string (:body response) keyword)]
    (or (:result body) [])))

(defn- send-message! [cfg chat-id text]
  (let [url (api-url cfg "sendMessage")]
    (http/post url {:throw false
                    :headers {"content-type" "application/json"}
                    :body (json/generate-string {:chat_id chat-id
                                                  :text text})})))

(defn- send-approval-request! [cfg chat-id pending]
  (send-message! cfg chat-id
                 (str "Tool approval requested\n"
                      "id=" (:approval/id pending) "\n"
                      "tool=" (:tool/name pending) "\n"
                      (pr-str (:tool/args pending)) "\n"
                      "Reply /approve, /deny, or /approve-rest.")))

(defn- session-id-for-chat [chat-id]
  (str "telegram-" chat-id))

(defn poll-once! []
  (let [cfg (telegram-config)
        token (require-telegram cfg :token)
        cfg* (assoc cfg :token token)
        updates (get-updates cfg*)
        seen (atom (load-seen))
        processed (atom 0)]
    (doseq [update updates]
      (when-not (contains? @seen (:update_id update))
      (when-let [message (:message update)]
        (let [chat-id (get-in message [:chat :id])
              session-id (session-id-for-chat chat-id)
              text (:text message)]
          (if-let [decision (approval/telegram-approval-reply text)]
            (send-message! cfg* chat-id
                           (approval/approval-reply-text
                            (approval/resolve! session-id decision)
                            decision))
            (let [turn (core/run-turn! (assoc (config/load-config)
                                              :session/id session-id
                                              :approval/ask
                                              (approval/waiting-approver
                                               {:session-id session-id
                                                :channel :telegram
                                                :timeout-ms (or (:approval-timeout-ms cfg*) 30000)
                                                :notify #(send-approval-request! cfg* chat-id %)}))
                                       text)]
              (send-message! cfg* chat-id (rich/telegram (rich/markdown (:assistant/final turn))))))))
      (swap! seen conj (:update_id update))
      (save-seen! @seen)
      (save-offset! (inc (:update_id update)))
      (swap! processed inc)))
    {:updates @processed}))
