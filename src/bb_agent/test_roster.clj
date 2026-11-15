(ns bb-agent.test-roster
  "Assigned vs executed: which test namespaces does any runner actually name?

   Stolen from liuyime2/autopoiesis, `tools/verify.py:3453-3484`
   (`check_all_tests_classified`): \"Every test file must be assigned, and at
   least one assigned class must actually run it. **Assignment alone is a
   weaker guarantee than it looks.** The map is an accounting of intent, not
   evidence of execution, and a file whose only class inspects the repository
   rather than running pytest contributes nothing to the gate **while looking
   fully covered**.\" Three distinct failure kinds over one pair of sets —
   unknown (on disk, unassigned), ghost (assigned, absent), never_run (assigned
   to something that does not run it).

   Our shape: `clojure.test` runs nothing on load, so a `*_test.clj` file that
   no `(t/run-tests …)` call names is a suite that is invisible to every gate
   while `bb test:bb-agent` stays green. That is the failure mode this ns
   reports.

   Why this exists as a boundary and not as a note: the measurement was first
   taken 2026-11-10 as a throwaway `/tmp` script (the 2026-10-14 `bb coords`
   shape — a real finding nobody can re-run). Taking it a second time here
   exposed a false negative IN THE INSTRUMENT: filtering runner files on
   `defn -main` reported five real `bb-agent.goal.*` suites as NEVER-RUN,
   because `bb -m ns` executes the file on LOAD and `run_tests.clj` has no
   `-main`. So a runner is any file that names `run-tests`, not any file with
   an entry point — and the report prints how many runners it read, because a
   measurement that hides its own input is how a clean number gets printed
   over an empty walk (zeph's pattern-rot rule, 2026-10-19).

   Report-only by construction: nothing here edits bb.edn, skips a suite or
   fails a build. `guard-placement` says the cheapest sufficient guard goes
   where the information and the mandate are; here that is a human reading a
   list, so exit 0 with the counts and 2 only when the root does not exist
   (not-found is not an empty roster)."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private ns-re #"\(ns\s+([a-z0-9][a-z0-9.\-]*)")
(def ^:private quoted-test-re #"(?:'|quote\s+)([a-z0-9][a-z0-9.\-]*-test)\b")
(def ^:private required-test-re #"\[([a-z0-9][a-z0-9.\-]*-test)\]")

(defn- slurp-or-nil
  "A file that cannot be read is nil, never \"\" — an unreadable file must not
   count as a file that declares nothing (not-found is not zero, 2026-10-14)."
  [path]
  (try (slurp (str path)) (catch Exception _ nil)))

(defn test-files
  "Every `test/**/*_test.clj`, as [{:rel :ns}]. `:ns` is nil when the file
   declares no parseable ns form — reported separately, never dropped."
  [root]
  (->> (fs/glob (str (fs/path root "test")) "**/*_test.clj")
       (mapv (fn [f]
               (let [body (slurp-or-nil f)]
                 {:rel (str (fs/relativize (fs/path root "test") f))
                  :ns (some->> body (re-find ns-re) second)})))
       (sort-by :rel)
       vec))

(defn runner-texts
  "`bb.edn` plus every file under `test/` that hands namespaces to `run-tests`.
   Two stated rules, both learned by measurement rather than by taste:

   * A runner is not a file with a `-main`. `bb -m ns` executes the file ON
     LOAD, which is how `test/bb_agent/goal/run_tests.clj` (no `-main`) runs
     twenty-one suites. My first probe filtered on `defn -main` and reported
     five real goal suites as NEVER-RUN — a false negative produced by the
     instrument, which is the whole reason this ns prints its own input count.
   * `*_test.clj` files are SUITES, not runners. Reading them as runners
     counted this ns's own fixture literals (`x.one-test`, `x.gone-test`,
     `x.deep-test`) as three GHOST namespaces on the live corpus. A suite that
     also runs others must still be named by `bb.edn` to be reachable, so
     excluding them loses no executed suite — verified: 117 named either way.

   Returns [text count] so the caller can print the denominator beside the
   verdict rather than letting an empty scan read as a clean corpus."
  [root]
  (let [bb-edn (slurp-or-nil (fs/path root "bb.edn"))
        files (->> (fs/glob (str (fs/path root "test")) "**/*.clj")
                   (remove #(str/ends-with? (fs/file-name %) "_test.clj"))
                   (keep (fn [f]
                           (when-let [b (slurp-or-nil f)]
                             (when (str/includes? b "run-tests") b)))))]
    [(str/join "\n" (concat [bb-edn] files)) (count files)]))

(defn roster
  "{:status :read|:missing :files n :assigned n :executed n :runner-files n
    :on-disk #{} :executed-ns #{} :never-run [row] :ghost [ns]
    :run-but-not-required [ns] :unparsed [rel]}.

   * :never-run — on disk, and no runner names it. The quiet one: it looks
     fully covered because the suite beside it is green.
   * :ghost — named by a runner, no file declares it. An accounting of intent
     with no execution behind it.
   * :run-but-not-required — named by `run-tests` but loaded by no `:require`,
     so the name would not resolve unless something else loaded it first.
   * :unparsed — a file whose ns could not be read. Kept out of :on-disk and
     counted, because folding it into \"nothing\" is exactly the bug."
  [root]
  (if-not (and (fs/exists? (fs/path root "bb.edn")) (fs/exists? (fs/path root "test")))
    {:status :missing :root (str root)}
    (let [[rtxt runners] (runner-texts root)
          files (test-files root)
          named (set (map second (re-seq quoted-test-re rtxt)))
          required (set (map second (re-seq required-test-re rtxt)))
          identified (->> files (keep :ns) set)
          unparsed (->> files (remove :ns) (mapv :rel))]
      {:status :read
       :root (str root)
       :files (count files)
       :runner-files runners
       :assigned (count required)
       :executed (count named)
       :on-disk identified
       :executed-ns named
       :never-run (->> files (filterv #(and (:ns %) (not (named (:ns %))))))
       :ghost (vec (sort (set/difference named identified)))
       :run-but-not-required (vec (sort (set/difference named required)))
       :unparsed unparsed})))

(defn roster-report
  "Lines for the CLI. Counts carry their denominator, the evidence class is
   stated, and the negative scope says what a green roster does NOT prove."
  [r]
  (if (= :missing (:status r))
    [(str "test roster: MISSING — no bb.edn + test/ under " (:root r))
     "not-checked: nothing was measured, so this is not an empty roster."]
    (concat
     [(format "test roster: %d file(s) on disk | %d named by a run-tests call | %d required | read %d runner file(s) + bb.edn"
              (:files r) (:executed r) (:assigned r) (:runner-files r))]
     (if (seq (:never-run r))
       (concat [(format "NEVER-RUN (%d) — on disk, no runner names it, so it looks covered and asserts nothing:"
                        (count (:never-run r)))]
               (mapv #(format "  %s [test/%s]" (:ns %) (:rel %)) (:never-run r)))
       ["never-run: 0"])
     (if (seq (:ghost r))
       (concat [(format "GHOST (%d) — named by a runner, no file declares it:" (count (:ghost r)))]
               (mapv #(str "  " %) (:ghost r)))
       ["ghost: 0"])
     (if (seq (:run-but-not-required r))
       (concat [(format "RUN-BUT-NOT-REQUIRED (%d):" (count (:run-but-not-required r)))]
               (mapv #(str "  " %) (:run-but-not-required r)))
       ["run-but-not-required: 0"])
     (if (seq (:unparsed r))
       (concat [(format "UNPARSED (%d) — a test file whose ns could not be read; not counted as identified:"
                        (count (:unparsed r)))]
               (mapv #(str "  test/" %) (:unparsed r)))
       ["unparsed: 0"])
     ["evidence class: a static read of bb.edn and the runner files. A name in a run-tests call proves the suite is NAMED, not that any suite passed and not that the task that names it is reachable from CI."
      "negative scope: no suite was executed here; a runner reached only through a path this scan does not read (a shell string built at runtime, a cron script outside test/) would leave a suite looking never-run."])))

(defn -main
  "Usage: bb test-roster [repo-root]. Exit 0 = report printed, 2 = missing root."
  [args]
  (let [root (or (first args) ".")]
    (doseq [line (roster-report (roster root))] (println line))
    (if (= :missing (:status (roster root))) 2 0)))
