(ns axiom.phase11-budget-test
  "Budget accounting and exhaustion tests."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.budget :as budget]
            [axiom.core :as core]
            [axiom.decision :as decision]
            [axiom.status :as status]
            [babashka.fs :as fs]))

(deftest usage-normalizes-advisory-result-metadata
  (testing "missing and malformed usage is zero"
    (is (= {:cost-usd 0.0 :tokens 0} (budget/usage nil)))
    (is (= {:cost-usd 0.0 :tokens 0}
           (budget/usage {:usage {:cost-usd "wat" :tokens nil}}))))
  (testing "nested and top-level counters are accepted"
    (is (= {:cost-usd 0.25 :tokens 1200}
           (budget/usage {:usage {:cost-usd "0.25" :tokens "1200"}})))
    (is (= {:cost-usd 0.5 :tokens 7}
           (budget/usage {:cost-usd 0.5 :tokens 7})))))

(deftest configured-budgets-exhaust-before-next-act
  (let [base {:goal {:op :>= :ref :n :value 1}
              :integrity []}]
    (is (= :act (:type (decision/decide (assoc base :max-cost-usd 1.0)
                                        {:n 0}
                                        {:cost-usd 0.99}))))
    (let [a (decision/decide (assoc base :max-cost-usd 1.0)
                             {:n 0}
                             {:cost-usd 1.0})]
      (is (= :halt (:type a)))
      (is (= :budget-exhausted (:reason a)))
      (is (= :cost-usd (:budget a))))
    (let [a (decision/decide (assoc base :max-tokens 10)
                             {:n 0}
                             {:tokens 10})]
      (is (= :halt (:type a)))
      (is (= :tokens (:budget a))))))

(deftest run-accumulates-cost-and-halts-on-next-decision
  (let [dir (str (fs/create-temp-dir))
        calls (atom 0)]
    (try
      (spit (str dir "/n") "0\n")
      (let [cfg {:name "budget-run"
                 :workdir dir
                 :lock (str dir "/.axiom.lock")
                 :log-dir (str dir "/logs")
                 :observers {:n {:sh "cat n" :parse :int}}
                 :goal {:op :>= :ref :n :value 1}
                 :progress :n
                 :integrity []
                 :max-cost-usd 0.01
                 :models ["surplus/gpt-5.4-mini"]
                 :opencode-config {"provider" {"surplus" {"models" {"gpt-5.4-mini" {}}}}}
                 :act {:harness :opencode
                       :prompt "spend once"}
                 :harness-transport (fn [_]
                                      (swap! calls inc)
                                      {:exit 0
                                       :usage {:cost-usd 0.02 :tokens 42}})}
            res (core/run! cfg {:max-iters 5})
            iter0 (read-string (slurp (str dir "/logs/iter-000.edn")))
            summary (status/format-summary cfg nil [iter0])]
        (is (= :halt (:status res)))
        (is (= :budget-exhausted (:reason res)))
        (is (= :cost-usd (get-in res [:detail :budget])))
        (is (= 1 @calls) "budget halt prevents a second act")
        (is (= 0.02 (:cost-usd iter0)))
        (is (= 42 (:tokens iter0)))
        (is (clojure.string/includes? summary "Cost/tokens: 0.02/42")))
      (finally (fs/delete-tree dir)))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase11-budget-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))