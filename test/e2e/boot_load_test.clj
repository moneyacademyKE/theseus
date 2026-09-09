(ns e2e.boot-load-test
  "Boot load-chain regression: a fresh bb process must analyze the full
  require chains that the launchd services boot into. 2026-09-08 incident:
  goal_bridge.clj gained a call to predicates/integrity-violations while the
  working tree briefly held a stale predicates.clj; the poller crash-looped
  ~30 min at analysis phase ('Unable to resolve symbol') with no test red.
  These pins fail in the suite instead of in a silent service restart loop."
  (:require [babashka.process :refer [sh]]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- load-chain
  "Require `ns-sym` in a fresh bb process from the repo root; return
  {:exit int :out string :err string}."
  [ns-sym]
  (let [{:keys [exit out err]}
        (sh {:dir "." :continue true}
            "bb" "-e" (str "(require '[" ns-sym "]) (println :load-ok)"))]
    {:exit exit :out out :err err}))

(deftest telegram-service-chain-loads
  ;; `bb telegram poll` boots cli -> telegram-lifecycle -> telegram-intake
  ;; -> goal_bridge -> goal.* — the chain that crash-looped (bk-1fe8), now
  ;; split across the lifecycle/intake namespaces (bk-bf8b).
  (let [{:keys [exit out err]} (load-chain "bb-agent.telegram-lifecycle")]
    (testing "fresh process analyzes telegram lifecycle/intake chain"
      (is (zero? exit) (str "boot chain failed: " err))
      (is (str/includes? out ":load-ok"))))
  (let [{:keys [exit out err]} (load-chain "bb-agent.telegram-intake")]
    (testing "fresh process analyzes intake chain"
      (is (zero? exit) (str "intake chain failed: " err))
      (is (str/includes? out ":load-ok")))))

(deftest goal-runner-chain-loads
  ;; `bb goal <config>` boots bb-agent.goal.main — the detached runner chain.
  (let [{:keys [exit out err]} (load-chain "bb-agent.goal.main")]
    (testing "fresh process analyzes goal runner chain"
      (is (zero? exit) (str "runner chain failed: " err))
      (is (str/includes? out ":load-ok")))))
