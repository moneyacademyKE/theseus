(ns bb-agent.mcp-client
  "Bounded Streamable HTTP MCP client.

  Owns the protocol handshake and JSON-RPC transport only. Browser-specific
  policy (tab ownership, screenshots, approval) belongs to callers, not here.
  Responses may be JSON or SSE; errors never include raw response bodies."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-protocol-version "2025-03-26")
(def default-timeout-ms 10000)
(def max-body-chars 2000000)

(defn transport
  "HTTP seam. Tests inject a function with this same [url request] shape."
  [url request]
  (try
    (http/post url (assoc request :throw false))
    (catch Exception e
      {:status -1 :transport-error (or (ex-message e) (.getName (class e)))})))

(defn- failure
  ([message] {:ok? false :error message})
  ([status message] {:ok? false :http/status status :error message}))

(defn- header
  [response wanted]
  (some (fn [[k v]]
          (when (= wanted (str/lower-case (name k))) v))
        (:headers response)))

(defn- timeout
  [value]
  (-> (if (number? value) (long value) default-timeout-ms)
      (max 1000)
      (min 30000)))

(defn- valid-url?
  [url]
  (and (string? url)
       (boolean (re-matches #"https?://[^\s]+" url))))

(defn- sse-events
  [body]
  (keep (fn [event]
          (let [data (->> (str/split-lines event)
                          (filter #(str/starts-with? % "data:"))
                          (map #(str/trim (subs % 5)))
                          (remove #(= % "[DONE]"))
                          seq)]
            (when data (str/join "\n" data))))
        (str/split body #"\r?\n\r?\n")))

(defn decode-messages
  "Decode JSON or SSE data into JSON-RPC maps. Public for focused tests."
  [body content-type]
  (let [text (str (or body ""))]
    (cond
      (str/blank? text) []
      (> (count text) max-body-chars)
      (throw (ex-info "MCP response body exceeds bound" {}))
      (or (str/includes? (str/lower-case (or content-type "")) "text/event-stream")
          (str/starts-with? (str/triml text) "event:")
          (str/starts-with? (str/triml text) "data:"))
      (mapv #(json/parse-string % true) (sse-events text))
      :else [(json/parse-string text true)])))

(defn- rpc-error
  [{:keys [code message]}]
  (str "JSON-RPC error"
       (when (some? code) (str " " code))
       (when-not (str/blank? (str message))
         (str ": " (subs (str message) 0 (min 500 (count (str message))))))))

(defn- response-message
  [response expected-id]
  (let [status (:status response)]
    (cond
      (:transport-error response)
      (failure status (str "MCP transport failed: " (:transport-error response)))

      (not (and (integer? status) (<= 200 status 299)))
      (failure status (str "MCP HTTP request failed (status " status ")"))

      :else
      (try
        (let [messages (decode-messages (:body response)
                                        (header response "content-type"))
              message (some #(when (= (str (:id %)) (str expected-id)) %) messages)]
          (cond
            (nil? message) (failure status "MCP response id did not match request")
            (:error message) (failure status (rpc-error (:error message)))
            (not (contains? message :result)) (failure status "MCP response has no result")
            :else {:ok? true :result (:result message) :message message}))
        (catch Exception _
          (failure status "MCP response was not valid bounded JSON/SSE"))))))

(defn- post
  [{:keys [url transport timeout-ms headers]} message session-id protocol-version]
  (transport url {:body (json/generate-string message)
                  :timeout (timeout timeout-ms)
                  :headers (cond-> (merge {"Content-Type" "application/json"
                                           "Accept" "application/json, text/event-stream"}
                                          headers)
                             (not (str/blank? session-id))
                             (assoc "Mcp-Session-Id" session-id)
                             (not (str/blank? protocol-version))
                             (assoc "MCP-Protocol-Version" protocol-version))}))

(defn- notify-initialized
  [client]
  (let [response (post client {:jsonrpc "2.0" :method "notifications/initialized"}
                       (:session-id client) (:protocol-version client))
        status (:status response)]
    (if (and (integer? status) (<= 200 status 299))
      (assoc client :initialized? true)
      (failure status "MCP initialized notification was rejected"))))

(defn connect
  "Initialize MCP, capture the optional session id, and send initialized.

  Returns a client map with :ok? true, or a redacted failure map. The session
  id and transport are retained for later calls; callers should not log the
  whole map because servers may use a bearer-like session identifier."
  [{:keys [url protocol-version client-name client-version] :as opts}]
  (if-not (valid-url? url)
    (failure "MCP URL must be a nonblank http(s) URL")
    (let [id 1
          requested-version (or protocol-version default-protocol-version)
          client (merge {:transport transport :timeout-ms default-timeout-ms}
                        (select-keys opts [:url :transport :timeout-ms :headers]))
          response (post client
                         {:jsonrpc "2.0" :id id :method "initialize"
                          :params {:protocolVersion requested-version
                                   :capabilities {}
                                   :clientInfo {:name (or client-name "theseus")
                                                :version (or client-version "0.1.0")}}}
                         nil nil)
          parsed (response-message response id)]
      (if-not (:ok? parsed)
        parsed
        (let [result (:result parsed)
              negotiated (or (:protocolVersion result) requested-version)]
          (notify-initialized
           (merge client
                  {:ok? true
                   :session-id (header response "mcp-session-id")
                   :protocol-version negotiated
                   :server-info (:serverInfo result)
                   :capabilities (:capabilities result)
                   :next-id (atom id)})))))))

(defn request
  "Make one JSON-RPC request on an initialized client."
  [{:keys [next-id initialized?] :as client} method params]
  (if-not (and initialized? (instance? clojure.lang.Atom next-id))
    (failure "MCP client is not initialized")
    (let [id (swap! next-id inc)
          response (post client {:jsonrpc "2.0" :id id :method method
                                 :params (or params {})}
                         (:session-id client) (:protocol-version client))]
      (response-message response id))))

(defn list-tools
  ([client] (list-tools client nil))
  ([client cursor]
   (request client "tools/list" (cond-> {} cursor (assoc :cursor cursor)))))

(defn call-tool
  [client tool-name arguments]
  (if (str/blank? (str tool-name))
    (failure "MCP tool name must not be blank")
    (request client "tools/call" {:name (str tool-name)
                                  :arguments (or arguments {})})))
