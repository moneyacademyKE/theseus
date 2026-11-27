(ns bb-agent.turn-diagnostics-test
  (:require [bb-agent.turn-diagnostics :as td]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest build-evidence-only-diagnostics-test
  (testing "classifies provider fallback exhaustion with observed evidence and explicit unknowns"
    (let [ex (ex-info "All providers in fallback chain failed"
                      {:fallback/tried [{:fallback/provider :infered-local
                                         :fallback/kind :infra
                                         :fallback/reason "Provider request failed with status 503"}]})
          diag (td/assess-failure {:session-id "s-1"
                                   :exception ex
                                   :tool-count 3
                                   :cancelled? false})]
      (is (= :provider-exhausted (:outcome diag)))
      (is (seq (:evidence diag)))
      (is (some #(str/includes? % "infered-local") (:evidence diag)))
      (is (seq (:unknowns diag)) "must explicitly record unknowns rather than inventing root causes")
      (is (seq (:next-steps diag)))
      (is (not (contains? diag :prompt)) "never leaks raw prompts or args")))

  (testing "classifies user cancel and steer terminations distinctly"
    (is (= :cancelled-by-user (:outcome (td/assess-failure {:termination :user-cancelled}))))
    (is (= :redirected-by-user (:outcome (td/assess-failure {:termination :user-steer}))))))
