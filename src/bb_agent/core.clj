(ns bb-agent.core
  (:require [bb-agent.brain :as brain]
            [bb-agent.circuit-breaker :as cb]
            [bb-agent.compression :as compression]
            [bb-agent.fallback :as fallback]
            [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.provider :as provider]
            [bb-agent.retry :as retry]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.session :as session]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [clojure.string :as str]))

(def ^:private max-tool-rounds 8)

(def ^:private provider-breaker
  "Shared per-provider breaker threaded through retry calls. The
  breaker modules stay pure; this atom is the one mutable seam."
  (atom (cb/breaker 5 30)))

(defn- complete-retrying
  "Wrap provider/complete with retry/backoff + the shared breaker.
  config.edn may override retry knobs via a `:retry` map."
  [cfg provider request]
  (let [opts (-> (retry/defaults)
                 (merge (:retry cfg))
                 (assoc :breaker @provider-breaker
                        :breaker-key provider))
        result (retry/with-retries opts #(provider/complete provider request))]
    (when (:breaker result) (reset! provider-breaker (:breaker result)))
    (case (:outcome result)
      :success (:value result)
      :breaker-open (throw (ex-info (str "Provider circuit breaker open for " provider)
                                    {:provider provider :outcome :breaker-open}))
      (throw (:error result)))))

(defn- request-for [base step]
  (-> base
      (assoc :provider (:provider step))
      (assoc :model (:model step))
      (assoc :provider/config (:provider/config step))))

(defn- fallback-steps [cfg base-request]
  (let [primary {:provider (:provider base-request)
                 :model (:model base-request)
                 :provider/config (:provider/config base-request)}]
    (into [primary]
          (map (fn [fb]
                 (let [p (keyword (:provider fb))]
                   {:provider p
                    :model (:model fb)
                    :provider/config (get-in cfg [:providers p])})))
          (:provider/fallbacks cfg))))

(defn- complete-chain
  "Primary provider first (retries + breaker intact), then
  :provider/fallbacks as data — each step its own provider, model,
  and provider config. Nothing configured: exactly the old
  single-provider path, byte for byte."
  [cfg base-request]
  (let [steps (fallback-steps cfg base-request)]
    (if (= 1 (count steps))
      (complete-retrying cfg (:provider base-request) base-request)
      (fallback/try-chain steps
                          #(complete-retrying cfg (:provider %)
                                              (request-for base-request %))))))

(defn- tool-error-summary [results]
  (let [names (->> results (map :tool/name) (str/join ", "))]
    (str "Tool execution finished without final answer: " names)))

(defn- tool-results-summary [results]
  (str "tool-results=" (pr-str results)))

(defn- memory-system-message [matches]
  {:role :system
   :content (str "Relevant memories:\n"
                 (str/join "\n" (map :memory/text matches)))})

(defn- semantic-system-message [context]
  {:role :system :content context})

(defn- brain-system-message [brain]
  {:role :system
   :content (str "Agent brain (identity & conventions):\n" brain)})

(defn- history-messages
  "Prior session turns as alternating user/assistant wire messages.
   Bounded by :history-budget-chars (default 24000): over budget, old
   middle turns are compacted by bb-agent.compression — head kept, the
   most recent ~25% kept verbatim, tool payloads pruned, the middle
   summarized through the provider chain."
  [cfg session-id]
  (when session-id
    (let [pairs (->> (session/load-turns session-id)
                     (keep (fn [{:keys [user/input assistant/final]}]
                             (when (and (seq input) (seq final))
                               [{:role :user :content input}
                                {:role :assistant :content final}])))
                     (apply concat)
                     vec)]
      (when (seq pairs)
        (let [budget (or (:history-budget-chars cfg) 24000)]
          (if (<= (compression/count-chars pairs) budget)
            pairs
            (compression/compress pairs
                                  {:protect-first 0
                                   :tail-char-budget (max 200 (quot budget 4))
                                   :call-llm-fn (fn [prompt]
                                                  (:content
                                                   (complete-chain
                                                    cfg
                                                    {:provider (:provider cfg)
                                                     :model (:model cfg)
                                                     :messages [{:role :user :content prompt}]
                                                     :provider/config (get-in cfg [:providers (:provider cfg)])})))})))))))

(defn- initial-messages [prompt memory-matches semantic-ctx brain-ctx history]
  (cond-> []
    (seq memory-matches) (conj (memory-system-message memory-matches))
    semantic-ctx (conj (semantic-system-message semantic-ctx))
    (seq brain-ctx) (conj (brain-system-message brain-ctx))
    true (into (or history []))
    true (conj {:role :user :content prompt})))

(defn- tool-call-message [tool-requests]
  {:role :assistant
   :tool/requests tool-requests})

(defn- tool-result-message [tool-results]
  {:role :tool
   :content (pr-str tool-results)
   :tool/results tool-results})

(defn- append-tool-round [turn response tool-results]
  (-> turn
      (update :tool/requests into (or (:tool/requests response) []))
      (update :tool/results into tool-results)))

(defn- continue-messages [messages response tool-results]
  (cond-> messages
    (seq (:tool/requests response)) (conj (tool-call-message (:tool/requests response)))
    (seq tool-results) (conj (tool-result-message tool-results))))

(defn- finish-turn [id cfg prompt memory-matches semantic-ctx turn final-content usage]
  (let [completed (assoc turn
                         :session/id id
                         :user/input prompt
                         :provider (or (:provider turn) (:provider cfg))
                         :model (:model cfg)
                         :memory/backend (memory/backend)
                         :memory/matches memory-matches
                         :semantic/context semantic-ctx
                         :assistant/final final-content)]
    (session/touch-metadata! id cfg)
    (session/append-turn! id completed)
    (when (and (semantic-memory/enabled? cfg)
               (not (:session/shared? cfg)))
      (semantic-memory/index-session! id))
    (usage/append-event! (usage/event {:session-id id
                                       :provider (or (:provider turn) (:provider cfg))
                                       :model (:model cfg)
                                       :prompt prompt
                                       :final final-content
                                       :usage usage
                                       :fallback-tried (not-empty (:fallback/tried turn))
                                       :fallback-served (:fallback/served-by turn)
                                       :ok (:turn/ok completed true)}))
    completed))

(defn run-turn! [{:keys [provider model session/id] :as cfg} prompt]
  (let [cfg (model/effective-config cfg)
        provider (:provider cfg)
        id (:session/id cfg)
        user-images (:user/images cfg)
        model (if (and (seq user-images) (:vision-model cfg))
                (:vision-model cfg)
                (:model cfg))
        metadata (session/load-metadata id)
        cfg (-> cfg
                (cond-> (:cwd metadata) (assoc :cwd (:cwd metadata)))
                ;; Record the model that will actually serve the turn —
                ;; image turns use :vision-model, and usage stats must not
                ;; credit the primary model for a fallback/vision serving.
                (assoc :model model))
        shared? (:session/shared? cfg)
        memory-matches (if shared? [] (memory/attach-memories prompt))
        semantic-ctx (when-not shared?
                       (semantic-memory/attach-context prompt cfg))
        brain-ctx (brain/load-brain)]
    (loop [messages (initial-messages prompt memory-matches semantic-ctx brain-ctx
                                      (history-messages cfg id))
           turn {:tool/requests []
                 :tool/results []}
           rounds-left max-tool-rounds]
      (when (neg? rounds-left)
        (throw (ex-info "Exceeded tool rounds" {:rounds max-tool-rounds})))
      (let [request {:provider provider
                     :model model
                     :messages messages
                     :memory/matches memory-matches
                     :provider/config (get-in cfg [:providers provider])}
            request (cond-> request (seq user-images) (assoc :images user-images))
            response (complete-chain cfg request)
            tool-requests (:tool/requests response)
            ;; Surface the turn's process, not just its result: channels
            ;; render one compact line per tool call. A broken renderer
            ;; must never kill the turn.
            _ (when-let [emit (:status/emit cfg)]
                (try
                  (run! (fn [req]
                          (emit {:status :tool/call
                                 :tool (:tool/name req)
                                 :args (:tool/args req)}))
                        tool-requests)
                  (catch Exception e
                    (binding [*out* *err*]
                      (println (str "status emit failed: " (.getMessage e)))))))
            tool-results (when (seq tool-requests)
                            (mapv #(tool/handle-tool-request % cfg) tool-requests))
            turn* (cond-> (append-tool-round turn response (or tool-results []))
                    (:fallback/tried response)
                    (assoc :fallback/tried (:fallback/tried response))
                    (:fallback/served-by response)
                    (assoc :provider (:fallback/served-by response)))]
        (if (seq tool-requests)
          (if (every? #(= :denied (:status %)) tool-results)
            (finish-turn id cfg prompt memory-matches semantic-ctx turn* (tool-results-summary tool-results) (:usage response))
            (recur (continue-messages messages response tool-results)
                   turn*
                   (dec rounds-left)))
          (if-let [content (:content response)]
            (finish-turn id cfg prompt memory-matches semantic-ctx turn* content (:usage response))
            (finish-turn id cfg prompt memory-matches semantic-ctx (assoc turn* :turn/ok false) (tool-error-summary (:tool/results turn*)) (:usage response))))))))
