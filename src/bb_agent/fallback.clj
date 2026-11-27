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
  (:require [bb-agent.error-classifier :as ec]
            [clojure.string :as str]))

(def ^:private sticky-threshold 4)
(def ^:private sticky-window-ms (* 15 60 1000))

(defn- model-incompatible-with-provider? [provider model]
  (let [m (str/lower-case (str (or model "")))]
    (cond
      (str/blank? m) false
      (and (= :openai-compatible provider) (str/starts-with? m "claude-")) true
      (and (= :anthropic-compatible provider)
           (or (str/starts-with? m "gpt-")
               (str/starts-with? m "o3")
               (str/starts-with? m "o4"))) true
      :else false)))

(defn guard-model-leak
  "Prevent a primary provider's pinned model ID (e.g. claude-*) from leaking
   into an incompatible fallback provider; falls back to the target provider's
   configured :default-model when available."
  [step primary-provider providers-cfg]
  (let [p (:provider step)
        m (:model step)]
    (if (and (not= p primary-provider)
             (model-incompatible-with-provider? p m))
      (if-let [default-m (get-in providers-cfg [p :default-model])]
        (assoc step :model default-m)
        (dissoc step :model))
      step)))

(defn record-rescue!
  "Record a verified fallback rescue for `session-id` in `sticky-atom`."
  ([sticky-atom session-id step]
   (record-rescue! sticky-atom session-id step (System/currentTimeMillis)))
  ([sticky-atom session-id {:keys [provider model]} now-ms]
   (when (and sticky-atom session-id provider)
     (let [cutoff (- (long now-ms) sticky-window-ms)
           k [provider model]]
       (swap! sticky-atom
              (fn [m]
                (let [prev (filterv #(>= % cutoff) (get-in m [session-id k] []))]
                  (assoc-in m [session-id k] (conj prev (long now-ms))))))))))

(defn reorder-with-sticky
  "If a fallback step rescued `session-id` >= sticky-threshold times within
   sticky-window-ms, move it to the front of `chain` for this session."
  ([sticky-atom session-id chain]
   (reorder-with-sticky sticky-atom session-id chain (System/currentTimeMillis)))
  ([sticky-atom session-id chain now-ms]
   (if (or (nil? sticky-atom) (nil? session-id) (< (count chain) 2))
     chain
     (let [cutoff (- (long now-ms) sticky-window-ms)
           sess-map (get @sticky-atom session-id {})
           winner (->> sess-map
                       (map (fn [[k ts]] [k (count (filter #(>= % cutoff) ts))]))
                       (filter #(>= (second %) sticky-threshold))
                       (sort-by (comp - second))
                       ffirst)]
       (if-not winner
         chain
         (let [[wp wm] winner
               match? (fn [step] (and (= wp (:provider step)) (= wm (:model step))))
               promoted (filterv match? chain)
               others (filterv (complement match?) chain)]
           (if (seq promoted)
             (into promoted others)
             chain)))))))

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
   (loop [steps (filter map? steps) tried tried]
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
                  (let [err (:error result)
                        body (some-> (ex-data err) :body str str/trim)
                        body-snip (when (seq body)
                                    (if (> (count body) 180)
                                      (str (subs body 0 180) "…")
                                      body))
                        base-msg (or (ex-message err) (.getName (class err)))]
                    (conj tried {:fallback/provider (:provider step)
                                 :fallback/kind (:kind result)
                                 :fallback/reason (if body-snip
                                                    (str base-msg " — " body-snip)
                                                    base-msg)})))))))))
