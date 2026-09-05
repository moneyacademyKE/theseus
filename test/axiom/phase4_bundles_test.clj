(ns axiom.phase4-bundles-test
  "Phase 4 -- composable axiom bundles + the example config library.

  Covers (1) vector-bundle expansion (compose [:rust-build :clean-tree]
  etc), (2) the three new bundles (:node-build :python-build :go-build),
  and (3) that every configs/*.edn example validates clean so the library
  can't silently rot."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.config :as config]))

;; ---------- vector / composable bundles ----------

(deftest expand-axioms-accepts-a-vector-of-bundles
  (testing "a vector of bundle keywords is flattened in order"
    (let [ax (config/expand-axioms {:axiom-bundle [:gleam-build :clean-tree]})]
      (is (= [{:op := :ref :build-ok :value "pass"}]
             (take 1 ax))
          "gleam-build comes first")
      (is (= {:op := :ref :git-clean :value "true"} (last ax))
          "clean-tree comes last")))
  (testing "three-way composition: rust-build + clj-build, then explicit"
    (let [ax (config/expand-axioms {:axiom-bundle [:rust-build :clj-build]
                                    :integrity [{:op :>= :ref :disk :value 1}]})]
      (is (= 5 (count ax))
          "rust(2) + clj(2) + explicit(1)")
      (is (= :disk (-> ax last :ref)) "explicit integrity appended last"))))

(deftest expand-axioms-vector-throws-on-unknown-bundle
  (doseq [bundle [[:bogus] [:gleam-build :bogus]]]
    (is (thrown? Exception (config/expand-axioms {:axiom-bundle bundle}))
        (str "any unknown bundle in a vector throws: " bundle))))

;; ---------- the three new bundles ----------

(deftest new-bundles-produce-build-and-test-predicates
  (letfn [(ax [b] (config/expand-axioms {:axiom-bundle b}))]
    (testing ":node-build asserts build + tests pass"
      (is (= [{:op := :ref :build-ok :value "pass"}
              {:op := :ref :tests-ok :value "pass"}]
             (ax :node-build))))
    (testing ":python-build asserts build + tests pass"
      (is (= 2 (count (ax :python-build)))))
    (testing ":go-build asserts build + tests pass"
      (is (= [{:op := :ref :build-ok :value "pass"}
              {:op := :ref :tests-ok :value "pass"}]
             (ax :go-build))))))

;; ---------- the example config library validates ----------

(def configs-dir "configs")

(defn- config-names
  "List the .edn basenames in the configs/ directory, sans extension."
  []
  (->> (.listFiles (io/file configs-dir))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(str/ends-with? % ".edn"))
       (map #(subs % 0 (- (count %) 4)))
       sort
       vec))

(deftest every-config-in-library-validates
  (let [names (config-names)]
    (is (seq names) "the configs/ directory has at least one .edn file")
    (is (some #{"" "dogfood-level-gen"} (seq names)))
    (doseq [nm names
            :let [path (str configs-dir "/" nm ".edn")
                  cfg  (edn/read-string (slurp path))]]
      (testing (str "configs/" nm ".edn validates")
        ;; every example must pass the PURE validator (no missing keys,
        ;; clean predicates, well-formed acts/axioms).
        (is (empty? (config/validate-config cfg))
            (str "configs/" nm ".edn has validation errors"))))))

(deftest config-library-includes-the-canonical-three
  (let [names (set (config-names))]
    (is (contains? names "dogfood-level-gen"))
    (is (contains? names "test-convergence"))
    (is (contains? names "benchmark-chase"))))

(deftest benchmark-chase-demonstrates-vector-bundle-composition
  ;; the benchmark-chase example composes [:rust-build :clean-tree] -- a
  ;; real-world showcase of Phase 4 bundle composition. Confirm it expands.
  (let [path (str configs-dir "/benchmark-chase.edn")
        cfg  (edn/read-string (slurp path))]
    (is (vector? (:axiom-bundle cfg))
        "benchmark-chase uses a vector :axiom-bundle")
    (is (= [:rust-build :clean-tree] (:axiom-bundle cfg)))
    (is (= 3 (count (config/expand-axioms cfg)))
        "rust(2) + clean-tree(1) = 3 axioms after expansion")))

(defn -main
  "Run the Phase 4 bundle tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase4-bundles-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))