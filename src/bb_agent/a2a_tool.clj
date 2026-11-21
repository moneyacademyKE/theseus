(ns bb-agent.a2a-tool
  "a2a_task: borrow a remote A2A agent (OpenCrabs, another Theseus deck).
  Approval-gated — it is a network side effect. The stored OpenCrabs
  bearer is attached ONLY to localhost targets: the model chooses the
  URL, so a remote host must never receive this machine's credential."
  (:require [babashka.fs :as fs]
            [bb-agent.a2a-client :as a2a]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

(def max-output-chars 20000)
(def ^:private min-timeout-ms 1000)
(def ^:private max-timeout-ms 600000)

(defn- parse-timeout-ms
  "Parse an optional positive integer timeout-ms, clamped to [1000 600000].
  Returns nil when absent, a long when valid, or :invalid when malformed."
  [raw]
  (when (some? raw)
    (let [parsed (cond
                   (integer? raw) (long raw)
                   (and (string? raw) (re-matches #"\s*\d+\s*" raw))
                   (try (Long/parseLong (str/trim raw)) (catch Exception _ nil))
                   :else nil)]
      (if (and parsed (pos? parsed))
        (-> parsed (max min-timeout-ms) (min max-timeout-ms))
        :invalid))))

(defn- token-file []
  (fs/path (config/home) "state" "a2a-opencrabs-token"))

(defn- localhost? [url]
  (try
    (contains? #{"127.0.0.1" "localhost" "[::1]" "::1"}
               (.getHost (java.net.URI. url)))
    (catch Exception _ false)))

(defn stored-token
  "The OpenCrabs bridge token, file-only, mode 600, never an argument.
  Public on purpose: tests with-redefs THIS seam, never the file path."
  []
  (try
    (when-let [path (token-file)]
      (when (fs/regular-file? path)
        (str/trim (slurp (str path)))))
    (catch Exception _ nil)))

(defn- bearer-for [url]
  (when (localhost? url)
    (stored-token)))

(defn- valid-url? [url]
  (try
    (let [u (java.net.URI. url)]
      (and (contains? #{"http" "https"} (.getScheme u))
           (some? (.getHost u))
           (nil? (.getUserInfo u))))
    (catch Exception _ false)))

(defn- bounded [text]
  (let [body (str text)]
    (if (<= (count body) max-output-chars)
      body
      (str (subs body 0 (- max-output-chars (count "[truncated]")))
           "[truncated]"))))

(defn a2a-task
  "Handler: send :url + :text, wait for the remote agent's reply."
  [args]
  (let [url (or (:url args) (get args "url"))
        text (or (:text args) (get args "text"))
        raw-timeout (some #(when (some? (get args %)) (get args %))
                          [:timeout-ms "timeout-ms" :timeout_ms "timeout_ms"])
        timeout-ms (parse-timeout-ms raw-timeout)]
    (cond
      (not (and (string? url) (valid-url? url)))
      (common/error-result "a2a_task"
                           "url must be http(s) without userinfo"
                           {:executed? false})
      (not (and (string? text) (not (str/blank? text))))
      (common/error-result "a2a_task" "text must be a non-empty string"
                           {:executed? false})
      (= :invalid timeout-ms)
      (common/error-result "a2a_task" "timeout-ms must be a positive integer"
                           {:executed? false})
      :else
      (let [result (a2a/run-task {:base-url url
                                  :text text
                                  :token (bearer-for url)
                                  :timeout-ms timeout-ms})]
        (if (:ok? result)
          (common/ok-result "a2a_task"
                            {:output (bounded (str "remote agent replied:\n"
                                                    (:text result)))
                             :task-id (:task-id result)})
          (common/error-result "a2a_task"
                               (str (:error result "a2a task failed"))
                               {:executed? true
                                :task-id (:task-id result)
                                :state (:state result)}))))))

(def handlers {"a2a_task" a2a-task})
