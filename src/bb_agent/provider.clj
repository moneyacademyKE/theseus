(ns bb-agent.provider
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bb-agent.tools :as tools]))

(defmulti complete (fn [provider _request] provider))

(defn- final-tool-message [messages]
  (some->> messages
           reverse
           (filter #(= :tool (:role %)))
           first))

(defn- parse-tool-results [messages]
  (some-> (final-tool-message messages) :tool/results))

(defn- memory-match-texts [request]
  (mapv :memory/text (:memory/matches request)))

(defmethod complete :fake [_ {:keys [messages] :as request}]
  (let [prompt (:content (last messages))
        tool-results (parse-tool-results messages)
        memory-texts (memory-match-texts request)]
    (cond
      (and (= "use memory theseus" prompt)
           (seq memory-texts))
      {:role :assistant
       :content (str "memory=" (first memory-texts))}

      (and (= "status please" prompt)
           (nil? tool-results))
      {:role :assistant
       :content "status=ok"}

      (and (str/starts-with? prompt "try denied shell ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "shell"
                        :tool/args {:cmd (subs prompt (count "try denied shell "))}}]}

      (and (str/starts-with? prompt "try approved shell ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "shell"
                        :approval/policy :auto-all
                        :tool/args {:cmd (subs prompt (count "try approved shell "))}}]}

      (and (str/starts-with? prompt "try approved read_file ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "read_file"
                        :approval/policy :auto-all
                        :tool/args {:path (subs prompt (count "try approved read_file "))}}]}

      (and (str/starts-with? prompt "try approved write_file ")
           (nil? tool-results))
      (let [[path content] (str/split (subs prompt (count "try approved write_file ")) #"\|" 2)]
        {:role :assistant
         :tool/requests [{:tool/name "write_file"
                          :approval/policy :auto-all
                          :tool/args {:path path
                                      :content (or content "")
                                      :create-dirs? true}}]})

      (and (str/starts-with? prompt "try approved search ")
           (nil? tool-results))
      (let [[path query] (str/split (subs prompt (count "try approved search ")) #"\|" 2)]
        {:role :assistant
         :tool/requests [{:tool/name "search"
                          :approval/policy :auto-all
                          :tool/args {:path path
                                      :query (or query "")}}]})

      (and (str/starts-with? prompt "try approved git_status ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "git_status"
                        :approval/policy :auto-all
                        :tool/args {:cwd (subs prompt (count "try approved git_status "))}}]}

      (and (str/starts-with? prompt "try approved browser ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "browser_cli"
                        :approval/policy :auto-all
                        :tool/args {:url (subs prompt (count "try approved browser "))}}]}

      (and (str/starts-with? prompt "try approved document ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "document_read"
                        :approval/policy :auto-all
                        :tool/args {:path (subs prompt (count "try approved document "))}}]}

      tool-results
      {:role :assistant
       :content (str "tool-results=" (pr-str tool-results))}

      :else
      {:role :assistant
       :content (if (= "say pong" prompt)
                  "pong"
                  (str "fake: " prompt))})))

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

(defmethod complete :openai-compatible [_ {:keys [model messages provider/config]}]
  (let [_base-url (require-config config :base-url)
        api-key (require-config config :api-key)
        response (http/post (openai-url config)
                            {:headers {"authorization" (str "Bearer " api-key)
                                       "content-type" "application/json"}
                             :throw false
                             :timeout 60000
                             :body (json/generate-string {:model model
                                                          :messages (openai-wire-messages messages)
                                                          :tools tools/definitions})})
        body (json/parse-string (:body response) keyword)
        status (:status response)
        message (get-in body [:choices 0 :message])
        content (:content message)
        tool-calls (:tool_calls message)]
    (when (or (nil? status) (>= status 400))
      (throw (ex-info (str "Provider request failed with status " status)
                      {:status status})))
    (when (and (str/blank? content) (empty? tool-calls))
      (throw (ex-info "Provider response did not include assistant content"
                      {:status status})))
    (cond-> {:role :assistant}
      (not (str/blank? content)) (assoc :content content)
      (:usage body) (assoc :usage (openai-usage body))
      (seq tool-calls) (assoc :tool/requests (mapv openai-tool-request tool-calls)))))

(defn- anthropic-url [{:keys [base-url]}]
  (str (str/replace (or base-url "") #"/+$" "") "/messages"))

(defn- anthropic-tool-request [block]
  (when (= "tool_use" (:type block))
    {:tool/name (:name block)
     :tool/args (:input block)}))

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

(defmethod complete :anthropic-compatible [_ {:keys [model messages provider/config]}]
  (let [_base-url (require-config config :base-url)
        api-key (require-config config :api-key)
        response (http/post (anthropic-url config)
                            {:headers {"x-api-key" api-key
                                       "anthropic-version" "2023-06-01"
                                       "content-type" "application/json"}
                             :throw false
                             :timeout 60000
                             :body (json/generate-string {:model model
                                                          :max_tokens 4096
                                                          :messages messages})})
        body (json/parse-string (:body response) keyword)
        status (:status response)
        content-blocks (:content body)]
    (when (or (nil? status) (>= status 400))
      (throw (ex-info (str "Provider request failed with status " status)
                      {:status status})))
    (when (empty? content-blocks)
      (throw (ex-info "Provider response did not include content"
                      {:status status})))
    (cond-> (anthropic-extract content-blocks)
      (:usage body) (assoc :usage (anthropic-usage body)))))

(defmethod complete :default [provider _]
  (throw (ex-info (str "Unsupported provider: " provider)
                  {:provider provider})))
