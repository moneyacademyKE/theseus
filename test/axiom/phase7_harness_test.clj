(ns axiom.phase7-harness-test
  "Phase 7 -- harness orchestration tests."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.config :as config]
            [axiom.core :as core]
            [axiom.harness :as harness]
            [babashka.fs :as fs]))

(deftest build-invocation-renders-opencode-profile-without-shell
  (let [cfg {:workdir "/repo"
             :models ["cheap" "smart"]}
        act {:harness :opencode
             :prompt "Fix level {{level-count}} using {{model}}"}
        inv (harness/build-invocation cfg {:level-count 2} {:attempt 3 :model-index 1} act)]
    (is (= :opencode (:harness inv)))
    (is (= "opencode" (:cmd inv)))
    (is (= ["opencode" "run" "--model" "smart" "Fix level 2 using smart"]
           (:argv inv)))
    (is (= "/repo" (:dir inv)))
    (is (= "smart" (:model inv)))))

(deftest config-validation-accepts-harness-act-and-rejects-malformed-one
  (let [base {:name "harness-config"
              :observers {:n {:sh "echo 0" :parse :int}}
              :goal {:op :>= :ref :n :value 1}
              :progress :n}]
    (is (empty? (config/validate-config
                 (assoc base :act {:harness :opencode
                                   :prompt "make n bigger"}))))
    (is (seq (config/validate-config
              (assoc base :act {:harness :opencode})))
        "harness acts still need an explicit prompt")))

(deftest unknown-harness-fails-loud
  (is (thrown? clojure.lang.ExceptionInfo
               (harness/build-invocation {} {} {} {:harness :missing
                                                   :prompt "x"}))))

(deftest run-treats-harness-output-as-advisory-and-trusts-observed-world
  (testing "a lying harness self-report does not finish the run unless observers prove it"
    (let [dir (str (fs/create-temp-dir))
          calls (atom 0)
          invocations (atom [])]
      (try
        (spit (str dir "/n") "0\n")
        (let [cfg {:name "harness-loop"
                   :workdir dir
                   :lock (str dir "/.axiom.lock")
                   :log-dir (str dir "/logs")
                   :observers {:n {:sh "cat n" :parse :int}}
                   :goal {:op :>= :ref :n :value 1}
                   :progress :n
                   :integrity []
                   :act {:harness :opencode
                         :prompt "set n to 1; attempt={{attempt}}"}
                   :stall-after 1
                   :max-rollbacks 0
                   :harness-transport (fn [inv]
                                        (swap! calls inc)
                                        (swap! invocations conj inv)
                                        ;; Lie loudly. Axiom must ignore this
                                        ;; and decide from the re-observed n file.
                                        {:exit 0 :out "GOAL FULFILLED" :err ""})}
              res (core/run! cfg {:max-iters 3})]
          (is (= :halt (:status res)))
          (is (= :stall (:reason res)))
          (is (= 1 @calls))
          (is (= 0 (Integer/parseInt (clojure.string/trim (slurp (str dir "/n"))))))
          (is (= :opencode (:harness (first @invocations)))))
        (finally (fs/delete-tree dir))))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase7-harness-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
