(ns e2e.retry-test
  "Retry/backoff executor tests: pure executor behavior first, then the
  wiring through `bb agent` against a real HTTP provider that fails
  transiently before succeeding."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.circuit-breaker :as cb]
            [bb-agent.retry :as retry]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(defn- opts
  "Executor opts with injected clock/sleep/rand for determinism.
  Returns [opts delays-atom]; delays records every slept ms."
  ([] (opts {}))
  ([over]
   (let [delays (atom [])]
     [(merge {:max-attempts 3
              :base-ms 100
              :max-ms 5000
              :jitter 0
              :rand (constantly 0.5)
              :sleep (fn [ms] (swap! delays conj ms))
              :clock (constantly 0)}
             over)
      delays])))

(deftest backoff-grows-and-caps
  (let [[o _] (opts)]
    (is (= [100 200 400 800] (mapv #(retry/backoff-ms o %) [1 2 3 4]))
        "base * 2^(attempt-1)")
    (is (= 5000 (retry/backoff-ms o 7)) "6400 clamps to max-ms")
    (is (= 5000 (retry/backoff-ms (first (opts {:base-ms 10000 :max-ms 5000})) 1))
        "base above max clamps immediately")))

(deftest backoff-jitter-band
  (testing "jitter j scales delay by (1 + j*(2r-1)), clamped to max-ms"
    (let [low (first (opts {:jitter 0.5 :rand (constantly 0.0)}))
          high (first (opts {:jitter 0.5 :rand (constantly 1.0)}))
          huge (first (opts {:base-ms 4000 :jitter 0.5 :rand (constantly 1.0)}))]
      (is (= 50 (retry/backoff-ms low 1)) "100 * 0.5")
      (is (= 150 (retry/backoff-ms high 1)) "100 * 1.5")
      (is (= 300 (retry/backoff-ms high 2)) "200 * 1.5")
      (is (= 5000 (retry/backoff-ms huge 2)) "8000 clamps to 5000 before jitter, stays 5000"))))

(deftest success-on-first-attempt
  (let [[o delays] (opts {:breaker (cb/breaker 3 60)})]
    (let [{:keys [outcome value attempts breaker]} (retry/with-retries o (constantly :pong))]
      (is (= :success outcome))
      (is (= :pong value))
      (is (= 1 attempts))
      (is (= [] @delays) "no sleeps on success")
      (is (= [true breaker] (cb/check breaker :provider 5))
          "success closes and resets the breaker"))))

(deftest infra-failure-retries-then-succeeds
  (let [[o delays] (opts {:breaker (cb/breaker 3 60)})
        calls (atom 0)
        thunk (fn []
                (if (= 1 (swap! calls inc))
                  (throw (ex-info "Provider request failed with status 503" {}))
                  :recovered))]
    (let [{:keys [outcome value attempts breaker]} (retry/with-retries o thunk)]
      (is (= :success outcome))
      (is (= :recovered value))
      (is (= 2 attempts))
      (is (= [100] @delays) "one backoff between the two attempts")
      (is (= [true breaker] (cb/check breaker :provider 5))
          "one retryable failure stays below threshold"))))

(deftest rate-limit-failure-is-retryable
  (let [[o _] (opts)
        calls (atom 0)
        thunk (fn []
                (if (= 1 (swap! calls inc))
                  (throw (ex-info "Provider request failed with status 429" {}))
                  :ok))]
    (is (= :success (:outcome (retry/with-retries o thunk))))))

(deftest non-retryable-fails-fast-and-never-trips-breaker
  (let [[o delays] (opts {:breaker (cb/breaker 1 60)})]
    (let [{:keys [outcome kind attempts breaker]}
          (retry/with-retries o #(throw (ex-info "401 Unauthorized: invalid api key" {})))]
      (is (= :non-retryable outcome))
      (is (= :auth kind))
      (is (= 1 attempts))
      (is (= [] @delays) "no backoff for non-retryable errors")
      (is (= [true breaker] (cb/check breaker :provider 5))
          "threshold is 1, yet the breaker did not trip: auth does not count"))))

(deftest exhausts-after-max-attempts-and-trips-breaker
  (let [[o delays] (opts {:breaker (cb/breaker 3 60)})]
    (let [{:keys [outcome kind attempts breaker]}
          (retry/with-retries o #(throw (ex-info "504 Gateway Timeout" {})))]
      (is (= :exhausted outcome))
      (is (= :infra kind))
      (is (= 3 attempts) "max-attempts caps total calls")
      (is (= [100 200] @delays) "sleep between attempts, none after the last")
      (is (= [false breaker] (cb/check breaker :provider 30))
          "three consecutive retryable failures trip the circuit open"))))

(deftest open-breaker-short-circuits-without-calling-thunk
  (let [br (-> (cb/breaker 1 60) (cb/record-failure :provider 10))
        [o _] (opts {:breaker br})
        calls (atom 0)]
    (let [{:keys [outcome attempts]} (retry/with-retries o #(swap! calls inc))]
      (is (= :breaker-open outcome))
      (is (zero? attempts) "nothing was attempted")
      (is (zero? @calls) "thunk never ran"))))

(deftest agent-retries-transient-provider-failures-end-to-end
  (testing "bb agent absorbs two 503s from a real HTTP provider, succeeds on the third"
    (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-retry-e2e-"})
          port (with-open [socket (java.net.ServerSocket. 0)]
                 (.getLocalPort socket))
          hits (atom 0)
          stop-server (server/run-server
                       (fn [_req]
                         (if (< (swap! hits inc) 3)
                           {:status 503
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string {:error {:message "upstream unavailable"}})}
                           {:status 200
                            :headers {"content-type" "application/json"}
                            :body (json/generate-string
                                   {:choices [{:message {:role "assistant"
                                                         :content "pong-retry"}}]})}))
                       {:port port})]
      (try
        (fs/create-dirs home)
        (spit (str (fs/path home "config.edn"))
              (pr-str {:provider :openai-compatible
                       :model "test-model"
                       :providers {:openai-compatible
                                   {:base-url (str "http://127.0.0.1:" port "/v1")
                                    :api-key "test-key"}}}))
        (let [result (p/shell {:out :string
                               :err :string
                               :continue true
                               :extra-env {"OPENCRABS_HOME" (str home)}}
                              "bb" "agent" "say pong")]
          (is (= 0 (:exit result)) (:err result))
          (is (= "pong-retry\n" (:out result)))
          (is (= 3 @hits) "two transient failures absorbed by retry"))
        (finally
          (stop-server)
          (fs/delete-tree home))))))
