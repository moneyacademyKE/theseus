(ns bb-agent.fallback
  "Provider fallback chain as data: an ordered list of second
  opinions, walked until one answers. Advancing is the chain's whole
  policy — an auth failure or a bad model id on one provider says
  nothing about the next — but every failure is classified
  (error-classifier) and recorded, so the annotation says WHY each
  step was left. `call` arrives as an argument, so the chain itself
  is a pure function and retries/breakers stay the caller's
  composition."
  (:require [bb-agent.error-classifier :as ec]))

(defn try-chain
  "Walk `steps` (each a map with at least :provider), calling
  `(call step)` until one succeeds. Returns the success value —
  tagged with :fallback/tried when it's a map — or throws an
  ex-info whose ex-data carries the full :fallback/tried ledger
  of {:fallback/provider :fallback/kind :fallback/reason} entries."
  ([steps call] (try-chain steps call []))
  ([steps call tried]
   (loop [steps steps tried tried]
     (if-not (seq steps)
       (throw (ex-info "All providers in fallback chain failed"
                       {:fallback/tried tried}))
       (let [step (first steps)
             result (try {:ok? true :value (call step)}
                         (catch Exception e
                           {:ok? false :error e
                            :kind (:kind (ec/classify (ex-message e)))}))]
         (if (:ok? result)
           (let [value (:value result)]
             (cond-> value
               (map? value) (assoc :fallback/tried tried
                                   :fallback/served-by (:provider step))))
           (recur (next steps)
                  (conj tried {:fallback/provider (:provider step)
                               :fallback/kind (:kind result)
                               :fallback/reason (ex-message (:error result))}))))))))
