(ns axiom.phase3-test
  "Phase 3 -- Observability & Ops regression.

  Two Phase 3 deliverables are exercised here:
    1. Config validation at load time (reject goals without a clean
       predicate + signature, malformed axioms/acts).
    2. The diagnostic halt bundle (last N iteration records + reason in one
       self-contained EDN file).

  Both target PURE surfaces (validate-config, valid-pred?) or a throwaway
  log dir (read-iterations, halt-bundle!), so no git/agent is needed. The
  ROADMAP Phase 3 'done-when' gate -- 'from a halt bundle alone, a human can
  name the blocker in under 2 minutes' -- is the spirit of the bundle tests."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.config :as config]
            [axiom.predicates :as predicates]
            [axiom.log :as log]
            [babashka.fs :as fs]))

;; ---------- config validation (pure) ----------

(def good-cfg
  {:name     "phase3-fixture"
   :observers {:level-count {:sh "echo 0" :parse :int}}
   :goal      {:op :>= :ref :level-count :value 3}
   :progress  :level-count
   :act       {:sh "./gen.sh {{attempt}}"}
   :integrity [{:op := :ref :build-ok :value "pass"}]})

(deftest validate-config-accepts-a-clean-config
  (is (empty? (config/validate-config good-cfg))
      "a well-formed config with goal + signature + act + axioms is valid"))

