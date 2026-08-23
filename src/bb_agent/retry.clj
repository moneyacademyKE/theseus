(ns bb-agent.retry
  "Retry/backoff executor: the caller that makes the circuit breaker
  and error classifier true. Ported shape from hermes-beam's retry
  loop around provider calls, decomplected:

    - pure function of opts + thunk, never throws
    - clock, sleep, and rand arrive as arguments, so every path is
      deterministically testable
    - the breaker is a value in (`:breaker`) and a value out (result
      `:breaker`); callers own any atom they want for shared state

  Failure policy: classify, then retry only what the classifier calls
  retryable (rate limits, infra). Auth/logic errors fail fast and do
  not count toward tripping the breaker; only retryable failures do."
  (:require [bb-agent.circuit-breaker :as cb]
            [bb-agent.error-classifier :as ec]))

(defn defaults
  "Sane production defaults. `:max-attempts` caps TOTAL calls (3 = one
  original + two retries). Override anything per call; config.edn may
  merge over this via a `:retry` map."
  []
  {:max-attempts 3
   :base-ms 100
   :max-ms 5000
   :jitter 0.5
   :breaker nil
   :breaker-key :provider
   :clock (fn [] (System/currentTimeMillis))
   :sleep (fn [ms] (Thread/sleep (long ms)))
   :rand rand})

(defn backoff-ms
  "Delay to sleep after failure on `attempt` (1-based), before the next:
  base * 2^(attempt-1), clamped to :max-ms, scaled by jitter factor
  (1 + j*(2r-1)) for r in [0,1), finally clamped to :max-ms again."
  [opts attempt]
  (let [{:keys [base-ms max-ms jitter rand]} (merge (defaults) opts)
        raw (min (* (long base-ms)
                    (long (Math/pow 2 (dec (long attempt)))))
                 (long max-ms))
        factor (+ 1 (* (double jitter) (- (* 2 (double (rand))) 1)))]
    (long (min (long max-ms) (Math/round (* raw factor))))))

(defn with-retries
  "Execute `(thunk)` with capped attempts and exponential backoff.
  Never throws. Returns a map with `:outcome`, `:attempts`, and the
  final `:breaker` value (nil when none was supplied):

    {:outcome :success       :value v}
    {:outcome :non-retryable :error e :kind k}
    {:outcome :exhausted     :error e :kind k}
    {:outcome :breaker-open               }

  `:attempts` counts calls actually made; breaker-open short-circuits
  before the first call, so it reports 0."
  [{:keys [breaker breaker-key max-attempts] :as opts} thunk]
  (let [{:keys [breaker breaker-key max-attempts] :as opts} (merge (defaults) opts)]
    (letfn [(now-secs [] (quot (long ((:clock opts))) 1000))]
    (loop [attempt 1 br breaker]
      (let [[allowed? br'] (if br
                             (cb/check br breaker-key (now-secs))
                             [true br])]
        (if-not allowed?
          {:outcome :breaker-open :attempts (dec attempt) :breaker br'}
          (let [result (try {:ok? true :value (thunk)}
                            (catch Exception e
                              (merge {:ok? false :error e}
                                     (ec/classify (ex-message e)))))]
            (if (:ok? result)
              {:outcome :success
               :value (:value result)
               :attempts attempt
               :breaker (if br (cb/record-success br' breaker-key) br)}
              (if-not (ec/retryable? result)
                {:outcome :non-retryable
                 :error (:error result)
                 :kind (:kind result)
                 :attempts attempt
                 :breaker br'}
                (let [br'' (if br
                             (cb/record-failure br' breaker-key (now-secs))
                             br')]
                  (if (>= attempt (long max-attempts))
                    {:outcome :exhausted
                     :error (:error result)
                     :kind (:kind result)
                     :attempts attempt
                     :breaker br''}
                    (do ((:sleep opts) (backoff-ms opts attempt))
                        (recur (inc attempt) br'')))))))))))))
