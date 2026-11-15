(ns bb-agent.test-roster-test
  "The assigned-vs-executed roster. Stolen from liuyime2/autopoiesis
  `tools/verify.py:3453-3484` — \"Assignment alone is a weaker guarantee than
  it looks… a file whose only class inspects the repository rather than running
  pytest contributes nothing to the gate while looking fully covered.\"

  Red first: these failed at analysis with
  `Unable to resolve symbol: tr/roster` before src/bb_agent/test_roster.clj existed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bb-agent.test-roster :as tr]))

(defn- fixture!
  "A throwaway repo root: bb.edn plus test files. Data, not a real project."
  [home bb-edn files]
  (fs/create-dirs (fs/path home "test"))
  (spit (str (fs/path home "bb.edn")) bb-edn)
  (doseq [[rel body] files]
    (let [p (fs/path home "test" rel)]
      (fs/create-dirs (fs/parent p))
      (spit (str p) body)))
  (str home))

(deftest roster-counts-what-is-on-disk-and-what-is-named
  (let [home (fs/create-temp-dir {:prefix "roster-"})]
    (try
      (fixture! home
                "{:tasks {a {:requires ([x.one-test])
                             :task (t/run-tests 'x.one-test)}}}"
                {"x/one_test.clj" "(ns x.one-test)\n"
                 "x/two_test.clj" "(ns x.two-test)\n"})
      (let [r (tr/roster (str home))]
        (is (= 2 (:files r)))
        (is (= #{"x.one-test" "x.two-test"} (set (:on-disk r))))
        (is (= #{"x.one-test"} (:executed-ns r)))
        ;; :assigned is what a :require vector loads, :executed is what a
        ;; run-tests call names — two different sets, so the denominator is
        ;; printed beside the verdict rather than assumed
        (is (= 1 (:assigned r)))
        (is (= 1 (:executed r)))
        ;; two_test.clj exists and is named by nobody: the quiet failure
        (is (= ["x.two-test"] (mapv :ns (:never-run r)))))
      (finally (fs/delete-tree home)))))

(deftest roster-names-a-ghost-and-an-unparsed-file-separately
  (let [home (fs/create-temp-dir {:prefix "roster-ghost-"})]
    (try
      (fixture! home
                "{:tasks {a {:task (t/run-tests 'x.one-test 'x.gone-test)}}}"
                {"x/one_test.clj" "(ns x.one-test)\n"
                 "x/odd_test.clj" "no ns form here at all\n"})
      (let [r (tr/roster (str home))]
        ;; named by a runner but no file declares it — an accounting of intent
        (is (= ["x.gone-test"] (vec (:ghost r))))
        ;; a file whose ns cannot be read is UNPARSED, not silently absent:
        ;; not-found is not zero (the 2026-10-14 rule, applied to identity)
        (is (= 1 (count (:unparsed r))))
        (is (str/includes? (first (:unparsed r)) "odd_test.clj"))
        ;; and it is NOT counted as on-disk-identified, so the partition is honest
        (is (= 1 (count (:on-disk r)))))
      (finally (fs/delete-tree home)))))

(deftest roster-follows-a-runner-module-that-has-no-main
  ;; The measurement that caught my own probe lying: `bb -m ns` executes the
  ;; file ON LOAD, so a runner does not need a `-main`. Filtering runner files
  ;; on `defn -main` reported 5 real goal suites as NEVER-RUN — a false negative
  ;; produced by the instrument, exactly the shape this ns exists to prevent.
  (let [home (fs/create-temp-dir {:prefix "roster-runner-"})]
    (try
      (fixture! home
                "{:tasks {g {:task (shell \"bb\" \"-m\" \"x.run-tests\")}}}"
                {"x/run_tests.clj" (str "(ns x.run-tests)\n"
                                        "(t/run-tests 'x.deep-test)\n")
                 "x/deep_test.clj" "(ns x.deep-test)\n"})
      (let [r (tr/roster (str home))]
        (is (= #{"x.deep-test"} (:executed-ns r)))
        (is (empty? (:never-run r)))
        (is (= 1 (:runner-files r))))
      (finally (fs/delete-tree home)))))

(deftest a-suite-file-is-not-read-as-a-runner
  ;; Regression on the instrument, not on the corpus: this ns's own fixtures
  ;; (`'x.one-test` inside a fixture string) were reported as three GHOST
  ;; namespaces on the live repo. A `*_test.clj` is a suite; only bb.edn and
  ;; non-suite runner files decide who is executed.
  (let [home (fs/create-temp-dir {:prefix "roster-suite-"})]
    (try
      (fixture! home
                "{:tasks {a {:task (t/run-tests 'x.one-test)}}}"
                {"x/one_test.clj" "(ns x.one-test)\n"
                 ;; a suite whose body quotes another namespace must NOT
                 ;; make that namespace look executed
                 "x/suite_test.clj" "(ns x.suite-test)\n(t/run-tests 'x.never-real-test)\n"})
      (let [r (tr/roster (str home))]
        (is (= #{"x.one-test"} (:executed-ns r)))
        (is (empty? (:ghost r)))
        (is (= ["x.suite-test"] (mapv :ns (:never-run r)))))
      (finally (fs/delete-tree home)))))

(deftest roster-report-states-its-evidence-class-and-negative-scope
  (let [home (fs/create-temp-dir {:prefix "roster-report-"})]
    (try
      (fixture! home
                "{:tasks {a {:requires ([x.one-test])
                             :task (t/run-tests 'x.one-test)}}}"
                {"x/one_test.clj" "(ns x.one-test)\n"
                 "x/two_test.clj" "(ns x.two-test)\n"})
      (let [out (str/join "\n" (tr/roster-report (tr/roster (str home))))]
        ;; counts with their denominator, so a rate is checkable arithmetic
        (is (str/includes? out "2 file(s) on disk"))
        (is (str/includes? out "NEVER-RUN (1)"))
        (is (str/includes? out "x.two-test"))
        ;; a name in a run-tests call proves it is NAMED, not that it ran green
        (is (str/includes? out "evidence class"))
        (is (str/includes? out "not that any suite passed"))
        ;; and the report says what it did not examine
        (is (str/includes? out "negative scope")))
      (finally (fs/delete-tree home)))))

(deftest roster-missing-root-is-not-an-empty-roster
  ;; A root that does not exist is not-found, never a clean zero — the shape
  ;; `audit` learned on 2026-10-14 and `context-cost` on 2026-11-11.
  (let [r (tr/roster "/tmp/no-such-roster-root-4c19")]
    (is (= :missing (:status r)))
    (is (nil? (:files r)))
    (is (nil? (:executed r))))
  (is (= 2 (tr/-main ["/tmp/no-such-roster-root-4c19"]))))

(deftest roster-exit-code-is-zero-on-a-clean-report
  (let [home (fs/create-temp-dir {:prefix "roster-exit-"})]
    (try
      (fixture! home
                "{:tasks {a {:requires ([x.one-test])
                             :task (t/run-tests 'x.one-test)}}}"
                {"x/one_test.clj" "(ns x.one-test)\n"})
      (is (= 0 (tr/-main [(str home)])))
      (finally (fs/delete-tree home)))))
