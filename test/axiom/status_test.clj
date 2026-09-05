(ns axiom.status-test
  "Phase 3 -- live status view tests.

  Targets the PURE `format-summary` surface (no disk I/O) with two cases:
    1. A halt bundle -> the summary names the halt reason + iteration count.
    2. No bundle (running / stale) -> the summary carries the 'RUNNING'
       marker and the recent iteration events.

  Calls a throwaway log dir to exercise `summarize!` once end-to-end, so the
  disk-reading surface is also covered."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.status :as status]
            [axiom.log :as log]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def halt-bundle
  "A representative halt bundle (as written by log/halt-bundle!)."
  {:reason     :no-convergence
   :halt       {:reason :no-convergence :iterations 3 :world {:level-count 0}}
   :iterations [{:event :act :iteration 0 :progressed? true :exit 0}
                {:event :act :iteration 1 :progressed? true :exit 0}
                {:event :escalate :iteration 2 :rung :reframe}]})

(deftest format-summary-names-reason-and-iterations-on-halt
  (let [cfg "demo-steady"
        summary (status/format-summary {:name cfg} halt-bundle (:iterations halt-bundle))]
    (is (str/includes? summary "HALTED: no-convergence")
        "the summary names the halt reason")
    (is (str/includes? summary "Iterations: 2")
        "the summary shows the last iteration number from the records")
    (is (str/includes? summary "Last world:")
        "the summary includes the last world snapshot")
    (is (str/includes? summary "Halt iterations: 3")
        "the halt iteration count from the bundle is surfaced")))

(deftest format-summary-shows-running-without-bundle
  (testing "a nil bundle -> the 'RUNNING' marker, no halt reason"
    (let [iters [{:event :act :iteration 0 :progressed? true :exit 0}
                 {:event :act :iteration 1 :progressed? false :exit 0}]
          summary (status/format-summary {:name "demo-running"} nil iters)]
      (is (str/includes? summary "RUNNING"))
      (is (not (str/includes? summary "HALTED:")))
      (is (str/includes? summary "Iterations: 1")
          "the last iteration number is shown when running"))))

(deftest summarize-reads-halt-bundle-from-disk
  (testing "summarize! reads the halt bundle if present and formats it"
    (let [dir (str (fs/create-temp-dir))]
      (try
        ;; write a halt bundle to disk (as the loop would)
        (let [action {:type :halt :reason :stall :iterations 2 :world {:level-count 0}}
              _      (log/halt-bundle! dir action)
              summary (status/summarize! {:name "demo-disk" :log-dir dir} {:last 20})]
          (is (str/includes? summary "HALTED: stall"))
          (is (str/includes? summary "Iterations:"))
          (is (str/includes? summary "demo-disk")))
          (finally (fs/delete-tree dir))))))

(deftest summarize-no-bundle-shows-running
  (testing "summarize! with no halt bundle shows the RUNNING state"
    (let [dir (str (fs/create-temp-dir))]
      (try
        ;; write iteration records but no halt bundle
        (doseq [i (range 3)]
          (log/iteration! dir i {:event :act :progressed? true :exit 0}))
        (let [summary (status/summarize! {:name "demo-running-disk" :log-dir dir} {:last 20})]
          (is (str/includes? summary "RUNNING"))
          (is (str/includes? summary "demo-running-disk")))
         (finally (fs/delete-tree dir))))))

(defn -main
  "Run the Phase 3 status tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.status-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))