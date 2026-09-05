(ns axiom.phase2-rungs-test
  "Runner-level Phase 2 test: the escalation ladder rungs perform REAL,
  observably-different perturbations -- not just bookkeeping.

  Drives the real `run!` loop with :thrash-after 1 and the full ladder
  [:reseed :reframe :escalate-model]. A vector :act (prompts A,B) and a
  :models list (cheap, smart) let :reframe and :escalate-model change what
  the next act actually runs; :reseed runs a side command. Each act records
  `{{prompt}}:{{model}}` to rung.log, so the log sequence proves the rungs
  fired in order and each changed something. The ladder then exhausts ->
  :no-convergence halt (ROADMAP Phase 2 done-when)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [axiom.core :as core]
            [babashka.fs :as fs]))

(defn- shq
  "Quietly run a command in `dir`; non-zero exits surface via :continue."
  [dir & args]
  (apply babashka.process/sh
         {:out :string :err :string :dir dir :continue true} args))

(deftest run-climbs-ladder-with-observable-rung-effects
  (testing "reseed -> reframe -> escalate-model each perturb observably, then halt"
    (let [dir (str (fs/create-temp-dir))
          log-dir (str dir "/logs")]
      (try
        (spit (str dir "/act.count") "0\n")
        (spit (str dir "/reseed.count") "0\n")
        (let [cfg {:name "phase2-rungs-test"
                   :workdir dir
                   :lock (str dir "/.axiom.lock")
                   :log-dir log-dir
                   :observers {:act-count {:sh "cat act.count 2>/dev/null | tr -d '\\n'" :parse :int}}
                   :goal {:op :>= :ref :act-count :value 999}
                   :integrity []
                   :progress :act-count
                   :thrash-after 1
                   :escalations [:reseed :reframe :escalate-model]
                   ;; vector act = reframe ladder (prompt A, prompt B)
                   :act [{:sh "n=$(cat act.count 2>/dev/null||echo 0); echo $((n+1))>act.count; echo A:{{model}}>>rung.log"}
                         {:sh "n=$(cat act.count 2>/dev/null||echo 0); echo $((n+1))>act.count; echo B:{{model}}>>rung.log"}]
                   :models ["cheap" "smart"]
                   :reseed {:sh "r=$(cat reseed.count 2>/dev/null||echo 0); echo $((r+1))>reseed.count"}}
              result (core/run! cfg {:max-iters 30})
              rung-log (when (fs/exists? (str dir "/rung.log"))
                         (-> (slurp (str dir "/rung.log"))
                             str/trim
                             (str/split #"\n")))
              reseed-count (-> (slurp (str dir "/reseed.count")) str/trim Integer/parseInt)]
          (is (= :halt (:status result)))
          (is (= :no-convergence (:reason result)))
          (is (= ["A:cheap" "A:cheap" "B:cheap" "B:smart"] rung-log)
              "rung.log proves reframe switched A->B and escalate-model switched cheap->smart")
          (is (= 1 reseed-count)
              "the :reseed rung ran its command exactly once"))
        (finally
          (fs/delete-tree dir))))))

(defn -main
  "Run the Phase 2 rungs test; exit non-zero on any failure or error."
  [& _]
  (let [res (clojure.test/run-tests 'axiom.phase2-rungs-test)]
    (System/exit (if (or (pos? (:fail res)) (pos? (:error res))) 1 0))))
