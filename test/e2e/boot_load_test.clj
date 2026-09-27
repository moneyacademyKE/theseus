(ns e2e.boot-load-test
  "Boot load-chain regression: a fresh bb process must analyze the full
  require chains that the launchd services boot into. 2026-09-08 incident:
  goal_bridge.clj gained a call to predicates/integrity-violations while the
  working tree briefly held a stale predicates.clj; the poller crash-looped
  ~30 min at analysis phase ('Unable to resolve symbol') with no test red.
  These pins fail in the suite instead of in a silent service restart loop."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [sh]]
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

(deftest home-resolution-is-theseus-owned
  ;; 2026-09-27 daemon decoupling (bk-6121): home = $THESEUS_HOME or ~/theseus.
  ;; The OPENCRABS_HOME name and the ~/.opencrabs-bb default are gone — a
  ;; fresh process must find its home without any other runtime's env var.
  (testing "default home is ~/theseus when no env override is set"
    (let [{:keys [exit out]}
          (sh {:dir "." :continue true}
              "bb" "-e" "(require '[bb-agent.config :as c]) (print (c/home))")]
      (is (zero? exit))
      (is (str/includes? out (str (System/getProperty "user.home") "/theseus"))
          (str "unexpected default home: " out))))
  (testing "$THESEUS_HOME override is honored in a fresh process"
    (let [tmp (str (fs/create-temp-dir {:prefix "theseus-home-"}))
          {:keys [exit out]}
          (sh {:dir "." :continue true :extra-env {"THESEUS_HOME" tmp}}
              "bb" "-e" "(require '[bb-agent.config :as c]) (print (c/home))")]
      (is (zero? exit))
      (is (.startsWith (str/trim out) tmp)
          (str "override ignored, got: " out)))))
