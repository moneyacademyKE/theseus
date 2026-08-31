(ns bb-agent.openai-wire
  "Translate between Theseus' provider-neutral tool values and the OpenAI
  chat-completions wire protocol. Internal tool data stays provider-neutral;
  only this boundary knows `tool_calls` and `tool_call_id`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- parse-args [args]
  (cond
    (map? args) args
    (str/blank? (or args "")) {}
    :else (json/parse-string args keyword)))

(defn tool-request
  "Decode one OpenAI tool call. Generate a stable per-response id only for
  non-conforming test/providers that omit the required id."
  [index tool-call]
  (let [function (:function tool-call)]
    {:tool/id (or (:id tool-call) (str "call_" index))
     :tool/name (:name function)
     :tool/args (parse-args (:arguments function))}))

(defn- request-id [index request]
  (or (:tool/id request) (str "call_" index)))

(defn- tool-call [index request]
  {:id (request-id index request)
   :type "function"
   :function {:name (:tool/name request)
              :arguments (json/generate-string (or (:tool/args request) {}))}})

(defn- assistant-message [message]
  (let [requests (:tool/requests message)]
    (cond-> {:role "assistant"}
      (contains? message :content) (assoc :content (:content message))
      (seq requests) (assoc :tool_calls (mapv tool-call (range) requests)))))

(defn- tool-messages [message]
  (mapv (fn [index result]
          {:role "tool"
           :tool_call_id (request-id index result)
           :content (json/generate-string (dissoc result :tool/id))})
        (range)
        (:tool/results message)))

(defn- ordinary-message [message]
  {:role (name (:role message))
   :content (:content message)})

(defn- message->wire [message]
  (case (:role message)
    :assistant [(assistant-message message)]
    :tool (tool-messages message)
    [(ordinary-message message)]))

(defn messages
  "Encode internal messages for OpenAI. A combined internal tool-results value
  becomes one OpenAI tool message per result, correlated by `:tool/id`."
  [internal]
  (vec (mapcat message->wire internal)))
