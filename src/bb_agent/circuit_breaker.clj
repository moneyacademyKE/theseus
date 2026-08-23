(ns bb-agent.circuit-breaker
  "Per-key circuit breaker as a pure value.

  Ported from hermes-beam's circuit_breaker_actor.gleam: the OTP actor
  becomes a map threaded through explicit transitions, and time arrives
  as an argument so every transition is deterministically testable.
  Semantics preserved exactly:

    - closed     allows; `:threshold` consecutive failures trip it open
    - open       denies until `:cooldown-secs` elapse, then half-open
    - half-open  one trial; success closes and resets, failure reopens

  Callers wanting shared mutable state wrap the breaker in an atom;
  this module stays pure.")

(defn breaker
  "New breaker value. `threshold` consecutive failures trip a key's
  circuit; an open circuit blocks a key for `cooldown-secs`."
  [threshold cooldown-secs]
  {:threshold (long threshold)
   :cooldown-secs (long cooldown-secs)
   :statuses {}})

(defn- status [br key]
  (get-in br [:statuses key] {:state :closed :failures 0}))

(defn check
  "Returns [allowed? breaker'] for `key` at `now-secs`. Checking an
  open circuit past its cooldown transitions it to half-open and
  allows a single trial through."
  [br key now-secs]
  (let [{:keys [state tripped-at]} (status br key)]
    (cond
      (not= state :open)
      [true br]

      (>= (- now-secs (or tripped-at 0)) (:cooldown-secs br))
      [true (assoc-in br [:statuses key :state] :half-open)]

      :else
      [false br])))

(defn record-success
  "A successful call closes the circuit for `key` and resets its
  failure count."
  [br key]
  (assoc-in br [:statuses key] {:state :closed :failures 0}))

(defn record-failure
  "A failed call increments `key`'s failure count; reaching the
  threshold trips the circuit open at `now-secs`."
  [br key now-secs]
  (let [failures (inc (:failures (status br key)))]
    (assoc-in br [:statuses key]
              (if (>= failures (:threshold br))
                {:state :open :failures failures :tripped-at now-secs}
                {:state :closed :failures failures}))))
