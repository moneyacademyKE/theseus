(ns bb-agent.knowledge-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.knowledge :as k]
            [bb-agent.tool.file :as tool-file]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *dir* nil)

(defn tmp-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "knowledge-test"}))]
    (binding [*dir* d]
      (spit (str d "/fresh.md") "# Fresh\nlast-reviewed: 2026-09-20\n")
      (spit (str d "/old.md") "# Old\nlast-reviewed: 2026-08-01\n")
      (spit (str d "/unmarked.md") "# Unmarked\nbody\n")
      (f))
    (fs/delete-tree d)))

(use-fixtures :each tmp-dir)

(def ^:private today (java.time.LocalDate/parse "2026-09-27"))
(def ^:private opts {:today today :max-age-days 30})

(deftest stale-pages-test
  (testing "flags stale and unmarked, skips fresh"
    (let [found (k/stale-pages *dir* opts)]
      (is (= ["old.md" "unmarked.md"]
             (mapv (comp fs/file-name fs/path :file) found)))
      (is (= [:stale :unmarked] (mapv :status found)))))
  (testing "empty dir = empty report, not an error"
    (let [d (str (fs/create-temp-dir))]
      (is (empty? (k/stale-pages d opts)))
      (fs/delete-tree d))))

(deftest stamp-review-test
  (let [p (str *dir* "/old.md")]
    (testing "replaces an existing mark"
      (k/stamp-review! p today)
      (is (= :fresh (:status (k/page-status p {:today today :max-age-days 30})))))
    (testing "adds a mark to an unmarked page"
      (let [u (str *dir* "/unmarked.md")]
        (k/stamp-review! u today)
        (is (re-find #"last-reviewed: 2026-09-27" (slurp u))))
      (testing "and left a snapshot behind"
        (is (seq (fs/glob *dir* "*.bak-*")))))))

(deftest record-win-test
  (let [w (str *dir* "/wins.md")]
    (testing "blank evidence is refused, loudly"
      (is (thrown? Exception (k/record-win! w {:what "x" :evidence ""})))
      (is (not (fs/exists? w))))
    (testing "evidence-bearing win appends"
      (k/record-win! w {:what "fallback fix" :evidence "tests 125/467 green, commit 0b1c54b"})
      (let [s (slurp w)]
        (is (re-find #"fallback fix" s))
        (is (re-find #"tests 125/467 green" s))))))

(deftest tier-of-test
  (testing "tier lookup is data, not judgment"
    (is (= :forbidden (k/tier-of "brain/rules.clj")))
    (is (= :forbidden (k/tier-of "config.edn")))
    (is (= :strict (k/tier-of "brain/improvements.md")))
    (is (= :soft (k/tier-of "state/usage.edn")))
    (is (= :unclassified (k/tier-of "src/bb_agent/knowledge.clj")))))

(deftest check-write-test
  (testing "forbidden always throws"
    (is (thrown? Exception (k/check-write! "brain/rules.clj" {:justification "please"}))))
  (testing "strict requires a written justification"
    (is (thrown? Exception (k/check-write! "brain/x.md" {})))
    (is (:allowed (k/check-write! "brain/x.md" {:justification "moe approved 2026-09-27"}))))
  (testing "soft passes through"
    (is (:allowed (k/check-write! "state/foo.edn" {})))))

(deftest snapshot-test
  (let [p (str *dir* "/snap.md")]
    (spit p "v1")
    (testing "existing file gets a timestamped copy"
      (let [bak (k/snapshot! p)]
        (is (some? bak))
        (is (= "v1" (slurp bak)))))
    (testing "missing file returns nil, no crash"
      (is (nil? (k/snapshot! (str *dir* "/nope.md")))))))

(deftest curator-candidates-test
  (testing "dry-run flags problems and duplicate titles, archives nothing"
    (spit (str *dir* "/dupe.md") "# Old\nlast-reviewed: 2026-09-01\n")
    (let [r (k/curator-candidates *dir* opts)]
      (is (contains? (set (map (comp str fs/path :file) (:problems r)))
                     (str *dir* "/old.md")))
      (is (= [{:title "Old" :files [(str *dir* "/dupe.md") (str *dir* "/old.md")]}]
             (:duplicate-titles r)))
      ;; dry-run: nothing archived, nothing written
      (is (= 4 (count (fs/glob *dir* "*.md")))))))

(deftest promote-gate-test
  (testing "all green = :promote"
    (let [r (k/promote-gate {"new-check" (constantly {:ok true :detail "new ✓"})
                             "prior-suite" (constantly {:ok true :detail "125/467 ✓"})})]
      (is (= :promote (:verdict r)))
      (is (every? :ok (vals (:results r))))))
  (testing "any regression blocks — recorded, not silent"
    (let [r (k/promote-gate {"new-check" (constantly {:ok true :detail "new ✓"})
                             "prior-suite" (fn [] {:ok false :detail "2 broke"})})]
      (is (= :block (:verdict r)))
      (is (= "2 broke" (get-in r [:results "prior-suite" :detail])))))
  (testing "a throwing check blocks rather than crashing the gate"
    (let [r (k/promote-gate {"boom" (fn [] (throw (ex-info "x" {})))})]
      (is (= :block (:verdict r)))
      (is (re-find #"threw" (get-in r [:results "boom" :detail]))))))

;; ------------------------------------------------------------------ prune
;; Antarsoft/morphness: a rule earns its place in always-loaded context by
;; having *fired*, not by having been written. A fire count derived from the
;; ledgers is certainty level 4 ("ran it"); a hand-written `last-reviewed:`
;; line is level 1 ("said so"). Red first — these failed before page-fires,
;; never-fired and the curator's :never-fired key existed.

(deftest page-fires-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-ledgers"}))
        ledger (str ld "/improvements.md")
        page-a (str *dir* "/refusal-classes.md")
        page-z (str *dir* "/assertion-provenance.md")]
    (spit ledger (str/join "\n"
                           ["## 2026-10-13T06:00Z — scout lane"
                            "- lens: match against refusal-classes.md before writing prose"
                            ""
                            "## cupcake777/brain"
                            "- lens: extends refusal-classes.md, per theme-dedup-at-propose"
                            ""]))
    (testing "a cited page counts its citations and names its slug"
      (let [f (k/page-fires page-a [ledger])]
        (is (= "refusal-classes" (:slug f)))
        (is (= 2 (:fires f)))))
    (testing "an uncited page measures zero — that is the prune's question"
      (is (= 0 (:fires (k/page-fires page-z [ledger])))))
    (testing "last-fired is the latest dated heading, not the last line read"
      (is (= "2026-10-13" (:last-fired (k/page-fires page-a [ledger])))))
    (testing "a citation under an undated heading still fires, with no date to claim"
      (let [f (k/page-fires (str *dir* "/theme-dedup-at-propose.md") [ledger])]
        (is (= 1 (:fires f)))
        (is (nil? (:last-fired f)))))
    (testing "a missing ledger is an empty measurement, never an exception"
      (is (= 0 (:fires (k/page-fires page-a [(str ld "/nope.md")])))))
    (fs/delete-tree ld)))

(deftest never-fired-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-ledgers"}))
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-13\n- lens: see fresh.md only\n")
    (testing "only the uncited pages are listed, sorted by slug"
      (is (= ["old" "unmarked"] (mapv :slug (k/never-fired *dir* [ledger])))))
    (testing "the zero is on the record — measured, not asserted"
      (is (every? zero? (map :fires (k/never-fired *dir* [ledger])))))
    (testing "no ledgers at all is unclaimable — SUPERSEDED 2026-10-14.
      This assertion used to read (= 3 (count (never-fired ... []))): with
      nothing read, every page was reported as never fired. Not-found is not
      zero (aden-hive/hive), so the corrected contract is an empty question
      list and three rows in unverified-zero instead."
      (is (empty? (k/never-fired *dir* []))))
    (fs/delete-tree ld)))

(deftest curator-candidates-prune-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-ledgers"}))
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-13\n- lens: cites fresh.md\n")
    (testing "with :ledger-paths the curator reports the never-fired pages"
      (let [r (k/curator-candidates *dir* (assoc opts :ledger-paths [ledger]))]
        (is (= ["old" "unmarked"] (mapv :slug (:never-fired r))))))
    (testing "without :ledger-paths the report is unchanged — no regression"
      (is (not (contains? (k/curator-candidates *dir* opts) :never-fired))))
    (fs/delete-tree ld)))

(deftest prune-cli-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-ledgers"}))
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-13\n- lens: cites fresh.md\n")
    (testing "prune reports every page and exits 0 — a report, never an archive"
      (let [out (with-out-str (is (= 0 (k/-main ["prune" *dir* ledger]))))]
        (is (str/includes? out "fresh"))
        (is (str/includes? out "NEVER"))
        (is (str/includes? out "fires=1"))))
    (testing "usage exits 2"
      (is (= 2 (k/-main []))))
    (testing "a missing ledger path exits 2 rather than measuring nothing"
      (is (= 2 (k/-main ["prune" *dir* (str ld "/nope.md")]))))
    (fs/delete-tree ld)))

;; ------------------------------------------------- not found is not zero
;; Stolen from aden-hive/hive: in its own demo the queen audits the workers'
;; rows and CLEARS one, so "the chart shows Cal.com as not found rather than
;; zero." A missing ledger must not be reported in the same shape as a measured
;; zero. Red first: before this, ledger-mentions returned [0 []] for an absent
;; file, so `bb knowledge curator` printed a false NEVER under a THESEUS_HOME
;; whose ledgers were rotated — reproduced 2026-10-14 with /tmp/hive-probe: a
;; page cited 4x in the real ledger was listed as never fired, exit 0.

(deftest page-fires-distinguishes-missing-from-zero-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-missing"}))
        ledger (str ld "/improvements.md")
        ghost (str ld "/ghost.md")
        page-a (str *dir* "/refusal-classes.md")]
    (spit ledger "## 2026-10-14\n- lens: match refusal-classes.md first\n")
    (testing "a ledger that was read says so"
      (let [f (k/page-fires page-a [ledger])]
        (is (= 1 (:fires f)))
        (is (= 1 (:ledgers-read f)))
        (is (= 0 (:ledgers-missing f)))
        (is (:verified f) "a count from a read ledger is a measurement")))
    (testing "a missing ledger is NOT a zero — it is not-found"
      (let [f (k/page-fires page-a [ghost])]
        (is (= 0 (:fires f)))
        (is (= 0 (:ledgers-read f)))
        (is (= 1 (:ledgers-missing f)))
        (is (false? (:verified f)) "nothing was read, so nothing may be claimed")))
    (testing "one read ledger beside one missing still verifies what was read"
      (let [f (k/page-fires page-a [ledger ghost])]
        (is (= 1 (:ledgers-read f)))
        (is (= 1 (:ledgers-missing f)))
        (is (:verified f))))
    (fs/delete-tree ld)))

(deftest never-fired-refuses-to-claim-when-nothing-was-read-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-unverified"}))
        ghost (str ld "/nope.md")
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-14\n- lens: cites fresh.md only\n")
    (testing "with a ledger actually read, the uncited pages are the question list"
      (is (= ["old" "unmarked"] (mapv :slug (k/never-fired *dir* [ledger])))))
    (testing "with only missing ledgers, NEVER is unclaimable — the list is empty"
      (is (empty? (k/never-fired *dir* [ghost]))))
    (testing "no ledgers at all is also unclaimable, not 'every page is dead'"
      (is (empty? (k/never-fired *dir* []))))
    (testing "the unclaimable zeros are reported separately, not silently dropped"
      (is (= ["fresh" "old" "unmarked"]
             (mapv :slug (sort-by :slug (k/unverified-zero *dir* [ghost]))))))
    (fs/delete-tree ld)))

(deftest curator-candidates-unverified-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-cur-unv"}))
        ghost (str ld "/nope.md")
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-14\n- lens: cites fresh.md\n")
    (testing "an unread ledger yields no never-fired claims, and names the gap"
      (let [r (k/curator-candidates *dir* (assoc opts :ledger-paths [ghost]))]
        (is (empty? (:never-fired r)))
        (is (= 3 (count (:unverified r))))))
    (testing "a read ledger behaves exactly as before — no regression"
      (let [r (k/curator-candidates *dir* (assoc opts :ledger-paths [ledger]))]
        (is (= ["old" "unmarked"] (mapv :slug (:never-fired r))))
        (is (empty? (:unverified r)))))
    (testing "without :ledger-paths the report shape is unchanged"
      (is (not (contains? (k/curator-candidates *dir* opts) :never-fired)))
      (is (not (contains? (k/curator-candidates *dir* opts) :unverified))))
    (fs/delete-tree ld)))

;; ------------------------------------------------- the write seam (2026-10-20)
;; Stolen from miketako3/dsh-fracture, `src/guard.ts:1-8`: "The loop edits the
;; harness that is running it, so some rows must stay out of reach no matter what
;; a diagnosis suggests. These checks run before a candidate is stored, probed, or
;; adopted: refusing early keeps a bad row out of the candidate directory entirely."
;;
;; Why this is a real gap and not a nice idea — measured before these tests were
;; written: `grep -rn "check-write!\|snapshot!" src/ scripts/` returned ZERO
;; production callers. Both functions have existed in bb-agent.knowledge since
;; 2026-09/10-10 and were referenced only from this test file, so protection-tiers
;; and pre-write-snapshots were knowledge pages whose code no boundary called.
;; The cost was already paid once: on 2026-10-14 `write_file` with `append? true`
;; clobbered protection-tiers.md and coordinate-discipline.md, and only a manually
;; taken .bak saved them.

(deftest guard-write-classifies-refuses-and-snapshots
  (let [home (str (fs/create-temp-dir {:prefix "k-seam"}))]
    (fs/create-dirs (str home "/brain"))
    (spit (str home "/brain/page.md") "# Original\nbody\n")
    (spit (str home "/brain/rules.clj") ";; the law\n")
    (with-redefs [config/home (constantly home)]
      (testing "a :forbidden path is refused before anything is written"
        (let [v (k/guard-write! (str home "/brain/rules.clj"))]
          (is (= :refused (:verdict v)))
          (is (= :forbidden (:tier v)))
          (is (= ";; the law\n" (slurp (str home "/brain/rules.clj")))
              "refusing early means the bytes never moved")))
      (testing "a :strict path is allowed and snapshotted first"
        (let [v (k/guard-write! (str home "/brain/page.md"))]
          (is (= :allowed (:verdict v)))
          (is (= :strict (:tier v)))
          (is (:snapshot v) "the snapshot path is returned, not swallowed")
          (is (= "# Original\nbody\n" (slurp (:snapshot v)))
              "the snapshot holds the pre-write bytes")))
      (testing "a path outside the home is unclassified and never snapshotted"
        (let [outside (str (fs/create-temp-dir {:prefix "k-outside"}))
              v (k/guard-write! (str outside "/notes.md"))]
          (is (= :allowed (:verdict v)))
          (is (= :unclassified (:tier v)))
          (is (nil? (:snapshot v)))
          (fs/delete-tree outside)))
      (testing "`..` is normalized, so traversal cannot dodge the tier data"
        (let [v (k/guard-write! (str home "/brain/../brain/rules.clj"))]
          (is (= :refused (:verdict v))
              "a lexically hidden rules.clj is still rules.clj")
          (is (= :forbidden (:tier v)))))
      (testing "the verdict names the path it judged — a measurement names its subject"
        (let [v (k/guard-write! (str home "/brain/page.md"))]
          (is (= (str (fs/normalize (fs/absolutize (fs/path home "brain" "page.md"))))
                 (:path v))))))
    (fs/delete-tree home)))

(deftest write-file-tool-meets-the-guard
  (let [home (str (fs/create-temp-dir {:prefix "k-tool-seam"}))
        write! (get tool-file/handlers "write_file")]
    (fs/create-dirs (str home "/brain"))
    (spit (str home "/brain/page.md") "# Original\n")
    (spit (str home "/brain/rules.clj") ";; the law\n")
    (with-redefs [config/home (constantly home)]
      (testing "a :forbidden write is an error result, and the file is untouched"
        (let [r (write! {:path (str home "/brain/rules.clj") :content "rewritten"})]
          (is (= :error (:status r)))
          (is (re-find #"forbidden" (str (:error/message r))))
          (is (= ";; the law\n" (slurp (str home "/brain/rules.clj"))))))
      (testing "a :strict write lands and leaves the pre-write bytes behind"
        (let [r (write! {:path (str home "/brain/page.md") :content "# New\n"})]
          (is (= :ok (:status r)))
          (is (= "# New\n" (slurp (str home "/brain/page.md"))))
          (is (:snapshot r) "the result carries the snapshot path")
          (is (= "# Original\n" (slurp (:snapshot r))))))
      (testing "an append? write is snapshotted too — that is the 10-14 clobber shape"
        (let [r (write! {:path (str home "/brain/page.md") :content "appended\n" :append? true})]
          (is (= :ok (:status r)))
          (is (= "# New\nappended\n" (slurp (str home "/brain/page.md"))))
          ;; SUPERSEDED 2026-10-20, append-and-supersede, same turn. The original
          ;; assertion here expected the SECOND write's snapshot to hold the
          ;; intermediate bytes. It failed with actual "# Original", which is
          ;; snapshot-unless-same-second! by design: inside one second the EARLIEST
          ;; pre-write bytes are kept, because a snapshot exists to hold what was
          ;; there before this turn touched the file. The assertion encoded the
          ;; contract I wished for, not the one implemented - same mistake class as
          ;; the pre-fix assertion on 2026-10-18. Real rule:
          (is (= "# Original\n" (slurp (:snapshot r)))
              "a second write in the same second reuses the first backup, so the
               earliest bytes win and the 10-14 clobber shape stays recoverable")))
      (testing "a write outside the home behaves exactly as before — no regression"
        (let [outside (str (fs/create-temp-dir {:prefix "k-tool-outside"}))
              r (write! {:path (str outside "/plain.md") :content "hi"})]
          (is (= :ok (:status r)))
          (is (= "hi" (slurp (str outside "/plain.md"))))
          (is (nil? (:snapshot r)))
          (is (empty? (fs/glob outside "*.bak-*")) "nothing is snapshotted outside home")
          (fs/delete-tree outside))))
    (fs/delete-tree home)))

;; ------------------------------------------------------- guard coverage
;; Stolen from alkanfel1987/pantheon-seed, memory/pattern-promotion.md:118 —
;; a named mistake class WITHOUT a mechanical check gave 6 repeats in one
;; session while classes WITH one never reached consequences, hence the
;; promotion rule "build a check, don't strengthen the wording". Our
;; page-fires counts CITATIONS of a page; nothing asked whether a machine
;; stands behind the rule. These tests went red first (unable to resolve
;; k/guard-coverage), then red again on two real defects: the CLI reused
;; missing-path (fs/regular-file?) on DIRECTORIES, so every run reported
;; "no such path" against a path that existed; and the corpus dir is the
;; knowledge subdir, not its parent — fs/glob is not recursive, the same
;; trap recorded for preflight on 2026-10-19.

(defn with-guards-corpus
  "Build a corpus with two pages — one citing a real program, one citing only
   prose — call `f` with [root knowledge-dir], tear down. NOT a fixture: this
   file already has `use-fixtures :each tmp-dir` binding *dir*, and a second
   :each fixture composes with it, so both would bind the same var and the
   guard tests would depend on fixture order. (Caught red: ArityException,
   because the composed chain handed the test fn an arg it never declared.)"
  [f]
  (let [root (str (fs/create-temp-dir {:prefix "guards-test"}))
        kdir (str root "/knowledge")]
    (fs/create-dirs kdir)
    (fs/create-dirs (str root "/src"))
    (spit (str root "/src/machine.clj") "(ns machine)\n;; line 2\n;; line 3\n")
    (spit (str kdir "/backed.md")
          "# Backed\nlast-reviewed: 2026-10-21\n\nEnforced at src/machine.clj:2 by a real function.\n")
    (spit (str kdir "/wording.md")
          "# Wording only\nlast-reviewed: 2026-10-21\n\nA rule nobody enforces. See also backed.md:1 for prose.\n")
    (try (f root kdir) (finally (fs/delete-tree root)))))

(deftest guard-coverage-names-the-machine-behind-each-page
  (with-guards-corpus
    (fn [root kdir]
      (let [rows (k/guard-coverage kdir [root])
            by (into {} (map (juxt :slug identity) rows))]
        (is (= 2 (count rows)) "both pages are measured, neither is skipped")
        (is (= #{"backed" "wording"} (set (map :slug rows))))
        (is (= ["machine.clj"] (mapv #(fs/file-name (fs/path %)) (:machines (get by "backed"))))
            "a page citing a resolving program names it, so the verdict is checkable")
        (is (empty? (:machines (get by "wording")))
            "a page citing only prose is wording-only")
        (is (empty? (:ghosts (get by "wording")))
            "citing another .md page is prose, not a ghost machine")))))

(deftest guard-coverage-g-is-the-machine-backed-share
  (with-guards-corpus
    (fn [root kdir]
      (let [r (k/guard-coverage-summary kdir [root])]
        (is (= 2 (:pages r)))
        (is (= 1 (:machine-backed r)))
        (is (= ["wording"] (mapv :slug (:wording-only r)))
            "the wording-only list is the prune's question list for enforcement")
        (is (= 0.5 (:g r)) "G = machine-backed / pages, the loop metric")))))

(deftest guard-coverage-a-dangling-code-cite-is-not-a-machine
  (with-guards-corpus
    (fn [root kdir]
      (spit (str kdir "/ghost.md")
            "# Ghost\nlast-reviewed: 2026-10-21\n\nEnforced at src/nope.clj:9.\n")
      (let [by (into {} (map (juxt :slug identity) (k/guard-coverage kdir [root])))]
        (is (empty? (:machines (get by "ghost")))
            "a cite of a program that does not exist is not a machine — counting")
        (is (= ["src/nope.clj:9"] (:ghosts (get by "ghost")))
            "and the claim is named separately, because it reads as a guard in prose"))
      (is (= 3 (:pages (k/guard-coverage-summary kdir [root])))))))

(deftest guards-cli-reports-and-names-its-negative-scope
  (with-guards-corpus
    (fn [root kdir]
      (is (= 0 (k/-main ["guards" kdir "--index" root])))
      (let [out (with-out-str (k/-main ["guards" kdir "--index" root]))]
        (is (str/includes? out "wording") "the wording-only page is named")
        (is (str/includes? out "G = 1/2") "and the loop metric is printed, not implied")
        (is (str/includes? out "does not prove")
            "the report states that a resolving cite names a program, not an enforced rule"))
      (let [empty-root (str (fs/create-temp-dir {:prefix "guards-empty"}))]
        (fs/create-dirs (str empty-root "/knowledge"))
        (let [out (with-out-str (k/-main ["guards" (str empty-root "/knowledge") "--index" empty-root]))]
          (is (str/includes? out "UNVERIFIED")
              "0 pages is not evidence of a clean corpus (zeph: a scan that finds nothing is broken until proven otherwise)")
          (is (= 0 (k/-main ["guards" (str empty-root "/knowledge") "--index" empty-root]))))
        (fs/delete-tree empty-root)))))

(deftest guard-coverage-a-guessed-cite-is-not-a-machine
  (testing "a bare basename resolved by first-match is a guess, so it cannot back a rule"
    (with-guards-corpus
      (fn [root kdir]
        ;; a second file with the same basename makes `machine.clj` ambiguous
        (fs/create-dirs (str root "/other"))
        (spit (str root "/other/machine.clj") "(ns other.machine)\n;; 2\n")
        (spit (str kdir "/guess.md")
              "# Guess\nlast-reviewed: 2026-10-31\n\nEnforced at machine.clj:2 — a bare basename.\n")
        (let [rows (k/guard-coverage kdir [root])
              g (first (filterv #(= "guess" (:slug %)) rows))]
          (is (empty? (:machines g))
              "a resolution chosen between several same-named files is a guess;
               counting it would report a machine that may not be the one cited")
          (is (= 1 (count (:guessed g)))
              "and the guess is reported separately, with the size of the choice")
          (is (empty? (:ghosts g))
              "ambiguous is not dangling — the file may well exist, we cannot say which")
          ;; SUPERSEDED 2026-10-31: this asserted 0 and was WRONG. The
          ;; corpus holds backed.md, whose src/machine.clj cite is pinned, so
          ;; machine-backed is 1 — refusing a guess must not demote a pinned
          ;; cite. Corrected rather than deleted, so the next reader does not
          ;; 'fix' the code back into counting guesses.
          (is (= 1 (:machine-backed (k/guard-coverage-summary kdir [root])))
              "G counts the pinned cite and not the guess"))))))

(deftest guard-coverage-a-pinned-cite-still-counts
  (testing "a cite specific enough to pin one file is a machine, guess or not"
    (with-guards-corpus
      (fn [root kdir]
        (fs/create-dirs (str root "/other"))
        (spit (str root "/other/machine.clj") "(ns other.machine)\n;; 2\n")
        (spit (str kdir "/pinned.md")
              "# Pinned\nlast-reviewed: 2026-10-31\n\nEnforced at src/machine.clj:2.\n")
        (let [g (first (filterv #(= "pinned" (:slug %)) (k/guard-coverage kdir [root])))]
          (is (= ["machine.clj"] (mapv #(fs/file-name (fs/path %)) (:machines g)))
              "src/machine.clj pins one file, so it is a machine")
          (is (empty? (:guessed g))))))))

;; ---------- 2026-11-01: `guards --index <dir>` was unusable ----------
;; The branch's own comment documents the invocation ("--index dirs override the
;; default index, the same way `bb coords --index` does, so a test can point the
;; resolver at a fixture tree"), and the test suite exercises it only with a
;; brain dir in front: ["guards" kdir "--index" root]. Live, the invocation a
;; human types against the real corpus is `bb knowledge guards --index
;; /Users/moe/theseus/theseus`, and it printed
;; `knowledge guards: no such path — --index` with exit 2 — argv[0] was consumed
;; as the dir, so the flag became a path. `bb coords` fixed exactly this on
;; 2026-10-19 for the same reason ("a tool that demands its own name as the
;; first argument is a tool nobody runs"). Second live defect found this turn by
;; running the tool rather than reading it.

(deftest guards-cli-accepts-index-without-a-brain-dir
  (with-guards-corpus
    (fn [root kdir]
      (testing "the documented invocation works with no dir in front of the flag"
        (let [out (with-out-str (is (= 0 (k/-main ["guards" "--index" root])))
                  "--index alone must not be read as the corpus path")]
          (is (str/includes? out "G = ")
              "it printed the metric, not a missing-path error")
          (is (not (str/includes? out "no such path")))))
      (testing "and a real dir in front still works — no regression"
        (is (= 0 (k/-main ["guards" kdir "--index" root])))
        (let [out (with-out-str (k/-main ["guards" kdir "--index" root]))]
          (is (str/includes? out "G = 1/2"))))
      (testing "a genuinely missing index dir still exits 2 and names it"
        (is (= 2 (k/-main ["guards" "--index" "/tmp/definitely-not-here-xyz"])))))))

;; ---------- 2026-11-01: the gate's unit is an independent event, not a line ----------
;; Stolen from phense/agentic-rag `docs/pattern-synthesis.md`: a pattern needs "at least
;; three independent user events across three different memories", and "Two quotes count
;; as the same event when they come from the same source ... so an imported copy of one
;; report does not count twice". Our crystallization gate says >=2 observations, while
;; page-fires counts LINES. Measured on the live corpus before these tests existed: 22 of
;; 28 pages have more cites than distinct dated headings — coordinate-discipline 23 cites
;; over 9 dates, mechanical-confidence-supersede 23 over 7 — so one day's entry naming a
;; page six times reports fires=6. A self-referential amplification we already met twice
;; (page-fires on 10-13, bb coords on 10-14) and never fixed at the root.

(deftest page-fires-counts-events-not-lines-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-events"}))
        ledger (str ld "/improvements.md")
        page-a (str *dir* "/refusal-classes.md")]
    (spit ledger (str/join "\n"
                           ["## 2026-10-13T06:00Z — scout lane"
                            "- lens: match refusal-classes.md before writing prose"
                            "- lens: extends refusal-classes.md again in the same entry"
                            ""
                            "## cupcake777/brain"
                            "- lens: refusal-classes.md is the registry"
                            ""
                            "## 2026-11-01T07:00Z — scout lane"
                            "- lens: refusal-classes.md matched twice today"
                            ""]))
    (testing "three cites on one day is ONE observation, not three"
      (let [r (k/page-fires page-a [ledger])]
        (is (= 4 (:fires r)) "lines are still counted — no regression")
        (is (= 2 (:events r)) "distinct dated headings are the independent events")
        (is (= "2026-11-01" (:last-fired r)))))
    (fs/delete-tree ld)))

(deftest page-fires-undated-cites-are-not-a-false-zero-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-undated"}))
        ledger (str ld "/improvements.md")
        page-a (str *dir* "/refusal-classes.md")]
    (spit ledger "## cupcake777/brain\n- lens: refusal-classes.md, undated heading\n")
    (testing "a cite under an undated heading fires but cannot be dated"
      (let [r (k/page-fires page-a [ledger])]
        (is (= 1 (:fires r)))
        (is (= 0 (:events r)) "an undated cite is not an event we can count")
        (is (= 1 (:undated r)) "and it is reported, not silently dropped — not-found is not zero")
        (is (nil? (:last-fired r)))))
    (testing "mixed: dated events plus an undated remainder both appear"
      (spit ledger "## 2026-11-01T07:00Z\n- lens: refusal-classes.md dated\n## repo\n- lens: refusal-classes.md not\n")
      (let [r (k/page-fires page-a [ledger])]
        (is (= 2 (:fires r)))
        (is (= 1 (:events r)))
        (is (= 1 (:undated r)))))
    (fs/delete-tree ld)))

(deftest prune-report-names-the-event-unit-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-rep"}))
        ledger (str ld "/improvements.md")]
    (spit ledger "## 2026-10-13\n- lens: fresh.md twice\n- lens: fresh.md again\n")
    (testing "the report prints events beside fires, so a reader cannot confuse the units"
      (let [out (str/join "\n" (k/prune-report (k/fire-counts *dir* [ledger])))]
        (is (str/includes? out "fresh"))
        (is (str/includes? out "fires=2"))
        (is (str/includes? out "events=1")
            "two lines on one day is one observation, and the report says so")))
    (testing "and the CLI prints it too — a measurement no boundary calls measures nothing"
      (let [out (with-out-str (k/-main ["prune" *dir* ledger]))]
        (is (str/includes? out "events="))))
    (fs/delete-tree ld)))

(deftest dateless-names-cited-but-undatable-pages-test
  (let [ld (str (fs/create-temp-dir {:prefix "k-dateless"}))
        ledger (str ld "/improvements.md")
        page-a (str *dir* "/refusal-classes.md")]
    ;; the fixture dir holds fresh/old/unmarked only; the cited page has to exist
    ;; for `dateless` to see it, since it walks the corpus rather than the ledger
    (spit page-a "# Refusal classes\nbody\n")
    (spit ledger "## cupcake777/brain\n- lens: match refusal-classes.md first\n")
    (testing "a cite under an undated heading is neither NEVER nor dated"
      (let [d (k/dateless *dir* [ledger])]
        (is (= ["refusal-classes"] (mapv :slug d)))
        (is (= 1 (:fires (first d))))
        (is (= 0 (:events (first d))))))
    (testing "and never-fired does NOT claim it — the 10-14 false NEVER, refused again"
      (is (not-any? #{"refusal-classes"} (mapv :slug (k/never-fired *dir* [ledger])))))
    (testing "the report prints DATELESS, a third verdict beside NEVER and UNVERIFIED"
      (let [out (str/join "\n" (k/prune-report (k/fire-counts *dir* [ledger])))]
        (is (str/includes? out "[DATELESS]"))
        (is (str/includes? out "[NEVER]"))))
    (testing "the CLI names the bucket and explains the two units"
      (let [out (with-out-str (is (= 0 (k/-main ["prune" *dir* ledger]))))]
        (is (str/includes? out "dateless"))
        (is (str/includes? out "units: fires = citing lines"))))
    (fs/delete-tree ld)))

;; ---------- 2026-11-03: an mtime is not proof of content ----------

(deftest tier-digests-reads-bytes-not-mtime-test
  (testing "a :forbidden file reports PRISTINE against its recorded sha256"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-test"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n;; the law\n")
          sha (k/sha256-file (str rules))
          d (k/tier-digests home {sha "brain/rules.clj"})
          ;; SUPERSEDED 2026-11-03: the first version of this assertion expected ONE
          ;; row. protection-tiers names TWO :forbidden paths (brain/rules.clj and
          ;; config.edn), and tier-digests covers every one by design — a missing law
          ;; must be reportable, so a fixture that creates only rules.clj legitimately
          ;; yields a second row with verdict :missing. Selecting the row under test
          ;; instead of counting rows.
          row (first (filter #(= "brain/rules.clj" (:rel %)) d))]
      (is (= 2 (count d)) "both :forbidden paths are digested, not just the fixture's")
      (is (= :missing (:verdict (first (filter #(= "config.edn" (:rel %)) d))))
          "the fixture has no config.edn, and that is its own verdict")
      (is (= :pristine (:verdict row)))
      (is (= "brain/rules.clj" (:rel row)))
      (is (= sha (:expected row)))
      (is (= sha (:current row)))
      (fs/delete-tree (fs/path home))))
  (testing "changed bytes are TAMPERED even when the mtime is preserved"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-test"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n;; the law\n")
          sha (k/sha256-file (str rules))
          before (fs/last-modified-time rules)
          _ (spit (str rules) "(ns rules)\n;; quietly rewritten\n")
          _ (fs/set-last-modified-time rules before)
          d (k/tier-digests home {sha "brain/rules.clj"})
          row (first (filter #(= "brain/rules.clj" (:rel %)) d))]
      (is (= :tampered (:verdict row))
          "the mtime says untouched; the bytes say otherwise")
      (is (= sha (:expected row)))
      (is (not= sha (:current row)))
      (fs/delete-tree (fs/path home))))
  (testing "a file that is MISSING is not PRISTINE and not TAMPERED — not-found is its own verdict"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-test"}))
          _ (fs/create-dirs (fs/path home "brain"))
          d (k/tier-digests home {"0000000000000000000000000000000000000000000000000000000000000000"
                                  "brain/rules.clj"})]
      (is (= :missing (:verdict (first d)))
          "a missing law is not an intact law")
      (is (nil? (:current (first d))))
      (fs/delete-tree (fs/path home)))))

(deftest tiers-cli-reports-and-never-mutates-test
  (testing "exit 0 with a report; the report names every tier path and its verdict"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-cli"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n")
          ;; SUPERSEDED 2026-11-03: the first fixture wrote only brain/rules.clj, and
          ;; the CLI then exited 1 on config.edn being :missing — which is the design,
          ;; not a bug. Both :forbidden paths get baseline entries.
          cfg (fs/path home "config.edn")
          _ (fs/create-dirs (fs/path home))
          _ (spit (str cfg) "{}\n")
          sha (k/sha256-file (str rules))
          sha2 (k/sha256-file (str cfg))
          baseline (str home "/tier-baseline.edn")
          _ (spit baseline (pr-str {sha "brain/rules.clj" sha2 "config.edn"}))
          out (binding [k/*tiers-baseline* baseline]
                (with-out-str (is (= 0 (k/-main ["tiers" home])))))]
      (is (str/includes? out "PRISTINE"))
      (is (str/includes? out "brain/rules.clj"))
      (is (str/includes? out (subs sha 0 12)))
      (is (str/includes? out "not checked")
          "states what the digest does not prove, per negative-scope-receipts")
      (fs/delete-tree (fs/path home))))
  (testing "a tampered tier makes the CLI exit 1 — the one non-zero exit this report has"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-cli2"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n")
          cfg (fs/path home "config.edn")
          _ (spit (str cfg) "{}\n")
          sha (k/sha256-file (str rules))
          sha2 (k/sha256-file (str cfg))
          baseline (str home "/tier-baseline.edn")
          _ (spit baseline (pr-str {sha "brain/rules.clj" sha2 "config.edn"}))
          _ (spit (str rules) "(ns rules) ;; changed\n")
          out (binding [k/*tiers-baseline* baseline]
                (with-out-str (is (= 1 (k/-main ["tiers" home])))))]
      (is (str/includes? out "TAMPERED"))
      (is (str/includes? out "expected"))
      (fs/delete-tree (fs/path home)))))

;; ---------------------------------------------------------------------
;; 2026-11-03 — the guards report's denominator, red first.
;;
;; Source, twice, both level 2:
;; * bug-ops/zeph `.github/scripts/check_integrity_audit.sh` — a scan that finds
;;   nothing is broken until proven otherwise.
;; * hung12ct/culi `docs/evaluation.md` — abstain accuracy sits beside hit rate,
;;   because a layer that always answers has no measured failure mode; and
;;   `internal/store/effectiveness.go` — "one quiet session proves nothing".
;;
;; Measured live BEFORE these tests existed:
;;   bb knowledge guards --index /tmp/<empty>
;; printed `G = 1/29 pages machine-backed (3%)` plus five GHOST rows naming our
;; own real files (`src/bb_agent/knowledge.clj:100`, `src/bb_agent/tool/file.clj:73`,
;; `src/bb_agent/preflight.clj:144` ...) as "no such file/line", exit 0, with
;; nothing saying the walk saw no code at all. The single page still counted as
;; machine-backed was coordinate-discipline, whose cite is ABSOLUTE and so
;; resolves without the index (the 2026-10-18 fix) — i.e. the only surviving
;; verdict was the one that did not depend on the rotted measurement.

(deftest index-code-files-measures-the-walk-test
  (testing "counts code-extension files, distinct, and nothing else"
    (let [d (str (fs/create-temp-dir {:prefix "index-walk"}))]
      (fs/create-dirs (fs/path d "src"))
      (fs/create-dirs (fs/path d "docs"))
      (spit (str d "/src/one.clj") "(ns one)\n")
      (spit (str d "/src/two.bb") "(println 1)\n")
      (spit (str d "/src/three.sh") "#!/bin/sh\n")
      (spit (str d "/docs/readme.md") "# hi\n")
      (spit (str d "/docs/notes.txt") "hi\n")
      (is (= 3 (count (k/index-code-files [d])))
          "three code files; .md and .txt are prose, not machines")
      (is (empty? (k/index-code-files [(str d "/nope")]))
          "a missing dir yields nothing rather than throwing")
      (is (= 3 (count (k/index-code-files [d d])))
          "the same dir twice counts its files once — the 2026-11-01 double-walk lesson")
      (fs/delete-tree (fs/path d)))))

(deftest guards-summary-carries-its-denominator-test
  (testing "a rate names the size of the walk it came from"
    (let [d (str (fs/create-temp-dir {:prefix "gc-denom"}))
          src (str d "/repo")]
      (fs/create-dirs (fs/path src "src"))
      (spit (str src "/src/thing.clj") "(ns thing)\n")
      (spit (str d "/page.md") "# Page\nSee src/thing.clj:1 for the guard.\n")
      (let [s (k/guard-coverage-summary d [src] [])]
        (is (= 1 (get-in s [:index :code-files])))
        (is (= [src] (get-in s [:index :dirs])))
        (is (= 1 (:machine-backed s))))
      (fs/delete-tree (fs/path d)))))

(deftest guards-report-flags-an-empty-index-walk-test
  (testing "zero code files in the walk = UNVERIFIED, never confident verdicts"
    (let [d (str (fs/create-temp-dir {:prefix "gc-empty"}))
          empty (str d "/empty-index")]
      (fs/create-dirs (fs/path empty))
      (spit (str d "/page.md") "# Page\nSee src/thing.clj:1 for the guard.\n")
      (let [s (k/guard-coverage-summary d [empty] [])
            out (str/join "\n" (k/guards-report s))]
        (is (zero? (get-in s [:index :code-files])))
        (is (str/includes? out "[UNVERIFIED]"))
        (is (str/includes? out "index walk saw NO code file"))
        (is (str/includes? out "describes the index, not the corpus")
            "says whose blind spot the GHOST rows are, so a reader cannot act on them")
        (is (str/includes? out "index: 0 code file(s)")))
      (fs/delete-tree (fs/path d))))
  (testing "a walk that sees code files prints no UNVERIFIED line"
    (let [d (str (fs/create-temp-dir {:prefix "gc-real"}))
          src (str d "/repo")]
      (fs/create-dirs (fs/path src "src"))
      (spit (str src "/src/thing.clj") "(ns thing)\n")
      (spit (str d "/page.md") "# Page\nSee src/thing.clj:1 for the guard.\n")
      (let [out (str/join "\n" (k/guards-report (k/guard-coverage-summary d [src] [])))]
        (is (not (str/includes? out "[UNVERIFIED]"))
            "no vacuous zero here, so no false alarm either")
        (is (str/includes? out "index: 1 code file(s) under 1 dir(s)")))
      (fs/delete-tree (fs/path d)))))

(deftest guards-negative-scope-says-a-ghost-is-about-the-walk-test
  (let [d (str (fs/create-temp-dir {:prefix "gc-neg"}))
        src (str d "/repo")]
    (fs/create-dirs (fs/path src "src"))
    (spit (str src "/src/thing.clj") "(ns thing)\n")
    (spit (str d "/page.md") "# Page\nSee src/thing.clj:1 for the guard.\n")
    (let [out (str/join "\n" (k/guards-report (k/guard-coverage-summary d [src] [])))]
      (is (str/includes? out "GHOST means no resolving file IN THE INDEX WALK"))
      (is (str/includes? out "not the same as no such file on disk")))
    (fs/delete-tree (fs/path d))))

;; ---------------------------------------------------------------------
;; 2026-11-03 — the brain index's discriminativeness, red first.
;;
;; Source, level 2: hung12ct/culi `docs/evaluation.md` — a retrieval layer is
;; scored on five metrics where "Abstain accuracy: Share of abstain cases (empty
;; `expect`) where nothing was injected" sits beside hit rate, and the suite
;; "keeps its failing cases; they are the useful part". Our index cannot abstain
;; (it rides whole, by design), so the honest analogue is a line that routes to
;; NOTHING: too few content terms, or indistinguishable from another.
;;
;; Measured once with a throwaway script in /tmp on 2026-11-03 (29 lines, terms
;; 3-11, max Jaccard 0.200, zero content-free lines) and left there — `bb coords`
;; on 2026-10-14 exactly: a real finding produced by a script no boundary calls.

(deftest routing-terms-drops-words-that-cannot-route-test
  (is (= #{"guard" "writes" "snapshot" "file"}
         (k/routing-terms "A guard that writes a snapshot of the file"))
      "stopwords and <=2-char tokens carry no routing information; 'file' is a
       real content term (4 chars), so the expectation names it rather than
       assuming a length filter the fn does not have")
  (is (= #{} (k/routing-terms "the of and to"))
      "a scope of pure stopwords has zero content terms — the abstain case"))

(deftest routing-rows-flags-a-line-that-cannot-select-its-page-test
  (let [rows (k/routing-rows ["alpha.md — a page"
                              "beta.md — protection tiers make edit rights data not prose"
                              "gamma.md — snapshots before an automated write"])]
    (is (= 3 (count rows)))
    (is (:thin? (first rows))
        "'a page' has no content term at all, so it cannot route")
    (is (not (:thin? (second rows))))
    (is (= 1 (:terms (first rows)))
        "'page' is a content term; THIN fires on <3, not on zero")
    (is (false? (:collision? (first rows)))
        "the flag is a boolean, never nil")))

(deftest routing-rows-flags-two-scopes-a-reader-cannot-choose-between-test
  (let [rows (k/routing-rows ["staleness.md — knowledge page staleness marks review date"
                              "curator.md — knowledge page curator marks review date"])]
    (is (every? :collision? rows)
        "both sides are flagged, because neither can be selected over the other")
    (is (= "curator.md" (:nearest (first rows))))
    (is (<= k/routing-collision-jaccard (:max-jaccard (first rows))))))

(deftest routing-report-names-its-own-denominator-and-misses-test
  (let [clean (k/routing-report (k/routing-rows ["a.md — alpha beta gamma delta"
                                                 "b.md — epsilon zeta eta theta"]))]
    (is (str/includes? (first clean) "routing: 2 index line(s)"))
    (is (str/includes? (first clean) "THIN 0 | COLLISION 0"))
    (is (some #(str/includes? % "known misses: none") clean)
        "a clean run says so explicitly instead of printing nothing")
    (is (some #(str/includes? % "negative scope") clean)))
  (let [flagged (k/routing-report (k/routing-rows ["a.md — a page"
                                                   "b.md — one two three four"]))]
    (is (some #(str/includes? % "THIN a.md") flagged))
    (is (some #(str/includes? % "they are the useful part") flagged)
        "culi keeps its failing cases; so does this report")))

(deftest routing-report-refuses-to-call-an-empty-index-clean-test
  (let [out (k/routing-report (k/routing-rows []))]
    (is (some #(str/includes? % "[UNVERIFIED] 0 index lines") out)
        "an empty index is a rotted walk or an empty corpus, not a routable one")
    (is (str/includes? (first out) "routing: 0 index line(s)"))))

(deftest routing-cli-test
  (testing "the documented invocation exits 0 and prints the measurement"
    (let [out (with-out-str (is (= 0 (k/-main ["routing"]))))]
      (is (str/includes? out "routing:"))
      (is (str/includes? out "negative scope"))))
  (testing "usage still exits 2 for an unknown subcommand"
    (with-out-str (is (= 2 (k/-main ["nope"]))))))

(deftest stamp-review-preserves-the-marker-prefix-test
  ;; Red first, and the bug is measured on the live corpus, not hypothesised:
  ;; stamp-review! rebuilt the line as (str "last-reviewed: " today), so a
  ;; `- last-reviewed:` LIST ITEM lost its dash. 3 of the 29 knowledge pages
  ;; were provably mangled that way (bare now, dashed in their own .bak):
  ;; coordinate-discipline.md:41, governed-evidence-stamps.md:71,
  ;; negative-scope-receipts.md:61 — the last by the 2026-11-04 stamp run.
  ;; Silent because review-re matches both shapes, so page-status never failed.
  (testing "a dashed marker keeps its dash, a bare one stays bare"
    (let [dashed (str *dir* "/dashed.md")
          bare (str *dir* "/bare.md")]
      (spit dashed "# D\n\n- last-reviewed: 2026-11-01\nbody\n")
      (spit bare "# B\n\nlast-reviewed: 2026-11-01\nbody\n")
      (k/stamp-review! dashed (java.time.LocalDate/parse "2026-11-04"))
      (k/stamp-review! bare (java.time.LocalDate/parse "2026-11-04"))
      (is (some #(= % "- last-reviewed: 2026-11-04") (str/split-lines (slurp dashed)))
          "the list dash must survive — un-listing the mark is the bug")
      (is (some #(= % "last-reviewed: 2026-11-04") (str/split-lines (slurp bare)))
          "a bare marker is not re-listed either")
      (is (= 1 (count (re-seq #"(?im)^- last-reviewed:" (slurp dashed))))
          "exactly one dashed mark, so the read-back count is meaningful")
      (is (= :fresh (:status (k/page-status dashed))))
      (is (= :fresh (:status (k/page-status bare)))))))

;; ---------------------------------------------------------------------
;; 2026-11-07 — the integrity report's own reference is writable by the same
;; hand, and the report said nothing about it. Red first.
;;
;; Source, level 2: Sppqq/nook
;; * https://github.com/Sppqq/nook/blob/main/nook_update.py `verify_source` —
;;   `expected = set(manifest['sha256']) | {'MANIFEST.json'}` vs `actual` over
;;   `rglob('*')`, `if actual != expected: raise UpdateError(...)`, so a manifest
;;   rejects what it does NOT list; and `plan_source`'s
;;   `missing = set(base['files']) - names → raise UpdateError('Обновление удаляет
;;   старые файлы; нужна отдельная миграция.')`.
;; * https://github.com/Sppqq/nook/blob/main/docs/SECURITY-AUDIT.md —
;;   "Manifests are integrity checks, not independent signatures."
;; * https://github.com/Sppqq/nook/blob/main/tests/test_security.py
;;   `test_unlisted_executable_source_refused` plants `mods/injected.py` and
;;   requires SystemExit — the check is proven by a planted file, not by a
;;   passing run (proven-check-rule).
;;
;; Measured live before writing this, not argued:
;;   (k/tier-of "brain/tier-baseline.edn")            -> :strict
;;   (k/check-write! "brain/tier-baseline.edn" {:justification "x"})
;;                                                    -> {:tier :strict :allowed true}
;; so the reference `bb knowledge tiers` compares the law against is writable
;; through the tool seam with any justification, and writable by a shell `spit`
;; with none (the residual protection-tiers.md has named since 2026-10-20).

(deftest tiers-baseline-audit-names-its-own-reference-test
  (testing "the reference file is reported with its path, tier and digest"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-ref"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n")
          sha (k/sha256-file (str rules))
          baseline (str (fs/path home "brain" "tier-baseline.edn"))
          _ (spit baseline (pr-str {sha "brain/rules.clj"}))
          a (binding [k/*tiers-baseline* baseline]
              (k/tiers-baseline-audit home))]
      (is (= baseline (:path a)))
      (is (= "brain/tier-baseline.edn" (:rel a)))
      (is (= :strict (:tier a))
          "the reference sits in the :strict tier, so it is writable — the point")
      (is (= (k/sha256-file baseline) (:digest a)))
      (is (true? (:exists a)))
      (is (= 1 (:rows a)))
      (is (= [] (:orphans a)))
      (fs/delete-tree (fs/path home))))
  (testing "an absent reference is not-found, never a zero digest or 0 rows read as clean"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-noref"}))
          _ (fs/create-dirs (fs/path home "brain"))
          a (binding [k/*tiers-baseline* (str (fs/path home "brain" "tier-baseline.edn"))]
              (k/tiers-baseline-audit home))]
      (is (false? (:exists a)))
      (is (nil? (:digest a)))
      (is (nil? (:rows a))
          "a missing reference has no row count — nil, not 0")
      (fs/delete-tree (fs/path home))))
  (testing "a baseline row whose path is no longer :forbidden is an ORPHAN, not a silent drop"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-orphan"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          _ (spit (str rules) "(ns rules)\n")
          sha (k/sha256-file (str rules))
          baseline (str (fs/path home "brain" "tier-baseline.edn"))
          _ (spit baseline (pr-str {sha "brain/rules.clj"
                                    (str (apply str (repeat 64 "a"))) "brain/old-law.clj"}))
          a (binding [k/*tiers-baseline* baseline]
              (k/tiers-baseline-audit home))]
      (is (= 2 (:rows a)))
      (is (= 1 (count (:orphans a))))
      (is (= "brain/old-law.clj" (:rel (first (:orphans a)))))
      (is (= :strict (:tier (first (:orphans a))))
          "the orphan's current tier, so the reader can see why it dropped out")
      (fs/delete-tree (fs/path home)))))

(deftest tiers-report-prints-the-reference-and-its-tier-test
  (testing "the report names the reference file, its tier and that it is self-stored"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-rep"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          cfg (fs/path home "config.edn")
          _ (spit (str rules) "(ns rules)\n")
          _ (spit (str cfg) "{}\n")
          sha (k/sha256-file (str rules))
          sha2 (k/sha256-file (str cfg))
          baseline (str (fs/path home "brain" "tier-baseline.edn"))
          _ (spit baseline (pr-str {sha "brain/rules.clj" sha2 "config.edn"}))
          out (binding [k/*tiers-baseline* baseline]
                (with-out-str (is (= 0 (k/-main ["tiers" home])))))]
      (is (str/includes? out "PRISTINE"))
      (is (str/includes? out "reference:")
          "the report names the file it compared against")
      (is (str/includes? out "tier-baseline.edn"))
      (is (str/includes? out ":strict")
          "and that file's tier, so a reader sees it is writable")
      (is (str/includes? out "not independent signatures")
          "integrity vs authentication, stated rather than implied")
      (is (str/includes? out "rows=2")
          "the reference's own row count, so a truncated file is visible")
      (fs/delete-tree (fs/path home))))
  (testing "a missing reference prints its own line rather than looking like a clean run"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-rep2"}))
          _ (fs/create-dirs (fs/path home "brain"))
          out (binding [k/*tiers-baseline* (str (fs/path home "brain" "tier-baseline.edn"))]
                (with-out-str (k/-main ["tiers" home])))]
      (is (str/includes? out "MISSING-REFERENCE")
          "no digest was compared, so PRISTINE would be a guess")
      (is (str/includes? out "UNBASELINED"))
      (fs/delete-tree (fs/path home))))
  (testing "an orphan row is printed beside the verdicts"
    (let [home (str (fs/create-temp-dir {:prefix "tiers-rep3"}))
          _ (fs/create-dirs (fs/path home "brain"))
          rules (fs/path home "brain/rules.clj")
          cfg (fs/path home "config.edn")
          _ (spit (str rules) "(ns rules)\n")
          _ (spit (str cfg) "{}\n")
          sha (k/sha256-file (str rules))
          sha2 (k/sha256-file (str cfg))
          baseline (str (fs/path home "brain" "tier-baseline.edn"))
          _ (spit baseline (pr-str {sha "brain/rules.clj" sha2 "config.edn"
                                    (str (apply str (repeat 64 "b"))) "brain/old-law.clj"}))
          out (binding [k/*tiers-baseline* baseline]
                (with-out-str (k/-main ["tiers" home])))]
      (is (str/includes? out "ORPHAN"))
      (is (str/includes? out "brain/old-law.clj"))
      (is (str/includes? out "rows=3"))
      (fs/delete-tree (fs/path home)))))

(deftest tiers-report-one-arity-is-unchanged-test
  (testing "tiers-report with rows only still renders every verdict — no regression"
    (let [rows [{:rel "brain/rules.clj" :verdict :pristine
                 :expected "aaaa" :current "aaaaaaaaaaaa1234"}
                {:rel "config.edn" :verdict :tampered
                 :expected "bbbbbbbbbbbb" :current "cccccccccccc"}
                {:rel "gone.clj" :verdict :missing :expected "dddd" :current nil}
                {:rel "new.clj" :verdict :unbaselined :expected nil
                 :current "eeeeeeeeeeee"}]
          out (k/tiers-report rows)]
      (is (str/includes? out "PRISTINE"))
      (is (str/includes? out "TAMPERED"))
      (is (str/includes? out "MISSING"))
      (is (str/includes? out "UNBASELINED"))
      (is (str/includes? out "not checked")))))
