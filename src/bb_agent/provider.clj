(ns bb-agent.provider
  (:require [babashka.http-client :as http]
            [bb-agent.compression :as compression]
            [bb-agent.redact :as redact]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bb-agent.tools :as tools]
            [bb-agent.vision :as vision]))

(defmulti complete (fn [provider _request] provider))

;; Test double. Implementation lives in bb-agent.provider-fake, loaded on
;; first use — test support never ships in the hot require graph.
(defmethod complete :fake [_ request]
  (require 'bb-agent.provider-fake)
  ((resolve 'bb-agent.provider-fake/complete-fake) request))

(defn- openai-url [{:keys [base-url]}]
  (str (str/replace (or base-url "") #"/+$" "") "/chat/completions"))

(defn- require-config [config key]
  (let [value (get config key)]
    (when (str/blank? value)
      (throw (ex-info (str "Missing provider config: " (name key))
                      {:config/key key})))
    value))

(defn- parse-json-args [args]
  (if (str/blank? (or args ""))
    {}
    (json/parse-string args keyword)))

(defn- safe-parse-json
  "Best-effort JSON parse; returns nil on empty or non-JSON payloads (such
   as HTML 502/503 proxy error pages) so status checks fire cleanly."
  [s]
  (when-not (str/blank? (or s ""))
    (try (json/parse-string s keyword)
         (catch Exception _ nil))))

(defn- openai-tool-request [tool-call]
  (let [function (:function tool-call)]
    {:tool/name (:name function)
     :tool/args (parse-json-args (:arguments function))}))

(defn- openai-usage [body]
  (when-let [usage (:usage body)]
    {:tokens/input (or (:prompt_tokens usage) (:input_tokens usage) 0)
     :tokens/output (or (:completion_tokens usage) (:output_tokens usage) 0)
     :tokens/cache-read (or (:cached_tokens usage)
                            (get-in usage [:prompt_tokens_details :cached_tokens])
                            0)}))

(defn openai-wire-messages
  "Translate internal message maps into OpenAI chat-completions wire format.
   Plain messages pass through; an assistant tool round becomes tool_calls
   with synthetic positional ids (call-0, call-1, ...) and its paired
   tool-result message splits into one role=tool message per id, keeping
   tool_call_id linked by position."
  [messages]
  (letfn [(wire-tool-calls [m ids]
            (cond-> {"role" "assistant"
                     "tool_calls"
                     (mapv (fn [req id]
                             {"id" id
                              "type" "function"
                              "function" {"name" (name (:tool/name req))
                                          "arguments" (json/generate-string (:tool/args req))}})
                           (:tool/requests m) ids)}
              (:content m) (assoc "content" (:content m))))]
    (loop [in (seq messages) out [] pending-ids [] next-id 0]
      (if-let [m (first in)]
        (cond
          (:tool/requests m)
          (let [ids (mapv #(str "call-" %)
                          (range next-id (+ next-id (count (:tool/requests m)))))]
            (recur (next in) (conj out (wire-tool-calls m ids))
                   ids (+ next-id (count ids))))

          (:tool/results m)
          (let [tool-msgs (mapv (fn [result id]
                                  {"role" "tool"
                                   "tool_call_id" id
                                   "content" (pr-str result)})
                                (:tool/results m) pending-ids)]
            (recur (next in) (into out tool-msgs) [] next-id))

          :else
          (recur (next in)
                 (conj out {"role" (name (:role m)) "content" (:content m)})
                 [] next-id))
        out))))

(defn- vision-rejection?
  "True only when a request that carried images is 400'd AND the error body
   itself names image/vision handling. Unrelated 400s must not degrade —
   they fail loudly, exactly like the delivery fallback boundary."
  [status body images]
  (and (seq images)
       (= 400 (long (or status 0)))
       (boolean
        (re-find #"(?i)image|vision|multimodal"
                 (if (string? body) body (pr-str body))))))

(defn- perform-with-vision-fallback
  "Execute `post!` with images, falling back once to text-only if the model
   returns a vision-specific 400. Throws structured status errors BEFORE
   requiring a valid JSON body so HTML 5xx pages classify as retryable :infra."
  [post! images]
  (let [first-resp (post! images)
        first-body (safe-parse-json (:body first-resp))
        [response body vision-degraded?]
        (if (vision-rejection? (:status first-resp)
                               (or first-body (:body first-resp))
                               images)
          (let [r (post! nil)
                b (safe-parse-json (:body r))]
            [r b true])
          [first-resp first-body false])
        status (:status response)]
    (when (or (nil? status) (>= status 400))
      (throw (ex-info (str "Provider request failed with status " status)
                      {:status status
                       :body (redact/redact-secrets (:body response))})))
    (when (nil? body)
      (throw (ex-info (str "Provider returned non-JSON response (status " status ")")
                      {:status status
                       :body (redact/redact-secrets (:body response))})))
    [status body vision-degraded?]))

(defn- complete-openai-compatible
  [provider {:keys [model messages images provider/config]
             :as request}]
  (let [_base-url (require-config config :base-url)
        api-key (when (= :openai-compatible provider)
                  (require-config config :api-key))
        ;; Optional tools ride the request (:provider/tools, set by core from
        ;; config). Absent → fail-closed default: the core registry only, not
        ;; the dormant extras (owner directive 2026-11-12: capabilities must
        ;; be optional).
        advertised-tools (or (:provider/tools request) (tools/active-definitions nil))
        post! (fn [imgs]
                (http/post (openai-url config)
                           {:headers (cond-> {"content-type" "application/json"}
                                       api-key
                                       (assoc "authorization" (str "Bearer " api-key)))
                            :throw false
                            :timeout (or (:timeout-ms config) 60000)
                            :body (json/generate-string
                                   {:model model
                                    :messages (openai-wire-messages
                                               (vision/attach-to-first-user messages imgs :openai))
                                    :tools advertised-tools})}))
        [status body vision-degraded?] (perform-with-vision-fallback post! images)
        choice (get-in body [:choices 0])
        message (:message choice)
        finish-reason (:finish_reason choice)
        content (:content message)
        tool-calls (:tool_calls message)]
    (when (and (str/blank? content) (empty? tool-calls))
      (throw (ex-info "Provider response did not include assistant content"
                      {:status status})))
    (cond-> {:role :assistant}
      (not (str/blank? content)) (assoc :content content)
      finish-reason (assoc :finish/reason finish-reason)
      (:usage body) (assoc :usage (openai-usage body))
      (seq tool-calls) (assoc :tool/requests (mapv openai-tool-request tool-calls))
      vision-degraded? (assoc :vision/degraded true))))

(defmethod complete :openai-compatible [_ request]
  (complete-openai-compatible :openai-compatible request))

(defmethod complete :infered-local [_ request]
  (complete-openai-compatible :infered-local request))

(defmethod complete :infered-router [_ request]
  (complete-openai-compatible :infered-router request))

(defn- anthropic-url [{:keys [base-url]}]
  (str (str/replace (or base-url "") #"/+$" "") "/messages"))

(defn- anthropic-tool-request [block]
  (when (= "tool_use" (:type block))
    (cond-> {:tool/name (:name block)
             :tool/args (or (:input block) {})}
      (:id block) (assoc :tool/id (:id block)))))

(defn- anthropic-extract [content-blocks]
  (let [text (->> content-blocks
                  (filter #(= "text" (:type %)))
                  (map :text)
                  (str/join ""))
        tool-requests (->> content-blocks
                           (map anthropic-tool-request)
                           (remove nil?)
                           vec)]
    (cond-> {:role :assistant}
      (not (str/blank? text)) (assoc :content text)
      (seq tool-requests) (assoc :tool/requests tool-requests))))

(defn- anthropic-usage [body]
  (when-let [usage (:usage body)]
    {:tokens/input (or (:input_tokens usage) 0)
     :tokens/output (or (:output_tokens usage) 0)
     :tokens/cache-read (or (:cache_read_input_tokens usage) 0)
     :tokens/cache-write (or (:cache_creation_input_tokens usage) 0)}))

(defn anthropic-tools
  "Translate OpenAI function-calling tool definitions into Anthropic Messages
   API schemas (`:name`, `:description`, `:input_schema`)."
  [defs]
  (mapv (fn [d]
          (let [f (:function d)]
            {:name (:name f)
             :description (:description f)
             :input_schema (:parameters f)}))
        defs))

(defn anthropic-wire-payload
  "Translate internal messages into `{:system str-or-nil :messages [...]}`
   for the Anthropic Messages API: extracts `:system` messages into the
   top-level `:system` string, converts `:tool/requests` into assistant
   `tool_use` blocks preserving `:tool/id` (or synthesizing `toolu-N`),
   and converts `:tool/results` into user `tool_result` blocks matching each
   `tool_use_id`."
  [messages]
  (let [assign-ids (fn [reqs next-id]
                     (reduce (fn [[ids nid] req]
                               (if-let [explicit (:tool/id req)]
                                 [(conj ids (str explicit)) nid]
                                 [(conj ids (str "toolu-" nid)) (inc nid)]))
                             [[] next-id]
                             reqs))]
    (loop [in (seq messages) sys-parts [] out [] pending-ids [] next-id 0]
      (if-let [m (first in)]
        (cond
          (contains? #{:system "system"} (:role m))
          (recur (next in)
                 (cond-> sys-parts
                   (not (str/blank? (str (:content m)))) (conj (str (:content m))))
                 out
                 pending-ids
                 next-id)

          (:tool/requests m)
          (let [[ids next-id*] (assign-ids (:tool/requests m) next-id)
                blocks (cond-> []
                         (not (str/blank? (str (:content m))))
                         (conj {:type "text" :text (:content m)})
                         true
                         (into (map (fn [req id]
                                      {:type "tool_use"
                                       :id id
                                       :name (name (:tool/name req))
                                       :input (or (:tool/args req) {})})
                                    (:tool/requests m) ids)))]
            (recur (next in) sys-parts
                   (conj out {:role "assistant" :content blocks})
                   ids next-id*))

          (:tool/results m)
          (let [blocks (vec (map-indexed
                             (fn [idx result]
                               {:type "tool_result"
                                :tool_use_id (or (nth pending-ids idx nil)
                                                 (str "toolu-orphan-" idx))
                                :content (pr-str result)})
                             (:tool/results m)))]
            (recur (next in) sys-parts
                   (conj out {:role "user" :content blocks})
                   [] next-id))

          :else
          (recur (next in) sys-parts
                 (conj out {:role (some-> (:role m) name) :content (:content m)})
                 [] next-id))
        (cond-> {:messages out}
          (seq sys-parts) (assoc :system (str/join "\n\n" sys-parts)))))))

(defn- with-cache-marker [msg]
  (let [c (:content msg)]
    (cond
      (string? c)
      (assoc msg :content [{:type "text" :text c :cache_control {:type "ephemeral"}}])

      (and (vector? c) (seq c) (map? (peek c)))
      (assoc msg :content (conj (pop c) (assoc (peek c) :cache_control {:type "ephemeral"})))

      :else msg)))

(defn- mark-anthropic-cache-messages [msgs]
  (let [n (count msgs)]
    (if (<= n 1)
      msgs
      (let [mark-start (max 0 (- n 2))]
        (vec (map-indexed (fn [idx m]
                            (if (>= idx mark-start) (with-cache-marker m) m))
                          msgs))))))

(defn- mark-anthropic-cache-tools [tools-payload]
  (if (and (vector? tools-payload) (seq tools-payload))
    (conj (pop tools-payload)
          (assoc (peek tools-payload) :cache_control {:type "ephemeral"}))
    tools-payload))

(defmethod complete :anthropic-compatible [_ {:keys [model messages images provider/config]
                                              :as request}]
  (let [_base-url (require-config config :base-url)
        api-key (require-config config :api-key)
        advertised-tools (or (:provider/tools request) (tools/active-definitions nil))
        tools-payload (mark-anthropic-cache-tools (anthropic-tools advertised-tools))
        post! (fn [imgs]
                (let [wire (anthropic-wire-payload
                            (vision/attach-to-first-user messages imgs :anthropic))
                      payload (cond-> {:model model
                                       :max_tokens (compression/bounded-output-tokens
                                                    model (or (:max-tokens config) 4096))
                                       :messages (mark-anthropic-cache-messages (:messages wire))}
                                (not (str/blank? (:system wire)))
                                (assoc :system [{:type "text"
                                                 :text (:system wire)
                                                 :cache_control {:type "ephemeral"}}])
                                (seq tools-payload)
                                (assoc :tools tools-payload))]
                  (http/post (anthropic-url config)
                             {:headers {"x-api-key" api-key
                                        "anthropic-version" "2023-06-01"
                                        "content-type" "application/json"}
                              :throw false
                              :timeout (or (:timeout-ms config) 60000)
                              :body (json/generate-string payload)})))
        [status body vision-degraded?] (perform-with-vision-fallback post! images)
        content-blocks (:content body)]
    (when (empty? content-blocks)
      (throw (ex-info "Provider response did not include content"
                      {:status status})))
    (cond-> (anthropic-extract content-blocks)
      (:stop_reason body) (assoc :finish/reason (:stop_reason body))
      (:usage body) (assoc :usage (anthropic-usage body))
      vision-degraded? (assoc :vision/degraded true))))

(defmethod complete :default [provider _]
  (throw (ex-info (str "Unsupported provider: " provider)
                  {:provider provider})))
