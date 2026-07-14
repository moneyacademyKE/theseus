(ns bb-agent.core
  (:require [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.provider :as provider]
            [bb-agent.session :as session]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [clojure.string :as str]))

(def ^:private max-tool-rounds 8)

(defn- tool-error-summary [results]
  (let [names (->> results (map :tool/name) (str/join ", "))]
    (str "Tool execution finished without final answer: " names)))

(defn- tool-results-summary [results]
  (str "tool-results=" (pr-str results)))

(defn- memory-system-message [matches]
  {:role :system
   :content (str "Relevant memories:\n"
                 (str/join "\n" (map :memory/text matches)))})

(defn- initial-messages [prompt memory-matches]
  (cond-> []
    (seq memory-matches) (conj (memory-system-message memory-matches))
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

(defn- finish-turn [id cfg prompt memory-matches turn final-content usage]
  (let [completed (assoc turn
                         :session/id id
                         :user/input prompt
                         :provider (:provider cfg)
                         :model (:model cfg)
                         :memory/backend (memory/backend)
                         :memory/matches memory-matches
                         :assistant/final final-content)]
    (session/touch-metadata! id cfg)
    (session/append-turn! id completed)
    (usage/append-event! (usage/event {:session-id id
                                       :provider (:provider cfg)
                                       :model (:model cfg)
                                       :prompt prompt
                                       :final final-content
                                       :usage usage}))
    completed))

(defn run-turn! [{:keys [provider model session/id] :as cfg} prompt]
  (let [cfg (model/effective-config cfg)
        provider (:provider cfg)
        model (:model cfg)
        id (:session/id cfg)
        metadata (session/load-metadata id)
        cfg (cond-> cfg
              (:cwd metadata) (assoc :cwd (:cwd metadata)))
        memory-matches (memory/attach-memories prompt)]
    (loop [messages (initial-messages prompt memory-matches)
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
            response (provider/complete provider request)
            tool-requests (:tool/requests response)
            tool-results (when (seq tool-requests)
                            (mapv #(tool/handle-tool-request % cfg) tool-requests))
            turn* (append-tool-round turn response (or tool-results []))]
        (if-let [content (:content response)]
          (finish-turn id cfg prompt memory-matches turn* content (:usage response))
          (if (seq tool-requests)
            (if (every? #(= :denied (:status %)) tool-results)
              (finish-turn id cfg prompt memory-matches turn* (tool-results-summary tool-results) (:usage response))
              (recur (continue-messages messages response tool-results)
                     turn*
                     (dec rounds-left)))
            (finish-turn id cfg prompt memory-matches turn* (tool-error-summary (:tool/results turn*)) (:usage response))))))))
