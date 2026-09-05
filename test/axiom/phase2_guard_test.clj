(ns axiom.phase2-guard-test
  "Runner-level test for the ADR-0002 identical-retry guard.

  The guard (opt-in via `:identical-retry-guard`, default off) forbids two
  acts with the same `(prompt-index, model-index, pre-act world signature)`
  tuple: a byte-identical retry is the 'insane identical retry' the roadmap
  names as a failure mode. This namespace drives the REAL `run!` loop to
  prove two things end-to-end:

    1. With the guard ON, a non-progressing act spawns its command EXACTLY
       ONCE across the no-progress budget -- the repeats are skipped as
       identical and the loop halts on :stall. With the guard OFF the same
       loop spawns one act per iteration (the contrast proves the guard
       bites rather than no-ops).

    2. The guard does NOT trap the loop: an escalation rung (`:reframe`)
       perturbs prompt-index, which changes the tuple, which lets the next
       act re-spawn with the reframed prompt. Two distinct tuples -> two
       spawns -> :no-convergence halt once the ladder exhausts.

  No git repo is used (rollback is a no-op here); the act is a spawn counter
  that does NOT change the observed world, so the progress signature is
  constant -- the exact condition the guard is built to detect."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [axiom.core :as core]
            [babashka.fs :as fs]))

;; A non-progressing act: increments a spawn counter but does NOT touch the
;; observed world (`level-count` is observed as a constant 0). The progress
;; signature is therefore constant and the guard sees identical tuples.
(defn- noop-spawn-act
  "Bash source for an act that counts its own spawns into `act.count` and
  tags `spawns.log` with `tag` so the test can read which prompt ran. It
  never changes the observed `level-count`, so the pre-act signature is
  constant -- the exact condition the identical-retry guard detects."
  [tag]
  (str "n=$(cat act.count 2>/dev/null||echo 0); echo $((n+1))>act.count; echo " tag ">>spawns.log"))

(defn- read-count
  "Read the integer written to `<dir>/act.count`, 0 if absent."
  [dir]
  (if (fs/exists? (str dir "/act.count"))
    (-> (slurp (str dir "/act.count")) str/trim Integer/parseInt)
    0))

(defn- base-cfg
  "A non-progressing loop config. `overrides` tail-merges extra keys (guard,
  stall-after, ladder, vector act, ...). Observer `level-count` is a
  constant 0 so the goal (>=3) is unreachable and the pre-act signature is
  constant -- which is what makes a repeated tuple identical."
  [dir log-dir overrides]
  (merge
   {:name        "phase2-guard-test"
    :workdir     dir
    :lock        (str dir "/.axiom.lock")
    :log-dir     log-dir
    :observers   {:level-count {:sh "echo 0" :parse :int}}
    :goal        {:op :>= :ref :level-count :value 3}
    :integrity   []
    :progress    :level-count
    :act         {:sh (noop-spawn-act "A")}
    :stall-after 3}
   overrides))

(deftest guard-skips-identical-retries-until-stall-halt
  ;; Zooming in on the core guarantee: with the guard ON, the act spawns
  ;; once, then the repeats are skipped as identical and the loop halts.
  (let [dir (str (fs/create-temp-dir))
        log-dir (str dir "/logs-guard-on")]
    (try
      (spit (str dir "/act.count") "0\n")
      (let [cfg (base-cfg dir log-dir
                          {:identical-retry-guard true :max-rollbacks 0})
            res  (core/run! cfg {:max-iters 20})]
        (is (= :halt (:status res)) "the loop halts")
        (is (= :stall (:reason res)) "halt reason is the stall budget")
        (is (= 1 (read-count dir))
            "the act spawned EXACTLY once; the repeats were skipped as identical"))
      (finally (fs/delete-tree dir)))))

(deftest guard-off-spawns-one-act-per-iteration
  ;; The contrast: the SAME loop WITHOUT the guard spawns one act per
  ;; iteration (3 acts before the stall budget halts), proving the guard
  ;; is what suppresses the repeats -- not some other cutout.
  (let [dir (str (fs/create-temp-dir))
        log-dir (str dir "/logs-guard-off")]
    (try
      (spit (str dir "/act.count") "0\n")
      (let [cfg (base-cfg dir log-dir {:max-rollbacks 0})
            res  (core/run! cfg {:max-iters 20})]
        (is (= :halt (:status res)))
        (is (= :stall (:reason res)))
        (is (= 3 (read-count dir))
            "without the guard the act spawned once per iteration (3 times)"))
      (finally (fs/delete-tree dir)))))

(deftest guard-lets-reframe-perturbation-respawn
  (testing "a :reframe rung changes prompt-index -> new tuple -> fresh spawn"
    (let [dir (str (fs/create-temp-dir))
          log-dir (str dir "/logs-reframe")]
      (try
        (spit (str dir "/act.count") "0\n")
        (let [cfg (base-cfg dir log-dir
                            {:identical-retry-guard true
                             :stall-after 999            ;; stall never fires; only thrash drives the ladder
                             :thrash-after 2
                             :escalations [:reframe]
                             ;; reframe advances prompt-index between A and B
                             :act [{:sh (noop-spawn-act "A")}
                                   {:sh (noop-spawn-act "B")}]})
              res (core/run! cfg {:max-iters 30})
              spawns (when (fs/exists? (str dir "/spawns.log"))
                       (str/split-lines (str/trim (slurp (str dir "/spawns.log")))))]
          (is (= :halt (:status res)))
          (is (= :no-convergence (:reason res))
              "the single-rung ladder exhausts -> :no-convergence halt")
          (is (= 2 (read-count dir))
              "two DISTINCT tuples each spawned once (A then B)")
          (is (= ["A" "B"] spawns)
              "the :reframe rung switched the prompt A -> B before the re-spawn"))
        (finally (fs/delete-tree dir))))))

(defn -main
  "Run the Phase 2 identical-retry guard tests; exit non-zero on failure."
  [& _]
  (let [res (clojure.test/run-tests 'axiom.phase2-guard-test)]
    (System/exit (if (or (pos? (:fail res)) (pos? (:error res))) 1 0))))