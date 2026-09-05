(ns axiom.phase5-dogfood-test
  "Phase 5 -- dogfood hardening tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.config :as config]
            [axiom.taxonomy :as taxonomy]))

(deftest halt-reasons-map-to-stable-failure-taxonomy
  (testing "known halt reasons get operator-facing classes"
    (is (= :no-progress (:class (taxonomy/classify-halt {:reason :stall}))))
    (is (= :dirty-tree-unsafe (:class (taxonomy/classify-halt {:reason :integrity}))))
    (is (= :budget-exhausted (:class (taxonomy/classify-halt {:reason :max-iters}))))
    (is (= :budget-exhausted (:class (taxonomy/classify-halt {:reason :budget-exhausted}))))
    (is (= :operator-paused (:class (taxonomy/classify-halt {:reason :operator-paused}))))
    (is (= :operator-stopped (:class (taxonomy/classify-halt {:reason :operator-stop}))))
    (is (= :config-invalid-after-hot-reload
           (:class (taxonomy/classify-halt {:reason :config-invalid}))))
    (is (= :rollback-limit-hit (:class (taxonomy/classify-halt {:reason :rollback-limit})))))
  (testing "halt bundles classify through their nested halt payload"
    (is (= :repeated-identical-actions
           (:class (taxonomy/classify-halt {:halt {:reason :no-convergence}})))))
  (testing "unknown reasons stay visible instead of being misreported"
    (is (= {:class :unknown
            :severity :warning
            :retryable? false
            :reason :weird}
           (taxonomy/classify-halt {:reason :weird})))))

(defn- config-files
  []
  (->> (.listFiles (io/file "configs"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))))

(deftest dogfood-configs-are-real-scenarios-not-placeholders
  (let [files (config-files)]
    (is (<= 3 (count files)) "Phase 4 library remains present")
    (doseq [f files
            :let [cfg (edn/read-string (slurp f))
                  loaded (config/load-config (.getPath f))]]
      (testing (.getName f)
        (is (string? (:name cfg)))
        (is (string? (:workdir cfg)) "scenario names its supervised workspace")
        (is (string? (:log-dir cfg)) "scenario leaves forensic logs")
        (is (seq (:observers cfg)) "scenario re-derives world state")
        (is (map? (:goal cfg)) "scenario has a data goal")
        (is (map? (:act cfg)) "scenario has a data act")
        (is (seq (:integrity loaded)) "bundle expansion yields executable axioms")))))

(deftest artifact-summary-names-forensic-surfaces
  (let [summary (taxonomy/artifact-summary {:name "dogfood"
                                            :log-dir "logs/dogfood"
                                            :config-path "configs/dogfood.edn"
                                            :hot-reload true})]
    (is (= "dogfood" (:name summary)))
    (is (= "logs/dogfood/events.log" (:events-log summary)))
    (is (= "logs/dogfood/halt-bundle.edn" (:halt-bundle summary)))
    (is (:hot-reload? summary))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase5-dogfood-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
