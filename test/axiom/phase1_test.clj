(ns axiom.phase1-test
  "Runner-level Phase 1 regression: rollback-as-recovery, end-to-end.

  The pure `decide` tests (in run_tests) prove the rollback *decision* is
  correct; git-test proves `git/rollback!` restores byte-identical state in
  isolation. This namespace closes the remaining gap: it drives the REAL
  `run!` loop against a throwaway git repo with a flaky agent that stalls,
  gets rolled back to last-good, and recovers to fulfill the goal -- the
  full Phase 1 'done-when' criterion exercised as one integrated system."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.core :as core]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [clojure.string :as str]))

(defn- shq
  "Quietly run a command in `dir`; non-zero exits surface via :continue."
  [dir & args]
  (apply sh {:out :string :err :string :dir dir :continue true} args))

(defn- flaky-agent-script
  "Bash source for a flaky agent that generates on calls 1-2 and 5+,
  stalls on calls 3-4. Uses a gitignored call counter so rollback does
  not reset its phase. Level files are named by call number (deterministic)."
  []
  (str/join "\n"
            ["#!/usr/bin/env bash"
             "set -euo pipefail"
             "cd \"$(dirname \"$0\")\""
             "n=$(cat calls.txt 2>/dev/null || echo 0); n=$((n+1)); echo \"$n\" > calls.txt"
             "touch .axiom-marker"
             "if { [ \"$n\" -ge 1 ] && [ \"$n\" -le 2 ]; } || [ \"$n\" -ge 5 ]; then"
             "  touch \"level_${n}.txt\""
             "  git add -A; git commit -q -m \"level $n\""
             "fi"]))

(deftest run-rolls-back-and-recovers-to-goal
  (testing "stall triggers rollback to last-good; retry recovers and fulfills the goal"
    (let [dir (str (fs/create-temp-dir))
          log-dir (str dir "/logs-recover")]
      (try
        ;; fresh git repo with a committed known-good baseline
        (shq dir "git" "init" "-q")
        (shq dir "git" "config" "user.email" "axiom@test")
        (shq dir "git" "config" "user.name" "axiom-test")
        (spit (str dir "/.axiom-marker") "GOOD\n")
        (spit (str dir "/.gitignore") "calls.txt\n.axiom.lock\nlogs-*\n")
        (shq dir "git" "add" "-A")
        (shq dir "git" "commit" "-q" "-m" "baseline")
        ;; flaky act script
        (spit (str dir "/act.sh") (flaky-agent-script))
        (shq dir "chmod" "+x" (str dir "/act.sh"))
        (let [cfg {:name "phase1-rollback-test"
                   :workdir dir
                   :lock (str dir "/.axiom.lock")
                   :log-dir log-dir
                   :observers {:level-count {:sh "ls level_*.txt 2>/dev/null | wc -l | tr -d ' '" :parse :int}
                               :build-ok {:sh "test -f .axiom-marker && echo pass || echo fail" :parse :string}}
                   :goal {:op :>= :ref :level-count :value 3}
                   :integrity [{:op := :ref :build-ok :value "pass"}]
                   :progress :level-count
                   :act {:sh "./act.sh {{attempt}}"}
                   :checkpoint {:tag-prefix "ax-test"}
                   :stall-after 2
                   :max-rollbacks 1}
              result (core/run! cfg {:max-iters 20})]
          (is (= :done (:status result))
              "loop recovered after rollback and fulfilled the goal")
          (is (= 3 (get-in result [:world :level-count]))
              "three levels generated post-recovery")
          ;; the agent stalled on calls 3-4, so the counter must be >= 5
          ;; (proving a rollback + retry actually occurred, not just 3 straight acts)
          (is (>= (Integer/parseInt (str/trim (slurp (str dir "/calls.txt")))) 5)
              "call counter advanced past the stall phase -- a rollback+retry happened"))
        (finally
          (fs/delete-tree dir))))))

(defn -main
  "Run the Phase 1 runner tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase1-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