(deftest validate-config-rejects-missing-progress-signature
  (let [errors (config/validate-config (dissoc good-cfg :progress))]
    (is (seq errors))
    (is (some #(= :progress (:key %)) errors)
        "an error names :progress as the missing signature")))

(deftest validate-config-rejects-a-malformed-goal
  (testing "a goal with an unknown op is not a clean predicate"
    (let [errors (config/validate-config (assoc good-cfg :goal {:op :bogus :ref :x :value 1}))]
      (is (some #(= :goal (:key %)) errors))))
  (testing "a goal that is neither keyword nor map is rejected"
    (let [errors (config/validate-config (assoc good-cfg :goal "build passes"))]
      (is (some #(= :goal (:key %)) errors)))))

(deftest validate-config-rejects-act-without-sh
  (testing "a map act missing :sh is rejected"
    (let [errors (config/validate-config (assoc good-cfg :act {:timeout 1000}))]
      (is (some #(= :act (:key %)) errors))))
  (testing "a vector act with one entry missing :sh names the bad index"
    (let [errors (config/validate-config
                  (assoc good-cfg :act [{:sh "echo a"} {:timeout 1000}]))
          act-err (filter #(= :act (:key %)) errors)]
      (is (seq act-err))
      (is (some #(str/includes? (:problem %) "act[1]") act-err))))
  (testing "an act of the wrong type is rejected"
    (let [errors (config/validate-config (assoc good-cfg :act "just run it"))]
      (is (some #(= :act (:key %)) errors)))))

(deftest validate-config-rejects-malformed-integrity-check
  (let [errors (config/validate-config
                (assoc good-cfg :integrity [{:op := :ref :build-ok :value "pass"}
                                             {:op :bogus}]))]
    (is (some #(and (= :integrity (:key %))
                    (str/includes? (:problem %) "check[1]")) errors)
        "the malformed check is named by index")))

(deftest valid-pred-recognises-the-full-grammar
  (testing "atoms"
    (is (predicates/valid? :ready?))
    (is (not (predicates/valid? "not a pred"))))
  (testing "comparison / existence"
    (is (predicates/valid? {:op :>= :ref :n :value 3}))
    (is (predicates/valid? {:op := :ref :build-ok :value "pass"}))
    (is (predicates/valid? {:op :exists :ref :n}))
    (is (not (predicates/valid? {:op :>= :value 3})) "missing :ref")
    (is (not (predicates/valid? {:op := :ref :n})) "comparison missing :value"))
  (testing "combinators recurse"
    (is (predicates/valid? {:op :and :exprs [{:op :>= :ref :a :value 1}
                                              {:op :=  :ref :b :value 2}]}))
    (is (predicates/valid? {:op :not :expr {:op := :ref :b :value 2}}))
    (is (not (predicates/valid? {:op :and :exprs []})) "empty :and is invalid")
    (is (not (predicates/valid? {:op :and :exprs [{:op :bogus}]}))
        "a bad nested pred invalidates the parent"))
  (testing "unknown op -> invalid"
    (is (not (predicates/valid? {:op :bogus :ref :x :value 1})))))

;; ---------- load-config throws on validation errors ----------

(defn- write-tmp-config
  "Write `cfg` as EDN to a temp file; return its path."
  [cfg]
  (let [dir (str (fs/create-temp-dir))
        f   (str dir "/config.edn")]
    (spit f (pr-str cfg))
    f))

(deftest load-config-throws-on-invalid-config
  (testing "missing progress signature -> ex-info with :errors"
    (let [path (write-tmp-config (dissoc good-cfg :progress))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"validation"
                            (config/load-config path)))))
  (testing "malformed goal -> ex-info"
    (let [path (write-tmp-config (assoc good-cfg :goal {:op :bogus}))]
      (is (thrown? clojure.lang.ExceptionInfo (config/load-config path))))))

(deftest load-config-accepts-a-valid-config
  (let [path (write-tmp-config good-cfg)]
    (is (= "phase3-fixture" (:name (config/load-config path))))))

;; ---------- diagnostic halt bundle ----------

(defn- write-iters
  "Write `n` fake iteration records into `log-dir` so read-iterations has
  something to read. Each record is {:event :act :iteration i}."
  [log-dir n]
  (doseq [i (range n)]
    (log/iteration! log-dir i {:event :act :exit 0 :progressed? true})))

(deftest read-iterations-returns-last-n-oldest-first
  (let [dir (str (fs/create-temp-dir))]
    (try
      (write-iters dir 5)
      (let [all  (log/read-iterations dir 10)
            last3 (log/read-iterations dir 3)]
        (is (= 5 (count all)) "all five read when n exceeds the count")
        (is (= [0 1 2 3 4] (mapv :iteration all)) "oldest first, in order")
        (is (= 3 (count last3)) "only the last 3 when n is smaller")
        (is (= [2 3 4] (mapv :iteration last3)) "the last 3 by iteration number"))
      (finally (fs/delete-tree dir)))))

(deftest read-iterations-nil-or-absent-dir
  (is (empty? (log/read-iterations nil 5)) "nil log-dir -> empty")
  (is (empty? (log/read-iterations "/nonexistent/axiom/dir" 5))
      "absent dir -> empty, not a crash"))

(deftest halt-bundle-writes-reason-and-iterations
  (let [dir (str (fs/create-temp-dir))]
    (try
      (write-iters dir 4)
      (let [action {:type :halt :reason :no-convergence :iterations 3
                    :world {:n 4}}
            bundle (log/halt-bundle! dir action 20)
            path   (str dir "/halt-bundle.edn")]
        (is (= :no-convergence (:reason bundle)))
        (is (= :no-convergence (-> bundle :halt :reason)))
        (is (= 4 (count (:iterations bundle)))
            "the bundle carries the last N iteration records")
        (is (fs/exists? path) "the bundle file was written to disk")
        ;; the written file round-trips as EDN and is self-contained
        (let [from-disk (edn/read-string (slurp path))]
          (is (= :no-convergence (:reason from-disk)))
          (is (= [0 1 2 3] (mapv :iteration (:iterations from-disk)))
              "a human reading only the file can see the iteration history")))
      (finally (fs/delete-tree dir)))))

(deftest halt-bundle-nil-log-dir-still-returns-bundle
  (let [action {:type :halt :reason :integrity :violations [{:check {:op :=}}]}
        bundle (log/halt-bundle! nil action)]
    (is (= :integrity (:reason bundle)))
    (is (vector? (:iterations bundle)) "iterations default to [] -- a vector, never nil")
    ;; nothing was written (no dir), but the in-memory bundle is usable
    (is (map? (:halt bundle)))))

(defn -main
  "Run the Phase 3 tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase3-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
