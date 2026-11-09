(ns bb-agent.coordinates-test
  "Red first: nothing in the repo checked whether the coordinates brain/*.md
   assert actually resolve, so coordinate-discipline.md was level 1 ('we said
   we'd do it') and never level 4 ('ran it'). Stolen from assafkip/claudemy —
   'a checker refuses any lesson that cites a page the research didn't
   actually read.' We take the checker and REFUSE the refusal: a dangling
   coordinate is reported, never blocked, because our own honest receipts cite
   /tmp paths (the 2026-10-12 preflight proof did) and a guard that rejects
   truthful provenance is worse than a report that names it."
  (:require [babashka.fs :as fs]
            [bb-agent.coordinates :as c]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *root* nil)
(def ^:dynamic *src* nil)

(defn- tiny-repo
  "A fake repo: one src file of 10 lines, two markdown pages citing it."
  [f]
  (let [r (str (fs/create-temp-dir {:prefix "coords-test"}))]
    (fs/create-dirs (str r "/src/bb_agent"))
    (fs/create-dirs (str r "/brain/knowledge"))
    (spit (str r "/src/bb_agent/thing.clj")
          (str/join "\n" (for [i (range 1 11)] (str ";; line " i))))
    (spit (str r "/brain/knowledge/good.md")
          "# Good\n- at: src/bb_agent/thing.clj:4\n")
    (spit (str r "/brain/knowledge/bad.md")
          "# Bad\n- at: src/bb_agent/thing.clj:99\n- at: src/bb_agent/gone.clj:1\n")
    (spit (str r "/brain/knowledge/old.md.bak-20261014")
          "# Snapshot\n- at: src/bb_agent/gone.clj:1\n")
    (binding [*root* (str r "/brain") *src* (str r "/src")]
      (f))
    (fs/delete-tree r)))

(use-fixtures :each tiny-repo)

(deftest coords-in-extracts-file-line-spans-test
  (testing "reads file:line and file:line-line, ignores bare prose ratios"
    (let [found (c/coords-in "see src/a/b.clj:12 and c.md:3-9, but not ratio 4:5")]
      (is (= ["src/a/b.clj" "c.md"] (mapv :path found)))
      (is (= [12 3] (mapv :line found)))
      (is (= [nil 9] (mapv :end found))))))

(deftest audit-classifies-every-citation-test
  (let [r (c/audit *root* [*src*])]
    (testing "three citations: one resolved, one dangling file, one line past EOF"
      (is (= 3 (:total r)))
      (is (= 1 (count (:resolved r))))
      (is (= ["gone.clj"] (mapv :base (:dangling r))))
      (is (= ["thing.clj"] (mapv :base (:out-of-range r))))
      (is (= [99] (mapv :line (:out-of-range r))))
      (is (= [10] (mapv :lines (:out-of-range r)))))
    (testing "a dangling row carries the citing page, so a human can fix it"
      (let [d (first (:dangling r))]
        (is (str/ends-with? (:source d) "bad.md"))
        (is (= 1 (:line d)))))
    (testing ".bak snapshots are not audited — yesterday's copy is not a claim"
      (is (= 3 (:total r)) "the snapshot's dangling cite would make this 4"))))

(deftest audit-of-a-clean-corpus-is-a-quiet-zero-test
  (fs/delete (str *root* "/knowledge/bad.md"))
  (let [r (c/audit *root* [*src*])]
    (is (empty? (:dangling r)))
    (is (empty? (:out-of-range r)))
    (is (= 1 (:total r)))))

(deftest coords-cli-is-report-only-test
  (testing "exit 0 even with a dangling coordinate — a report, never a block"
    (let [out (with-out-str (is (= 0 (c/-main ["coords" *root* "--index" *src*]))))]
      (is (str/includes? out "coordinates: 3 asserted"))
      (is (str/includes? out "dangling: 1"))
      (is (str/includes? out "gone.clj"))
      (is (str/includes? out "out of range: 1"))
      (is (str/includes? out "not checked") "the negative scope is stated")))
  (testing "a missing path exits 2"
    (is (= 2 (c/-main ["coords" "/tmp/definitely-not-here-xyz"]))))
  (testing "an unknown subcommand prints usage and exits 2 — a bare call does not"
    ;; SUPERSEDED 2026-10-14: this used to assert `(= 2 (c/-main []))`. Once
    ;; bare invocation defaulted to "audit the default brain", that failed
    ;; 2 vs 0 — caught by the cross-suite run, not by the isolated one, since
    ;; the isolated run predates the default. A tool that demands its own
    ;; name as the first argument is a tool nobody runs, so the contract is:
    ;; bare = audit, unknown = usage.
    (let [out (with-out-str (is (= 2 (c/-main ["frobnicate"]))))]
      (is (str/includes? out "usage:")))))

;; ---------- 2026-10-18: a cross-repo coordinate was invisible, and an
;; invisible coordinate reads as a green audit. Measured before the fix:
;; brain/**.md cites `.py` 13 times (src/nodes/metrics.py:58 x6,
;; src/nodes/ranking.py:113 x5, plus bare-basename variants) and `bb coords`
;; saw none of them, because coord-re's extension whitelist stopped at our
;; own languages. Yesterday's certainty-ladder rule (a steal must clear
;; level 2: file:line in code the SOURCE ships) guarantees more of these, so
;; the blind spot is load-bearing rather than cosmetic.
;;
;; The fix refuses to call them dangling: another repo's .py will never
;; resolve in a Clojure/markdown tree, so reporting it DANGLING would be the
;; same mistake as blocking a send on a truthful /tmp receipt. It is
;; EXTERNAL — counted, named, and explicitly unverified. Not found is not
;; zero, and not-checkable-here is not broken.

(deftest external-coordinate-is-counted-not-dangling-test
  (spit (str *root* "/knowledge/foreign.md")
        "# Foreign\n- at: src/nodes/metrics.py:58\n- at: src/index.ts:3\n")
  (let [r (c/audit *root* [*src*])]
    (testing "a .py and a .ts citation are external: this corpus has neither"
      (is (= 2 (count (:external r))))
      (is (= ["index.ts" "metrics.py"] (sort (mapv :base (:external r)))))
      (is (contains? c/corpus-extensions "clj")
          "the whitelist that decides external-vs-dangling is data, not prose")
      (is (not (contains? c/corpus-extensions "py")))
      (is (not (contains? c/corpus-extensions "ts"))))
    (testing "the pre-existing dangling .clj is STILL dangling, not external"
      (is (= ["gone.clj"] (mapv :base (:dangling r))))
      (is (= 1 (count (:dangling r)))))
    (testing "total counts them: 3 original + 2 external"
      (is (= 5 (:total r))))))

(deftest external-and-dangling-and-resolved-partition-the-audit-test
  (spit (str *root* "/knowledge/foreign.md")
        "# Foreign\n- at: src/nodes/metrics.py:58\n- at: /tmp/leaky_demo.md:3\n")
  (let [r (c/audit *root* [*src*])]
    (testing "a .md that does not exist is dangling — md IS a corpus extension"
      (is (= ["gone.clj" "leaky_demo.md"] (sort (mapv :base (:dangling r))))))
    (testing "the four buckets partition every assertion, so none is double-counted"
      (is (= (:total r)
             (+ (count (:resolved r)) (count (:dangling r))
                (count (:external r)) (count (:out-of-range r))))))
    (testing "an external row is never also a resolved row"
      (is (empty? (filter :external (:resolved r)))))))

(deftest coords-cli-names-external-and-its-negative-scope-test
  (spit (str *root* "/knowledge/foreign.md")
        "# Foreign\n- at: src/nodes/metrics.py:58\n")
  (let [out (with-out-str (is (= 0 (c/-main ["coords" *root* "--index" *src*]))))]
    (testing "the report counts externals beside the rest"
      (is (str/includes? out "coordinates: 4 asserted"))
      (is (str/includes? out "external: 1"))
      (is (str/includes? out "metrics.py")))
    (testing "and says plainly that they were not verified"
      (is (str/includes? out "unverifiable here"))
      (is (str/includes? out "not checked")))))

;; ---------- 2026-10-18, second fix today: an ABSOLUTE coordinate was
;; basename-resolved, so the audit read the wrong file and asserted about it.
;; Reproduced live, not hypothesised: brain/improvements.md cites
;; /Users/moe/theseus/goals/make-rsi-do-this-983334/act.sh:24 — the real file
;; has 93 lines and line 24 IS the `"$BB" test:bb-agent` gate — yet `bb coords`
;; printed `OUT OF RANGE ... (file has 22 lines)`. Cause: resolve-one's suffix
;; rule matches on "/" + cited, which an absolute path can never satisfy, so it
;; fell through to the basename bucket and took the first of 40+ `act.sh` files
;; under $THESEUS_HOME. A measurement that reads the wrong file does not merely
;; miss something — it makes a claim. Same mistake class as the false NEVER of
;; 10-14 and the false-clean ratio of 10-17, third occurrence.

(deftest absolute-coordinate-is-read-where-it-points-test
  (fs/delete (str *root* "/knowledge/bad.md"))
  (let [outside (str (fs/create-temp-dir {:prefix "coords-outside"}))]
    (fs/create-dirs (str outside "/goals/g"))
    (spit (str outside "/goals/g/act.sh")
          (str/join "\n" (for [i (range 1 31)] (str "# gate " i))))
    ;; same basename, inside the index, and short — the trap
    (spit (str *src* "/act.sh") "# tiny probe\n# two lines\n")
    (spit (str *root* "/knowledge/abs.md")
          (str "# Abs\n- at: " outside "/goals/g/act.sh:24\n"))
    (try
      (let [r (c/audit *root* [*src*])
            row (first (filter #(= "act.sh" (:base %))
                               (concat (:resolved r) (:out-of-range r))))]
        (testing "the absolute target is the file that gets read"
          (is (= (str outside "/goals/g/act.sh") (:resolved row))
              "basename matching read the 2-line probe fixture instead")
          (is (= 30 (:lines row)) "line count comes from the cited file"))
        (testing "line 24 of a 30-line file is in range, so no false verdict"
          (is (empty? (:out-of-range r))))
        (testing "both resolve now: the absolute one directly, the relative one via the index"
          ;; CORRECTED 2026-10-18, same turn: this assertion first read
          ;; `(= ["thing.clj"] (mapv :base (:resolved r)))`, which encoded the
          ;; PRE-fix world — where the absolute cite was basename-resolved to
          ;; the trap file and reported out of range, so only thing.clj could
          ;; be in :resolved. After the fix both rows resolve, and keeping the
          ;; old shape would fail for the right reason and invite "fixing" it
          ;; back into the bug. Corrected in place with the reason attached,
          ;; per mechanical-confidence-supersede, not silently rewritten.
          (is (= ["act.sh" "thing.clj"] (sort (mapv :base (:resolved r)))))
          (is (= 2 (:total r)))
          (is (= 10 (:lines (first (filter #(= "thing.clj" (:base %)) (:resolved r)))))
              "the relative cite still resolved to the 10-line indexed file")))
      (finally (fs/delete-tree outside)))))

(deftest absolute-coordinate-that-does-not-exist-is-still-dangling-test
  (spit (str *root* "/knowledge/gone-abs.md")
        "# Gone\n- at: /tmp/definitely-not-here-xyz.md:3\n")
  (let [r (c/audit *root* [*src*])]
    (testing "reading it where it points still reports absence as absence"
      (is (some #(= "definitely-not-here-xyz.md" (:base %)) (:dangling r)))
      (is (empty? (filter #(= "definitely-not-here-xyz.md" (:base %)) (:resolved r)))))))

;; ---------- 2026-10-18, third finding of the day: a BARE basename with many
;; candidates resolved to whichever file sorted first, and the verdict printed
;; with the same confidence as an exact one. Reproduced live: three OUT OF
;; RANGE rows reading `act.sh:24  (file has 22 lines)`, produced by this
;; scout's own prose ("both `act.sh:24` rows now resolve...") — the regex read
;; a mention as a citation, then basename-matched it against 33 act.sh files
;; under $THESEUS_HOME and measured the first. Two things follow: prose that
;; mentions a coordinate IS a coordinate (a property of any grep-shaped proxy,
;; named on 10-13 for page-fires and 10-14 for leaky_demo — no exclusion
;; machinery for a corpus of 27), and a guessed resolution must say it guessed.

(deftest ambiguous-basename-says-it-guessed-test
  (fs/delete (str *root* "/knowledge/bad.md"))
  (fs/create-dirs (str *src* "/other"))
  (spit (str *src* "/other/thing.clj")
        (str/join "\n" (for [i (range 1 41)] (str ";; other " i))))
  (spit (str *root* "/knowledge/amb.md")
        "# Amb\n- bare: thing.clj:30\n- specific: src/bb_agent/thing.clj:4\n")
  (let [r (c/audit *root* [*src*])
        rows (concat (:resolved r) (:out-of-range r) (:dangling r))
        bare (first (filter #(and (= "thing.clj" (:base %)) (= 30 (:line %))) rows))
        specific (first (filter #(and (= "thing.clj" (:base %)) (= 4 (:line %))) rows))]
    (testing "two files share the basename and no suffix matched, so the row admits the guess"
      (is (= 2 (:ambiguous bare)) "the row names how many files it chose between"))
    (testing "a cite specific enough to match one file is not flagged"
      (is (nil? (:ambiguous specific)))
      (is (= 10 (:lines specific)) "resolved to src/bb_agent/thing.clj via the suffix rule"))
    (testing "ambiguity annotates a row; it is not a fifth bucket"
      (is (= (:total r)
             (+ (count (:resolved r)) (count (:dangling r))
                (count (:external r)) (count (:out-of-range r))))))
    (testing "the CLI prints the guess beside the verdict, not instead of it"
      (let [out (with-out-str (c/-main ["coords" *root* "--index" *src*]))]
        (is (str/includes? out "basename matched 2 files"))
        (is (str/includes? out "thing.clj:30"))))))

(deftest absolute-cite-is-never-ambiguous-test
  (let [outside (str (fs/create-temp-dir {:prefix "coords-abs2"}))]
    (spit (str outside "/thing.clj") ";; one\n;; two\n;; three\n")
    (spit (str *root* "/knowledge/abs2.md")
          (str "# Abs2\n- at: " outside "/thing.clj:2\n"))
    (try
      (let [r (c/audit *root* [*src*])
            row (first (filter #(= 2 (:line %)) (:resolved r)))]
        (testing "an absolute path names exactly one file, so it cannot be a guess"
          (is (nil? (:ambiguous row)))
          (is (= (str outside "/thing.clj") (:resolved row)))
          (is (= 3 (:lines row)) "the same basename inside the index does not disturb it")))
      (finally (fs/delete-tree outside)))))

;; ---------- 2026-10-19: a zero that is not a clean bill ----------
;; bug-ops/zeph .github/scripts/check_integrity_audit.sh treats zero matches as
;; FATAL — "the pattern is broken (pattern rot)" — and its own history says why:
;; the proposed pattern missed crates/zeph-core/src/session_resume.rs (it calls
;; ReplayEngine::fold, not ::replay) and over-matched 40+ files in a different
;; trust domain. A scan that can go quiet is a scan that can only ever look
;; green. `bb coords` printing "0 asserted | 0 dangling" and exiting 0 is that
;; shape, and it would be the fourth false-clean in this lane (10-14 false NEVER
;; on an unread ledger, 10-17 a clean ratio that was only clean inside an
;; extension whitelist, 10-18 a confident OUT OF RANGE measured against the wrong
;; same-named file).

(deftest zero-asserted-is-unverified-not-clean-test
  (testing "a corpus with no coordinates says UNVERIFIED rather than reporting clean"
    (fs/delete (str *root* "/knowledge/good.md"))
    (fs/delete (str *root* "/knowledge/bad.md"))
    (let [out (with-out-str (is (= 0 (c/-main ["coords" *root* "--index" *src*]))
                              "exit stays 0: this is a hand-run report, not a CI gate"))]
      (is (str/includes? out "0 asserted"))
      (is (str/includes? out "UNVERIFIED")
          "zero asserted must be named as an unverified scan, never read as a clean bill")
      (is (str/includes? out "coord-re")
          "and it must name the thing that could have rotted")))
  (testing "coord-re still matches the shapes the corpus actually cites"
    ;; The pattern-rot self-test zeph mechanises in CI, kept here as data: if the
    ;; regex stops matching, every assertion above goes quiet and green.
    (doseq [s ["src/bb_agent/preflight.clj:178"
               "brain/improvements.md:544"
               "scripts/goal_watch.bb:94"
               "config/outcomes.yaml:12"
               "/Users/moe/theseus/goals/x/act.sh:24"]]
      (is (= 1 (count (c/coords-in s))) (str "coord-re no longer matches " s)))
    (is (= 9 (:end (first (c/coords-in "docs/a.md:3-9")))) "spans still parse")))

(deftest coords-cli-accepts-a-brain-dir-without-its-own-name-test
  ;; The tool's own usage line says `bb coords [brain-dir]`, but -main read the
  ;; first argument as a subcommand, so `bb coords <dir>` printed usage and
  ;; exited 2 — the one invocation a human would type. Found by proven-check, not
  ;; by review: pointing the audit at an empty dir to prove the UNVERIFIED line
  ;; printed, and getting a usage message instead.
  (testing "a first argument that is a real directory is the brain dir"
    (let [out (with-out-str (is (= 0 (c/-main [*root* "--index" *src*]))))]
      (is (str/includes? out "coordinates: 3 asserted"))))
  (testing "and an empty corpus then reports UNVERIFIED rather than clean"
    (fs/delete (str *root* "/knowledge/good.md"))
    (fs/delete (str *root* "/knowledge/bad.md"))
    (let [out (with-out-str (is (= 0 (c/-main [*root* "--index" *src*]))))]
      (is (str/includes? out "0 asserted"))
      (is (str/includes? out "UNVERIFIED"))))
  (testing "the bare call and the explicit subcommand still behave"
    (is (= 2 (c/-main ["nope-not-a-subcommand"])))
    (is (= 2 (c/-main ["coords" "/tmp/definitely-not-here-xyz"])))))

;; ---------- 2026-11-01: the same file counted twice, so every pinned cite
;; looked like a guess ----------
;; honey-stack's honey/health.py:28-30 states what its counter counts and what
;; it is not: "Counters are delivered tool *events*, not unique tool calls or
;; evidence that a guard ran." Naming the unit is what keeps a count honest.
;; Ours did not, and the defect was live, not hypothetical.
;;
;; `index-files` walks every index dir with file-seq. The DEFAULT index dirs are
;; [$THESEUS_HOME, $THESEUS_HOME/theseus] — and the second is INSIDE the first,
;; so every repo file is walked twice and appears twice in the index. Measured
;; 2026-11-01 before the fix: index size 4789, distinct 4360 (429 duplicates);
;; suffix matches for `src/bb_agent/coordinates.clj` -> 2, both the same path;
;; and on the live audit 86 of 115 resolved rows carried `:ambiguous 2` with
;; `distinct :ambiguous values` = (2) — i.e. EVERY pinned relative cite of a
;; repo file was reported as a guess, because the deciding set had two members
;; that were one file.
;;
;; Downstream that is worse than noise: knowledge/page-machines removes
;; `:ambiguous` rows (correctly, per the 2026-10-31 rule that a guess is not a
;; machine), so `bb knowledge guards` printed G = 7/28 machine-backed, named 18
;; pages WORDING-ONLY and 7 GUESSED — and the GUESSED rows were false, since
;; those cites named one file exactly. A measurement that double-counts its
;; subject does not just miss; it asserts. Seventh of the false-clean family in
;; this lane, and the first false-DIRTY: it reported less enforcement than
;; exists.
;;
;; The fix is data, not cleverness: one file is one candidate. Dedupe the index
;; so a path reached twice through nested index dirs counts once. A genuinely
;; ambiguous bare basename (33 act.sh files under $THESEUS_HOME) is STILL
;; ambiguous afterwards — dedupe removes duplicate identities, not distinct ones.

(deftest nested-index-dirs-do-not-double-count-a-file-test
  ;; *src* is inside the temp root, exactly the live default shape
  ;; [$THESEUS_HOME, $THESEUS_HOME/theseus].
  (let [outer (str (fs/parent (fs/path *src*)))
        r (c/audit *root* [outer *src*])
        pinned (first (filter #(= 4 (:line %))
                              (concat (:resolved r) (:out-of-range r))))]
    (testing "the pinned cite of a repo file resolves to ONE candidate, not two"
      (is (some? pinned) "src/bb_agent/thing.clj:4 must still resolve")
      (is (nil? (:ambiguous pinned))
          "a fully-pinned relative cite is not a guess: the two suffix matches were the same file, walked twice through nested index dirs"))
    (testing "and it resolved to the real file, with the real line count"
      (is (= (str *src* "/bb_agent/thing.clj") (:resolved pinned)))
      (is (= 10 (:lines pinned))))
    (testing "a genuinely ambiguous BARE basename is still reported as a guess"
      ;; dedupe removes duplicate identities, not distinct ones: two different
      ;; files sharing a basename must still say how many it chose between.
      (fs/create-dirs (str *src* "/other"))
      (spit (str *src* "/other/thing.clj")
            (str/join "\n" (for [i (range 1 41)] (str ";; other " i))))
      (spit (str *root* "/knowledge/amb2.md") "# Amb2\n- bare: thing.clj:30\n")
      (let [r2 (c/audit *root* [outer *src*])
            bare (first (filter #(= 30 (:line %))
                                (concat (:resolved r2) (:out-of-range r2) (:dangling r2))))]
        (is (= 2 (:ambiguous bare))
            "two distinct files with one basename is a real guess, and must stay flagged"))
      (fs/delete-tree (str *src* "/other"))
      (fs/delete (str *root* "/knowledge/amb2.md")))
    (testing "the four buckets still partition every assertion"
      (is (= (:total r)
             (+ (count (:resolved r)) (count (:dangling r))
                (count (:external r)) (count (:out-of-range r))))))))

(deftest index-dirs-are-deduplicated-test
  ;; The unit of the count is a FILE, not a walk. Named as data so the next
  ;; reader cannot re-introduce the double walk by adding an overlapping dir.
  (let [outer (str (fs/parent (fs/path *src*)))
        r (c/audit *root* [outer *src* outer *src*])]
    (testing "listing the same dir four times changes nothing"
      (is (= 3 (:total r)))
      (is (= 1 (count (:resolved r)))
          "the pinned cite in good.md resolves once")
      (is (nil? (:ambiguous (first (:resolved r))))))))

;; ---------- 2026-11-02: a coordinate nobody can decide is not a broken one ----------
;; Red first: these two deftests reference c/undecidable-cite? and c/claim-cite,
;; which do not exist yet — the suite must fail at analysis before it passes.
;; Source: noamsto/agent-smith internal/analyst/citecheck.go:235-238 (a citation
;; is "demoted" when unverifiable and "rejected" only when provably wrong) and
;; Shambhavagrawal/rotless skills/rotless-skill-modernizer/references/DESIGN.md §3
;; (the coverage safety net: every check rescans the text and reports, with
;; file:line, the references the manifest does not cover).

(deftest undecidable-cite-separates-other-repos-files-test
  (let [r (fs/parent (fs/path *src*))
        pg (str r "/brain/knowledge/upstream.md")]
    (spit pg "# Upstream\n- cited `Memory/contradiction-rules.md:11` (ai-suplex)\n- cited `INSTRUCTIONS.md:96` (RepoQL)\n- cited `/tmp/leaky_demo.md:3` (the 10-12 preflight proof)\n- cited `src/bb_agent/gone.clj:1` (a genuinely missing local file)\n")
    (testing "the predicate is a fact about the string, not a guess about intent"
      (is (true? (c/undecidable-cite? "Memory/contradiction-rules.md")))
      (is (true? (c/undecidable-cite? "INSTRUCTIONS.md")))
      ;; SUPERSEDED 2026-11-04, corrected against the shipped predicate rather
      ;; than left to fail: this asserted that an absolute /tmp receipt is
      ;; UNDECIDABLE. It is not, and the older deftest in this same file pins
      ;; the opposite (absolute-coordinate-that-does-not-exist-is-still-
      ;; dangling-test, 2026-10-18). One predicate, one job: `audit` reads an
      ;; absolute cite where it points, so absence IS a fact — a dead /tmp
      ;; receipt is DANGLING, and coordinate-discipline.md's answer is to EMBED
      ;; the receipt, not to excuse it. Corrected in place with the reason
      ;; attached, per mechanical-confidence-supersede, so the next reader
      ;; cannot "fix" the predicate back into contradicting the 10-18 rule.
      (is (false? (c/undecidable-cite? "/tmp/leaky_demo.md"))
          "an absolute path is decidable — audit reads it where it points")
      (is (false? (c/undecidable-cite? "src/bb_agent/gone.clj")))
      (is (false? (c/undecidable-cite? "src/bb_agent/thing.clj")))
      (is (false? (c/undecidable-cite? "brain/knowledge/good.md"))
          "a lowercase pathed cite stays in the resolve path — it may be ours"))
    (testing "the audit puts them in their own bucket and leaves DANGLING for ours"
      (let [a (c/audit (str r "/brain") [r])]
        (is (= ["src/bb_agent/gone.clj" "/tmp/leaky_demo.md" "src/bb_agent/gone.clj"]
               (mapv :path (:dangling a)))
            "our missing file AND the dead /tmp receipt are DANGLING — see the
             SUPERSEDED note above: absolute paths stay decidable")
        (is (= #{"Memory/contradiction-rules.md" "INSTRUCTIONS.md"}
               (set (map :path (:undecidable a))))
            "another repo's files are UNDECIDABLE, not broken; the /tmp receipt
             is decidable and lands in :dangling")
        (is (= (+ (count (:resolved a)) (count (:dangling a))
                  (count (:external a)) (count (:out-of-range a))
                  (count (:undecidable a)))
               (:total a))
            "the partition still sums — no assertion is counted twice or dropped")))
    (testing "and the report names both readings instead of asserting one"
      (let [out (with-out-str (c/-main [(str r "/brain") "--index" r]))]
        (is (str/includes? out "undecidable"))
        (is (str/includes? out "another repo's file")
            "says which of the two readings applies")
        (is (str/includes? out "a cite to another repository's file is a URL")
            "names the convention the row violates, with file:line — rotless's coverage net")
        (is (not (str/includes? out "DANGLING    brain/knowledge/upstream.md -> INSTRUCTIONS.md"))
            "the old false verdict is gone"))))

(deftest claim-cite-reads-the-symbol-beside-the-coordinate-test
  (testing "the house shape `path:LINE` — `symbol` is data, extractable without a file read"
    (let [claims (c/claim-cite "- landed at `src/bb_agent/knowledge.clj:516` — `guard-coverage`, verified")]
      (is (= 1 (count claims)))
      (is (= "src/bb_agent/knowledge.clj" (:path (first claims))))
      (is (= 516 (:line (first claims))))
      (is (= "guard-coverage" (:sym (first claims))))))
  (testing "an em dash and a plain hyphen both count, because the corpus uses both"
    (is (= 1 (count (c/claim-cite "`a.clj:3` — `foo`"))))
    (is (= 1 (count (c/claim-cite "`a.clj:3` - `foo`")))))
  (testing "a coordinate with no claim beside it is not invented — not-found is not zero"
    (is (= [] (c/claim-cite "see `src/bb_agent/thing.clj:4` for the rule")))
    (is (= [] (c/claim-cite "no coordinates here at all"))))
  (testing "and a claim about a line that does not hold it is reported, never blocked"
    (let [r (fs/parent (fs/path *src*))
          pg (str r "/brain/knowledge/claims.md")]
      (spit pg "# Claims\n- `src/bb_agent/thing.clj:4` — `no-such-symbol-anywhere`\n- `src/bb_agent/thing.clj:5` — `line 5`\n")
      (let [bad (c/unsupported-claims (str r "/brain") [r])]
        (is (= ["no-such-symbol-anywhere"] (mapv :sym bad))
            "the line exists but does not hold the symbol — that is the finding")
        (is (= 4 (:line (first bad))) "the cited line, not the one beside it")
        (is (str/includes? (str (first bad)) "src/bb_agent/thing.clj"))
        (let [out (with-out-str (c/-main [(str r "/brain") "--index" r]))]
          ;; SUPERSEDED 2026-11-05 (append-and-supersede, reason kept): this
          ;; assertion pinned the pre-kind label. `claims-print!` now separates
          ;; DRIFTED from UNSUPPORTED, because 29 of the live corpus's 31 rows
          ;; were stale line numbers rather than false claims and one label over
          ;; two kinds made a fixable report read as a broken corpus.
          (is (str/includes? out "UNSUPPORTED"))
          (is (str/includes? out "no-such-symbol-anywhere"))
          (is (= 0 (c/-main [(str r "/brain") "--index" r]))
              "report-only: exit 0, the same refusal claudemy's fail-closed variant got on 10-14")))))))

;; ---------- 2026-11-05: 31 claim findings, and no way to tell drift from a lie ----------
;; `bb coords` on the live corpus printed 31 `CLAIM NOT ON THAT LINE` rows and
;; the 2026-11-04 entry filed them as "a question list, not a verdict" because
;; nothing separated the two kinds. Measured this turn: nearly every one is a
;; DRIFTED line number — the cited symbol is in the cited file, on another line
;; (`src/bb_agent/knowledge.clj:27` cited for `page-status` points at a blank
;; line while the defn moved). A drifted number is one edit from fixed; a symbol
;; that appears nowhere in the file is an unsupported claim. Reporting both with
;; one label made the count unactionable, which is the armory shape
;; (PASS over unexamined data) pointed at our own corpus.

(deftest claim-rows-separate-a-drifted-line-from-an-unsupported-claim-test
  (let [r (fs/create-temp-dir)
        _ (fs/create-dirs (fs/path r "src"))
        _ (spit (str (fs/path r "src" "thing.clj"))
                (str/join "\n" ["(ns thing)" "" "" "(defn page-status" "  [p] p)" ""]))]
    (fs/create-dirs (fs/path r "brain"))
    (spit (str (fs/path r "brain" "p.md"))
          (str "# p\n\n"
               "- `src/thing.clj:2` — `page-status` drifted: the symbol is on line 4.\n"
               "- `src/thing.clj:2` — `no-such-symbol` is nowhere in the file.\n"))
    (let [rows (c/unsupported-claims (str (fs/path r "brain")) [r])
          by-sym (into {} (map (juxt :sym identity) rows))]
      (is (= 2 (count rows)) "both cited lines lack their symbol, so both are rows")
      (is (= [4] (:elsewhere (get by-sym "page-status")))
          "a drifted cite names the line the symbol IS on, 1-based")
      (is (nil? (:elsewhere (get by-sym "no-such-symbol")))
          (str "and a symbol that is nowhere in the file carries no :elsewhere — "
               "not-found stays not-found, never an empty list read as zero"))
      (is (= :drifted (:kind (get by-sym "page-status")))
          "the kind is data, so a caller can filter without re-reading prose")
      (is (= :unsupported (:kind (get by-sym "no-such-symbol")))))))

(deftest claim-report-labels-the-two-kinds-differently-test
  (let [r (fs/create-temp-dir)
        _ (fs/create-dirs (fs/path r "src"))
        _ (spit (str (fs/path r "src" "thing.clj"))
                (str/join "\n" ["(ns thing)" "" "(defn page-status" "  [p] p)" ""]))]
    (fs/create-dirs (fs/path r "brain"))
    (spit (str (fs/path r "brain" "p.md"))
          (str "# p\n\n"
               "- `src/thing.clj:2` — `page-status` drifted.\n"
               "- `src/thing.clj:2` — `no-such-symbol` unsupported.\n"))
    (let [out (with-out-str
                (c/claims-print! (c/unsupported-claims (str (fs/path r "brain")) [r]))) ]
      (is (str/includes? out "LINE DRIFTED")
          "drift gets its own label, because its fix is re-pinning a number")
      (is (str/includes? out "the symbol IS in that file at line 3")
          "and names where it went, so the fix is one edit")
      (is (str/includes? out "UNSUPPORTED")
          "a symbol nowhere in the file is a different finding")
      (is (str/includes? out "appears nowhere in that file")
          "stated, not left to the reader")
      (is (str/includes? out "1 drifted, 1 unsupported")
          "the two counts are printed separately, so one number cannot hide the other"))))
