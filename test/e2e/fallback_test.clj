(ns e2e.fallback-test
  "Provider fallback chain as data: an ordered list of second
  opinions. A failing primary (auth, infra, unknown — the
  classifier annotates which) advances to the next step; the last
  error carries the whole tried-ledger. The e2e drives the real
  run-turn! path: an unsupported primary falls back to the fake
  provider with retries and breaker untouched per step."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.fallback :as fallback]
            [clojure.test :refer [deftest is]]))

(deftest try-chain-annotates-and-advances
  (let [calls (atom [])
        call (fn [step]
               (swap! calls conj (:provider step))
               (if (= :broken (:provider step))
                 (throw (ex-info "Provider request failed with status 503" {}))
                 {:role :assistant :content "ok"}))
        result (fallback/try-chain [{:provider :broken} {:provider :fake}] call)]
    (is (= [:broken :fake] @calls) "advanced past the broken step")
    (is (= "ok" (:content result)))
    (is (= [{:fallback/provider :broken
             :fallback/kind :infra
             :fallback/reason "Provider request failed with status 503"}]
           (:fallback/tried result)))))

(deftest try-chain-exhaustion-throws-with-ledger
  (let [call (fn [_step] (throw (ex-info "invalid api key" {})))]
    (is (thrown-with-msg? Exception #"All providers in fallback chain failed"
                          (fallback/try-chain [{:provider :a} {:provider :b}] call)))
    (try (fallback/try-chain [{:provider :a} {:provider :b}] call)
         (catch Exception e
           (is (= [:auth :auth] (mapv :fallback/kind (:fallback/tried (ex-data e))))
               "auth never retries within a step, and the chain still advances")))))

(deftest try-chain-empty-throws
  (is (thrown? Exception (fallback/try-chain [] identity))))

(deftest run-turn-falls-back-to-fake
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-fallback-"})]
    (with-redefs [config/home (constantly (str home))]
      (try
        (let [turn (core/run-turn! {:session/id "fb"
                                    :provider :no-such-provider
                                    :model "whatever"
                                    :provider/fallbacks [{:provider :fake
                                                          :model "fake-deterministic"}]}
                                   "say pong")]
          (is (= "pong" (:assistant/final turn)) "secondary answered")
          (is (= :fake (:provider turn)) "turn reports the primary as configured")
          (is (= [{:fallback/provider :no-such-provider
                   :fallback/kind :unknown
                   :fallback/reason "Unsupported provider: :no-such-provider"}]
                 (:fallback/tried turn))
              "the failed primary lands in the tried-ledger"))
        (finally
          (fs/delete-tree home))))))
