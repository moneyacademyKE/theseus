(ns axiom.promote-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [axiom.promote :as p]))

(deftest test-find-highest-adr-num
  (testing "extracts correct max ADR number"
    (is (= 0 (p/find-highest-adr-num "")))
    (is (= 1 (p/find-highest-adr-num "## ADR-0001 -- First")))
    (is (= 19 (p/find-highest-adr-num "## ADR-0019 -- Max\n## ADR-0005 -- Other")))))

(deftest test-prepend-adr
  (testing "prepends ADR immediately after divider"
    (let [log "# Decision Log\n\n---\n## ADR-0001 -- Preexisting"
          adr "## ADR-0002 -- New ADR"
          expected "# Decision Log\n\n---\n## ADR-0002 -- New ADR\n\n## ADR-0001 -- Preexisting"]
      (is (= expected (p/prepend-adr log adr))))))

(deftest test-promote-plan
  (fs/with-temp-dir [tmp {}]
    (let [decision-log-file (fs/file tmp "decision-log.md")
          specs-dir (fs/file tmp "specs")
          archive-root (fs/file tmp "archive")
          plan-dir (fs/file tmp "phase13-test-promotion")
          plan-file (fs/file plan-dir "plan.md")
          adr-file (fs/file plan-dir "adr.md")
          deltas-dir (fs/file plan-dir "deltas")
          delta-file (fs/file deltas-dir "some-spec.md")]

      (testing "returns error if plan directory doesn't exist"
        (is (= :error (:status (p/promote-plan! {:plan-dir (fs/file tmp "non-existent")})))))

      (testing "returns error if plan.md is missing"
        (fs/create-dirs plan-dir)
        (is (= :error (:status (p/promote-plan! {:plan-dir plan-dir})))))

      (testing "promotes successfully when all conditions are met"
        (spit plan-file "# Plan content")
        (spit adr-file "## ADR-00NN -- Test ADR\n\nContext of ADR-XXXX.")
        (fs/create-dirs deltas-dir)
        (spit delta-file "# Spec details")
        (spit decision-log-file "# Decision Log\n\n---\n## ADR-0019 -- Old ADR")

        (let [opts {:plan-dir plan-dir
                    :decision-log-path decision-log-file
                    :specs-dir specs-dir
                    :archive-root-dir archive-root}
              res (p/promote-plan! opts)]
          (is (= :ok (:status res)))
          (is (= "ADR-0020" (:adr-tag res)))
          (is (= 20 (:adr-num res)))

          ;; Check that decision log is prepended and variables are replaced
          (let [log-content (slurp (str decision-log-file))]
            (is (clojure.string/includes? log-content "## ADR-0020 -- Test ADR"))
            (is (clojure.string/includes? log-content "Context of ADR-0020."))
            (is (clojure.string/includes? log-content "## ADR-0019 -- Old ADR")))

          ;; Check that delta is copied
          (is (fs/exists? (fs/file specs-dir "some-spec.md")))
          (is (= "# Spec details" (slurp (str (fs/file specs-dir "some-spec.md")))))

          ;; Check that plan dir is archived
          (is (not (fs/exists? plan-dir)))
          (is (fs/exists? (fs/file archive-root "phase13-test-promotion" "plan.md"))))))))
