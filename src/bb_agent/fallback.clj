(ns bb-agent.fallback
  "Provider fallback chain as data: an ordered list of second
  opinions, walked until one answers. Advancing is the chain's whole
  policy — an auth failure or a bad model id on one provider says
  nothing about the next — but every failure is classified
  (error-classifier) and recorded, so the annotation says WHY each
  step was left. `call` arrives as an argument, so the chain itself
  is a pure function and retries/breakers stay the caller's
  composition.

  A rescue is a rescue only if something failed. Answering on the
  first step stamps nothing: the chain's head IS the primary, so
  stamping it made every healthy turn look like the fallback saved
  it — a phantom that downstream analytics (bb-agent.rsi) read as
  31% fallback pressure when the real rate was one turn in 195.
  When a rescue is real, both :fallback/served-by and
  :fallback/model are recorded: chains are often model-level (same
  provider, cheaper model on the tail), and the provider alone
  cannot say what answered."
  (:require [bb-agent.error-classifier :as ec]))

(defn try-chain
  "Walk `steps` (each a map with at least :provider), calling
  `(call step)` until one succeeds. Returns the success value; when
  at least one step failed first, a map value is tagged with the
  :fallback/tried ledger of {:fallback/provider :fallback/kind
  :fallback/reason} entries plus :fallback/served-by and
  :fallback/model naming what answered. A first-step success is
  returned untouched. Exhausting the chain throws an ex-info whose
  ex-data carries the full tried ledger."
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
             (if (and (map? value) (seq tried))
               (assoc value
                      :fallback/tried tried
                      :fallback/served-by (:provider step)
                      :fallback/model (:model step))
               value))
           (recur (next steps)
                  (conj tried {:fallback/provider (:provider step)
                               :fallback/kind (:kind result)
                               :fallback/reason (ex-message (:error result))}))))))))
