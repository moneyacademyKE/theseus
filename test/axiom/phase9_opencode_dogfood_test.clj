(ns axiom.phase9-opencode-dogfood-test
  "Dogfood ladder tests for supervising opencode as an external harness."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.config :as config]
            [axiom.harness :as harness]))

(defn- dogfood-files
  []
  (->> (.listFiles (io/file "configs/dogfood"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))))

(deftest opencode-dogfood-ladder-is-present-and-ordered
  (let [names (mapv #(.getName %) (dogfood-files))]
    (is (= ["01-doc-link-check.edn"
            "02-config-library-tighten.edn"
            "03-test-repair.edn"
            "04-operator-status-ux.edn"
            "05-harness-contract-hardening.edn"
            "06-hot-reload-drill.edn"
            "07-cross-cutting-refactor.edn"
            "08-release-candidate-sweep.edn"]
           names))))

(deftest dogfood-configs-load-and-use-opencode-harness
  (doseq [f (dogfood-files)
          :let [raw (edn/read-string (slurp f))
                loaded (config/load-config (.getPath f))]]
    (testing (.getName f)
      (is (= :opencode (get-in raw [:act :harness])))
      (is (string? (get-in raw [:act :prompt])))
      (is (str/includes? (get-in raw [:act :prompt]) "Axiom"))
      (is (seq (:observers raw)))
      (is (map? (:goal raw)))
      (when (or (keyword? (:axiom-bundle raw))
                (seq (:axiom-bundle raw)))
        (is (seq (:integrity loaded)) "bundle expansion keeps objective axioms"))
      (is (pos-int? (:stall-after raw)))
      (is (pos-int? (:thrash-after raw)))
      (is (contains? raw :max-rollbacks)))))

(deftest dogfood-invocations-render-opencode-argv
  (doseq [f (dogfood-files)
          :let [cfg (config/load-config (.getPath f))
                inv (harness/build-invocation cfg {} {:attempt 0 :model-index 0} (:act cfg))]]
    (testing (.getName f)
      (is (= :opencode (:harness inv)))
      (is (= "opencode" (:cmd inv)))
      (is (= "run" (second (:argv inv))))
      (is (some #{"--model"} (:argv inv)))
      (is (not (some #{"--prompt"} (:argv inv)))
          "installed opencode accepts the prompt as positional message")
      (is (str/includes? (last (:argv inv)) "Axiom"))
      (is (= (:workdir cfg ".") (:dir inv))))))

(deftest dogfood-playbook-documents-the-ladder
  (let [doc (slurp "docs/opencode-dogfood.md")]
    (is (str/includes? doc "Axiom × opencode Dogfood Ladder"))
    (is (str/includes? doc "configs/dogfood/01-doc-link-check.edn"))
    (is (str/includes? doc "configs/dogfood/08-release-candidate-sweep.edn"))
    (is (str/includes? doc "Axiom only trusts observers"))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase9-opencode-dogfood-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
