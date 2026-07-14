(ns bb-agent.slack
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.rich :as rich]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn slack-config []
  (:slack (config/load-config)))

(defn- require-slack [cfg key]
  (let [value (get cfg key)]
    (when (str/blank? value)
      (throw (ex-info (str "Missing slack config: " (name key))
                      {:config/key key})))
    value))

(defn- api-base-url [cfg]
  (str/replace (or (:base-url cfg) "https://slack.com/api") #"/+$" ""))

(defn- auth-headers [cfg]
  {"authorization" (str "Bearer " (require-slack cfg :token))
   "content-type" "application/json;charset=utf-8"})

(defn- fetch-events [cfg]
  (let [url (str (api-base-url cfg) "/conversations.history")
        channel-id (require-slack cfg :channel-id)
        response (http/get url {:throw false
                                :headers (auth-headers cfg)
                                :query-params {:channel channel-id
                                               :limit 10}})
        body (json/parse-string (:body response) keyword)]
    (when-not (:ok body)
      (throw (ex-info (str "Slack history failed: " (:error body))
                      {:body body})))
    (->> (:messages body)
         reverse
         (filter :text)
         (map (fn [message]
                {:event/id (str (:ts message))
                 :channel/id channel-id
                 :user/id (:user message)
                 :text (:text message)}))
         vec)))

(defn- send-message! [cfg text]
  (let [url (str (api-base-url cfg) "/chat.postMessage")
        response (http/post url {:throw false
                                 :headers (auth-headers cfg)
                                 :body (json/generate-string {:channel (require-slack cfg :channel-id)
                                                              :text text})})
        body (json/parse-string (:body response) keyword)]
    (when-not (:ok body)
      (throw (ex-info (str "Slack send failed: " (:error body))
                      {:body body})))
    body))

(defn- seen-events-file []
  (fs/path (config/home) "state" "slack-seen.edn"))

(defn- load-seen-events []
  (let [path (seen-events-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      #{})))

(defn- save-seen-events! [ids]
  (let [path (seen-events-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str ids))
    ids))

(defn- session-id-for-channel [channel-id user-id]
  (str "slack-" channel-id "-" (or user-id "unknown")))

(defn poll-once! []
  (let [cfg (slack-config)
        channel-id (require-slack cfg :channel-id)
        _token (require-slack cfg :token)
        seen (load-seen-events)
        events (fetch-events cfg)
        fresh (remove #(contains? seen (:event/id %)) events)]
    (doseq [{event-id :event/id channel-id* :channel/id user-id :user/id text :text} fresh]
      (let [turn (core/run-turn! (assoc (config/load-config)
                                        :session/id (session-id-for-channel channel-id* user-id))
                                 text)]
        (send-message! cfg (rich/slack (rich/markdown (:assistant/final turn))))))
    (save-seen-events! (into seen (map :event/id fresh)))
    {:events (count fresh)
     :channel/id channel-id}))
