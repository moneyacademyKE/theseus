(ns bb-agent.web-a2a
  "Minimal A2A transport over the existing web turn lane.

  The card is public discovery metadata. JSON-RPC is bearer-gated and maps
  message/send to a durable web job; tasks/get is a projection of that same
  job. No second conversation engine, queue, or persistence format."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.web-http :as http]
            [bb-agent.web-turn :as turn]
            [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.nio.file.attribute PosixFilePermissions)))

(defn- token-file []
  (fs/path (config/home) "state" "a2a-token"))

(defn- new-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and 0xff (long %))) bytes))))

(defn- chmod-600! [path]
  (try
    (fs/set-posix-file-permissions
     path (PosixFilePermissions/fromString "rw-------"))
    (catch Exception _ nil)))

(defn- token []
  (let [path (token-file)]
    (if (fs/regular-file? path)
      (do (chmod-600! path)
          (str/trim (slurp (str path))))
      (let [value (new-token)]
        (fs/create-dirs (fs/parent path))
        (spit (str path) value)
        (chmod-600! path)
        value))))

(defn- authorized? [req]
  (let [supplied (http/bearer req)
        expected (token)]
    (and (string? supplied)
         (not (str/blank? supplied))
         (not (str/blank? expected))
         (java.security.MessageDigest/isEqual
          (.getBytes supplied "UTF-8")
          (.getBytes expected "UTF-8")))))

(defn- base-url [req]
  (str (or (get-in req [:headers "x-forwarded-proto"]) "http") "://"
       (or (get-in req [:headers "host"]) "127.0.0.1:8787")))

(defn handle-card [req]
  (let [endpoint (str (base-url req) "/a2a/v1")]
    (http/json-res
     200
     {:name "Theseus"
      :description "Theseus agent over its shared web turn lane"
      :url endpoint
      :version "0.1"
      :protocolVersion "1.0"
      :preferredTransport "JSONRPC"
      :supportedInterfaces [{:url endpoint
                             :protocolBinding "JSONRPC"
                             :protocolVersion "1.0"}]
      :capabilities {:streaming false
                     :pushNotifications false
                     :stateTransitionHistory false}
      :defaultInputModes ["text/plain"]
      :defaultOutputModes ["text/plain"]
      :skills [{:id "message-send"
                :name "Send a message"
                :description "Queue a text turn on the Theseus engine"
                :tags ["agent" "theseus"]
                :inputModes ["text/plain"]
                :outputModes ["text/plain"]}]})))

(defn- rpc-error [id code message]
  (http/json-res 200 {:jsonrpc "2.0" :id id
                      :error {:code code :message message}}))

(defn- text-from-message [message]
  (some->> (:parts message)
           (keep (fn [part]
                   (let [kind (some-> (or (:kind part) (:type part)) name)
                         text (:text part)]
                     (when (and (or (nil? kind) (= "text" kind))
                                (string? text))
                       text))))
           first
           str/trim
           not-empty))

(defn- task-view [id]
  (when-let [job (turn/job-view id)]
    (let [state (case (:status job)
                  :queued "submitted"
                  :running "working"
                  :done "completed"
                  :failed "failed"
                  :canceled "canceled"
                  :interrupted "failed"
                  "unknown")]
      {:id id
       :contextId (:session/id job)
       :status (cond-> {:state state}
                 (:error job)
                 (assoc :message {:parts [{:kind "text"
                                           :text (str (:error job))}]}))
       :artifacts (cond-> []
                    (:final job) (conj {:parts [{:kind "text"
                                                 :text (:final job)}]}))})))

(defn handle-rpc [req]
  (if-not (authorized? req)
    (http/json-res 401 {:error "unauthorized"})
    (let [payload (http/parse-json-body req)
          id (:id payload)
          method (:method payload)
          params (:params payload)]
      (cond
        (nil? payload) (rpc-error id -32600 "invalid request")
        (= method "message/send")
        (if-let [text (text-from-message (:message params))]
          (try
            (let [session (or (:session params)
                              (get-in params [:message :contextId])
                              "a2a")
                  job (turn/submit-turn! {:session session :text text})]
              (http/json-res 200 {:jsonrpc "2.0" :id id
                                  :result (task-view (:job/id job))}))
            (catch Exception e
              (rpc-error id -32000 (or (.getMessage e) "could not queue message"))))
          (rpc-error id -32602 "message must contain a non-empty text part"))
        (= method "tasks/get")
        (if-let [task (task-view (str (:id params)))]
          (http/json-res 200 {:jsonrpc "2.0" :id id :result task})
          (rpc-error id -32001 "task not found"))
        :else (rpc-error id -32601 "method not found")))))

(defn routes [req]
  (cond
    (and (= :get (:request-method req))
         (contains? #{"/.well-known/agent.json"
                      "/.well-known/agent-card.json"}
                    (:uri req)))
    (handle-card req)
    (and (= :post (:request-method req))
         (contains? #{"/a2a/v1" "/a2a"} (:uri req)))
    (handle-rpc req)
    :else nil))
