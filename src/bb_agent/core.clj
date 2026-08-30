(ns bb-agent.core
  (:require [bb-agent.brain :as brain]
            [bb-agent.circuit-breaker :as cb]
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

(defn- initial-messages [prompt memory-matches semantic-ctx brain-ctx]
  (cond-> []
    (seq memory-matches) (conj (memory-system-message memory-matches))
    semantic-ctx (conj (semantic-system-message semantic-ctx))
    (seq brain-ctx) (conj (brain-system-message brain-ctx))
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
    (when (semantic-memory/enabled? cfg)
      (semantic-memory/index-session! id))
    (usage/append-event! (usage/event {:session-id id
                                       :provider (or (:provider turn) (:provider cfg))
                                       :model (:model cfg)
                                       :prompt prompt
                                       :final final-content
                                       :usage usage
                                       :fallback-tried (not-empty (:fallback/tried turn))
                                       :fallback-served (:fallback/served-by turn)}))
    completed))

(defn run-turn! [{:keys [provider model session/id] :as cfg} prompt]
  (let [cfg (model/effective-config cfg)
        provider (:provider cfg)
        model (:model cfg)
        id (:session/id cfg)
        metadata (session/load-metadata id)
        cfg (cond-> cfg
              (:cwd metadata) (assoc :cwd (:cwd metadata)))
        memory-matches (memory/attach-memories prompt)
        semantic-ctx (semantic-memory/attach-context prompt cfg)
        brain-ctx (brain/load-brain)]
    (loop [messages (initial-messages prompt memory-matches semantic-ctx brain-ctx)
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
            response (complete-chain cfg request)
            tool-requests (:tool/requests response)
            tool-results (when (seq tool-requests)
                            (mapv #(tool/handle-tool-request % cfg) tool-requests))
            turn* (cond-> (append-tool-round turn response (or tool-results []))
                    (:fallback/tried response)
                    (assoc :fallback/tried (:fallback/tried response))
                    (:fallback/served-by response)
                    (assoc :provider (:fallback/served-by response)))]
        (if-let [content (:content response)]
          (finish-turn id cfg prompt memory-matches semantic-ctx turn* content (:usage response))
          (if (seq tool-requests)
            (if (every? #(= :denied (:status %)) tool-results)
              (finish-turn id cfg prompt memory-matches semantic-ctx turn* (tool-results-summary tool-results) (:usage response))
              (recur (continue-messages messages response tool-results)
                     turn*
                     (dec rounds-left)))
            (finish-turn id cfg prompt memory-matches semantic-ctx turn* (tool-error-summary (:tool/results turn*)) (:usage response))))))))
