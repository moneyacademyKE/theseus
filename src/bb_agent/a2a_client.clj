(ns bb-agent.a2a-client
  "A2A JSON-RPC client: message/send + tasks/get against any A2A agent
  that speaks the Streamable HTTP binding (OpenCrabs and Theseus both do).

  One network seam (post-json / get-json) on purpose: tests with-redefs
  THOSE, never the rpc logic. Bearer tokens are supplied by the caller;
  this ns never reads a credential file itself.

  Failure posture: fail closed. Non-200, rpc error, missing task, or a
  non-terminal task at timeout is an explicit error map — never a
  guessed reply."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-timeout-ms 180000)
(def default-poll-ms 2000)

(defn- headers [token]
  (cond-> {"Content-Type" "application/json"}
    (not (str/blank? token)) (assoc "Authorization" (str "Bearer " token))))

(defn post-json
  "POST one JSON-RPC envelope. Returns {:status int :body string} or
  {:error message} on transport failure. THE seam tests replace."
  [url body token]
  (try
    (let [resp (http/post url {:throw false
                               :headers (headers token)
                               :body (json/generate-string body)})]
      {:status (:status resp) :body (:body resp)})
    (catch Exception e
      {:error (or (.getMessage e) "a2a request failed")})))

(defn get-json
  "GET one URL, parse nothing. Returns {:status int :body string} or
  {:error message}. THE seam tests replace."
  [url]
  (try
    (let [resp (http/get url {:throw false})]
      {:status (:status resp) :body (:body resp)})
    (catch Exception e
      {:error (or (.getMessage e) "a2a request failed")})))

(defn- parse-body [resp]
  (when (and (map? resp) (string? (:body resp)))
    (try (json/parse-string (:body resp) true)
         (catch Exception _ nil))))

(defn- endpoint [base-url]
  (str (str/replace (or base-url "") #"/+$" "") "/a2a/v1"))

(defn- rpc-error-message [resp]
  (when-let [parsed (parse-body resp)]
    (some-> (:error parsed) :message str)))

(defn send-message
  "message/send one text task. Returns {:ok? true :task task-map} or
  {:ok? false :error msg :status http-status}."
  [{:keys [base-url text token session]}]
  (let [resp (post-json (endpoint base-url)
                        {:jsonrpc "2.0" :id 1 :method "message/send"
                         :params {:message {:role "user"
                                            :parts [{:kind "text" :text text}]
                                            :contextId (or session "theseus-a2a")}}}
                        token)]
    (cond
      (:error resp) {:ok? false :error (:error resp)}
      (not= 200 (:status resp)) {:ok? false
                                 :error (str "a2a send failed: HTTP " (:status resp))
                                 :status (:status resp)}
      (rpc-error-message resp) {:ok? false
                                :error (str "a2a rpc error: " (rpc-error-message resp))
                                :status 200}
      :else {:ok? true :task (get-in (parse-body resp) [:result])})))

(defn task-text
  "Concatenated text parts of a task's artifacts (kind or type tagged,
  untagged parts tolerated — OpenCrabs and Theseus emit slightly
  different shapes)."
  [task]
  (->> (:artifacts task)
       (mapcat :parts)
       (keep (fn [part]
               (let [text (:text part)]
                 (when (and (string? text)
                            (let [kind (some-> (or (:kind part) (:type part)) name)]
                              (or (nil? kind) (= "text" kind))))
                   text))))
       (str/join "\n")))

(defn task-state [task]
  (get-in task [:status :state]))

(defn- status-message [task]
  (->> (get-in task [:status :message :parts])
       (keep :text)
       (str/join " ")))

(defn- terminal? [task]
  (contains? #{"completed" "failed" "canceled"} (task-state task)))

(defn fetch-task
  "tasks/get one task by id. Returns the task map or nil when the
  remote agent does not know it."
  [{:keys [base-url token task-id]}]
  (let [resp (post-json (endpoint base-url)
                        {:jsonrpc "2.0" :id 2 :method "tasks/get"
                         :params {:id task-id}}
                        token)]
    (when (and (map? resp) (= 200 (:status resp)))
      (when-let [parsed (parse-body resp)]
        (when-not (:error parsed)
          (:result parsed))))))

(defn await-task
  "Poll tasks/get until the task is terminal or the deadline passes.
  Returns {:task task-map-or-nil :timed-out? bool}."
  [{:keys [timeout-ms poll-ms] :as opts}]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms default-timeout-ms))
        interval-ms (or poll-ms default-poll-ms)]
    (loop []
      (let [task (fetch-task opts)]
        (cond
          (nil? task) {:task nil :timed-out? true}
          (terminal? task) {:task task :timed-out? false}
          (>= (System/currentTimeMillis) deadline) {:task task :timed-out? true}
          :else (do (Thread/sleep interval-ms) (recur)))))))

(defn run-task
  "Send one text task and wait for its terminal state. Returns
  {:ok? true :state \"completed\" :text reply :task-id id} or an explicit
  error map (:error, :status, :task-id, :timed-out?)."
  [opts]
  (let [sent (send-message opts)]
    (if-not (:ok? sent)
      {:ok? false :error (:error sent) :status (:status sent)}
      (let [task-id (get-in sent [:task :id])
            final (await-task (assoc opts :task-id task-id))
            task (:task final)]
        (cond
          (:timed-out? final)
          {:ok? false :timed-out? true :task-id task-id
           :state (task-state task)
           :error (str "timed out; task " task-id " is still "
                       (or (task-state task) "unknown"))}
          (= "completed" (task-state task))
          {:ok? true :state "completed" :task-id task-id :text (task-text task)}
          :else
          {:ok? false :state (task-state task) :task-id task-id
           :error (or (status-message task)
                      (str "task ended " (task-state task)))})))))

(defn agent-card
  "Fetch and parse /.well-known/agent.json. Returns the card map or an
  error map. Never used on the tool path — probes only."
  [base-url]
  (let [resp (get-json (str (str/replace (or base-url "") #"/+$" "")
                           "/.well-known/agent.json"))]
    (cond
      (:error resp) {:ok? false :error (:error resp)}
      (not= 200 (:status resp)) {:ok? false :error (str "HTTP " (:status resp))}
      :else {:ok? true :card (parse-body resp)})))
