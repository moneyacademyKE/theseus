(ns bb-agent.knowledge
  "Six RSI-scout recommendations as one small namespace:
   staleness marks, a wins ledger, protection tiers, pre-write
   snapshots, a curator dry-run, and a no-regression promotion gate.
   Plain files, data in / data out. Nothing here mutates anything
   except record-win!, stamp-review! and snapshot! — and the human
   promotion gate stays outside this ns by design."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.coordinates :as coordinates]
            [bb-agent.brain :as brain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(declare snapshot! never-fired unverified-zero guard-coverage-summary guards-report
         routing-rows routing-report)

;; ---------------------------------------------------------------- staleness

(def ^:private review-re
  "The one-line decay mark stolen from aminuoshi-378/dsh-self-improving."
  #"last-reviewed:\s*(\d{4}-\d{2}-\d{2})")

(defn- days-old
  [^LocalDate today ^LocalDate d]
  (.until d today ChronoUnit/DAYS))

(defn page-status
  "Classify one markdown page: :fresh (marked within max-age-days),
   :stale (marked but old) or :unmarked (no last-reviewed line).
   Opts: :today (LocalDate, default now), :max-age-days (default 30)."
  ([path] (page-status path {}))
  ([path {:keys [today max-age-days] :or {today (LocalDate/now) max-age-days 30}}]
   (let [m (re-find review-re (slurp (str path)))
         reviewed (some-> m second LocalDate/parse)
         status (cond
                  (nil? reviewed) :unmarked
                  (> (days-old today reviewed) max-age-days) :stale
                  :else :fresh)]
     {:file (str path) :status status :last-reviewed reviewed})))

(defn stale-pages
  "All problem pages in a dir: :stale and :unmarked ones, sorted by name.
   Fresh pages are absent — a quiet result, not silence."
  ([dir] (stale-pages dir {}))
  ([dir opts]
   (->> (fs/glob (str dir) "*.md")
        (map #(page-status % opts))
        (remove #(= :fresh (:status %)))
        (sort-by :file))))

(defn stamp-review!
  "Set (or replace) the page's last-reviewed line to today. Snapshots
   first. Returns the page path."
  ([path] (stamp-review! path (LocalDate/now)))
  ([path ^LocalDate today]
   (snapshot! path)
   (let [p (str path)
         lines (str/split-lines (slurp p))
         line (str "last-reviewed: " today)
         fresh (if (some #(re-find review-re %) lines)
                 (mapv #(if (re-find review-re %)
                          (str/replace-first % review-re (constantly line))
                          %)
                       lines)
                 (into (vec lines) [" " line]))]
     (spit p (str/join "\n" fresh))
     path)))

;; ------------------------------------------------------------- wins ledger

(defn record-win!
  "Append one block to the wins ledger — but ONLY with evidence.
   A blank evidence string throws; a win without a receipt is a claim,
   and claims don't get recorded. Returns the ledger path."
  [wins-file {:keys [what evidence date] :or {date (str (LocalDate/now))}}]
  (when (str/blank? evidence)
    (throw (ex-info "record-win! refused: no evidence"
                    {:what what :evidence evidence})))
  (let [p (str wins-file)
        block (str "\n## " date " — " what "\n- evidence: " evidence "\n")]
    (spit p block :append true)
    p))

;; --------------------------------------------------------- protection tiers

(def protection-tiers
  "Edit-rights as data, not prose (stolen from ozumpe/omnibase, minus the
   actor org). Matched as path prefixes; first hit wins, so order matters
   only in that :forbidden entries must not be shadowed by :strict."
  {:forbidden ["brain/rules.clj" "config.edn"]
   ;; The files that PRODUCE this lane's verdicts. Named as data so a grader edit
   ;; is classified, snapshotted and reported instead of moving bytes invisibly.
   ;; Source: nmarchand73/ai-factory, docs/EVOLUTION.md#L33-L35 — "A system must
   ;; not tune its own grader." Matched after :forbidden, so the law wins.
   ;; Home-relative, because `guard-write!` relativizes against the home and the
  ;; repo is its `theseus/` subdir. Repo-relative entries read as :unclassified at
  ;; the real seam and every instrument row printed INSTRUMENT MISSING — measured
  ;; 2026-11-12 on the first live run, not assumed. `tier-of` matches either
  ;; spelling, so a caller holding a repo-relative path is not silently wrong.
   :instrument ["theseus/src/bb_agent/coordinates.clj"
                "theseus/src/bb_agent/knowledge.clj"
                "theseus/src/bb_agent/preflight.clj"
                "theseus/src/bb_agent/test_roster.clj"
                "theseus/src/bb_agent/goal_liveness.clj"]
   :strict ["brain/"]
   :soft ["state/"]})

(defn tier-of
  "Classify a repo-relative path: :forbidden, :strict, :soft or :unclassified."
  [path]
  (let [p (str path)
        ;; An :instrument entry is home-relative (`theseus/src/...`) while callers
        ;; often hold the repo-relative form; match either, so neither spelling
        ;; reads as :unclassified by accident.
        with-prefix (str "theseus/" (str/replace-first p "theseus/" ""))]
    (or (some #(when (str/starts-with? p %) :forbidden) (:forbidden protection-tiers))
        ;; :forbidden is matched first and wins, so the law can never be
        ;; downgraded to an instrument by an overlapping prefix.
        (some #(when (or (str/starts-with? p %) (str/starts-with? with-prefix %))
                   :instrument)
              (:instrument protection-tiers))
        (some #(when (str/starts-with? p %) :strict) (:strict protection-tiers))
        (some #(when (str/starts-with? p %) :soft) (:soft protection-tiers))
        :unclassified)))

(defn check-write!
  "The pre-write lookup. :forbidden always throws; :strict throws without
   a non-blank justification; everything else passes. Returns a verdict map."
  [path {:keys [justification]}]
  (let [tier (tier-of path)]
    (case tier
      :forbidden (throw (ex-info "write forbidden by protection tier"
                                 {:path (str path) :tier tier}))
      :strict (if (str/blank? justification)
                (throw (ex-info "strict write requires a written justification"
                                {:path (str path) :tier tier}))
                {:path (str path) :tier tier :allowed true})
      {:path (str path) :tier tier :allowed true})))


;; ------------------------------------------------- forbidden-tier content digests

(defn probe-path
  "What ONE stat established about a path: {:kind :file|:dir|:other|:absent|:unknown
   :detail string?}. Absence and unknown are distinct, and the distinction is the
   whole point — they call for OPPOSITE responses: create it, versus fix the
   permission.

   Source: wilbur-labs/Agora `backend/agora/fsprobe.py` — \"Only FileNotFoundError
   is absence. Every other OSError is unknown.\" Agora hit the conflation three
   times in one night in three modules and put the rule in one file instead of
   rediscovering it a fourth time. We hit it in `bb knowledge tiers`, the report
   that names our own graders, measured live 2026-11-14: one mode-000 instrument
   made the CLI throw `AccessDeniedException` and print NO census at all, not even
   the four readable rows. The predicates we chain do not even agree with each
   other — `fs/exists?` is FALSE for a file under an unreadable directory while
   `fs/regular-file?` is TRUE for a mode-000 file — so a fallback chain of them
   cannot recover a distinction each link has already lost.

   Never raises: a probe is an observation, and \"the observation failed\" is one
   of its outcomes rather than an exception every caller must remember to catch.
   NOFOLLOW_LINKS, so a dangling symlink reads as :other — recorded at the path,
   which is not nothing. NOT taken from Agora: its Environment protocol (run +
   probe as the only two capabilities), the Windows launcher, and the
   symlink-following second stat — one read is enough here."
  [path]
  (try
    (let [a (java.nio.file.Files/readAttributes
             (fs/path (str path)) java.nio.file.attribute.BasicFileAttributes
             (into-array java.nio.file.LinkOption
                         [java.nio.file.LinkOption/NOFOLLOW_LINKS]))]
      {:kind (cond (.isRegularFile a) :file
                   (.isDirectory a) :dir
                   :else :other)})
    (catch java.nio.file.NoSuchFileException _
      {:kind :absent})
    (catch Exception e
      {:kind :unknown
       :detail (str (.getSimpleName (class e))
                    (when-let [m (.getMessage e)] (str ": " m)))})))

(defn sha256-file
  "sha256 of a file's bytes, as 64 hex chars. Nil when the file is not a regular
   file — not-found is not an empty digest (the 2026-10-14 rule) — and ALSO nil
   when it cannot be read: a mode-000 file satisfies both `.exists` and `.isFile`
   and then throws on the read, which propagated out of `bb knowledge tiers` as a
   stack trace printing no census at all (measured 2026-11-14). Nil here keeps
   this function's own promise; `sha256-verbatim` is the form that can say WHY.
   Source of the split: wilbur-labs/Agora `backend/agora/fsprobe.py`."
  [path]
  (let [f (java.io.File. (str path))]
    (when (and (.exists f) (.isFile f))
      (try
        (let [md (java.security.MessageDigest/getInstance "SHA-256")
              bs (java.nio.file.Files/readAllBytes (.toPath f))]
          (.update md bs)
          (apply str (map #(format "%02x" %) (.digest md))))
        (catch Exception _ nil)))))

(defn sha256-verbatim
  "sha256 of a file's bytes as 64 hex chars, or {:kind :absent|:unknown|:dir|:other
   :detail} when there are no bytes to read. `sha256-file` returns nil for BOTH
   absence and unreadability (and used to THROW on a file that stats fine but
   cannot be read), so a caller needing the split cannot get it from nil. This is
   the three-state form, and it is what `instrument-rows` now reports on."
  [path]
  (let [p (probe-path path)]
    (cond
      (= :file (:kind p))
      (try
        (if-let [d (sha256-file (str path))]
          {:kind :file :digest d}
          {:kind :unknown :detail "exists but unreadable: no bytes could be hashed"})
        (catch Exception e
          {:kind :unknown
           :detail (str "exists but unreadable: " (.getSimpleName (class e))
                        (when-let [m (.getMessage e)] (str ": " m)))}))

      :else
      (assoc p :detail (or (:detail p)
                           (str "probe said " (name (:kind p))
                                ", so there are no bytes to hash"))))))


(def ^:dynamic *tiers-baseline*
  "Where the recorded digests live. A var rather than a derived path so a test can
   point it at a fixture and the CLI can point it at the corpus's own file."
  nil)

(defn tiers-baseline-path
  "The baseline lives beside the corpus it stamps, under the home, so it is data
   in the repo rather than state in a temp dir."
  [home]
  (or *tiers-baseline* (str (fs/path home "brain" "tier-baseline.edn"))))

(defn tier-digests
  "Every :forbidden path in `protection-tiers`, digested against a baseline map of
   sha256 -> repo-relative path. One row per baseline entry:
     :pristine  bytes match
     :tampered  bytes differ — expected and current are both in the row
     :missing   no such regular file, so there are no bytes to compare
     :unbaselined  the file exists but nothing recorded its digest, so PRISTINE
                would be a guess; the row carries the digest it just measured

   WHY (measured 2026-11-03, not assumed): nearly every entry in brain/improvements.md
   since 2026-10-15 closes with \"brain/rules.clj untouched (mtime Sep 27 00:13)\".
   An mtime is certainty level 1 — it says the file was not written since a date, and
   it is defeated by a restore that preserves mtime, by `touch`, and by reading the
   wrong file. A sha256 over the bytes is level 4. Source for the mechanism:
   KHAEntertainment/self-updating-skills, `bin/upkeep.sh:333-351` (sha256 over each
   frozen block), `:505` (`TAMPERED ... expected X != current Y`), `:526`
   (`VERDICT: TAMPERED`), and `engine/check.go:44,91,134-140` carrying the verdict to
   an exit code with TAMPERED > STALE precedence.

   Scope, so this is not over-read: this measures CONTENT. It does not prove nobody
   edited the file and restored it, and it says nothing about the :strict or :soft
   tiers, which change constantly by design and would report TAMPERED on every run."
  ([home] (tier-digests home nil))
  ([home baseline]
   (let [bl (or baseline
                (let [f (tiers-baseline-path home)]
                  (when (fs/regular-file? (fs/path f))
                    (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))))
         ;; baseline is sha -> rel path; invert it so a file can ask for its own digest
         by-rel (into {} (map (fn [[sha rel]] [rel sha]) (or bl {})))]
     (mapv (fn [rel]
             (let [abs (str (fs/path home rel))
                   cur (sha256-file abs)
                   exp (get by-rel rel)]
               {:rel rel
                :path abs
                :tier (tier-of rel)
                :expected exp
                :current cur
                :verdict (cond
                           (nil? cur) :missing
                           (nil? exp) :unbaselined
                           (= exp cur) :pristine
                           :else :tampered)}))
           (:forbidden protection-tiers)))))

(defn instrument-rows
  "Every :instrument path — the files that PRODUCE this lane's verdicts — as data:
   rel path, digest, and :present|:missing. A missing instrument is not-found, never
   a zero digest that reads as clean (the 2026-10-14 rule).

   Why these are named at all, measured 2026-11-12 rather than argued: every one of
   them classified :unclassified, so `guard-write!` returned `:snapshot nil` for a
   write to a grader — the bytes a verdict was measured under were gone with no copy
   and no trace. The cost was already paid twice: on 2026-11-09 an edit to
   coordinates.clj moved `bb knowledge guards` from G = 8/29 to G = 7/29 inside the
   same turn that reported both numbers, and on 2026-11-11 the first test-roster
   probe reported five real suites as NEVER-RUN because the instrument itself was
   wrong. Source: nmarchand73/ai-factory,
   https://github.com/nmarchand73/ai-factory/blob/main/docs/EVOLUTION.md#L33-L35 —
   \"Protected (the plan is rejected if it targets them): eval-judge, retrospective,
   factory/bin/*, evals/criteria.json, factory/config.json. A system must not tune
   its own grader.\"

   Scope, deliberately narrower than the source: we NAME and SNAPSHOT instruments, we
   do not block writes to them. ai-factory refuses its own loop from touching a grader
   and then records \"we fixed those by hand\" — a human turn legitimately fixes a
   misjudging grader, so a gate that refused would strand the fix (same call as
   2026-10-12: a guard that rejects truthful provenance is worse than a report that
   names it)."
  [home]
  (mapv (fn [rel]
          (let [abs (str (fs/path home rel))
                ;; Three states, not two: a grader we cannot READ is not a grader
                ;; that is ABSENT, and reporting it as :missing sends the operator
                ;; to create a file that is already sitting there behind a
                ;; permission. Source of the split: wilbur-labs/Agora fsprobe.py.
                r (sha256-verbatim abs)]
            {:rel rel
             :path abs
             :digest (:digest r)
             :detail (:detail r)
             :verdict (case (:kind r)
                        :file :present
                        :absent :missing
                        :unreadable)}))
        (:instrument protection-tiers)))

(defn tiers-baseline-audit
  "The reference audits itself: path, tier, digest, row count, orphans. A
   baseline row whose path is no longer :forbidden is an ORPHAN — reported,
   not silently dropped, so a retired law leaves a visible seam. An absent
   reference is not-found: nil digest, nil rows — never a zero row count
   that reads as clean."
  [home]
  (let [path (tiers-baseline-path home)
        exists (fs/regular-file? (fs/path path))
        entries (when exists
                  (try (edn/read-string (slurp path)) (catch Exception _ nil)))
        orphans (->> (or entries {})
                     (filter (fn [[_ rel]] (not= :forbidden (tier-of rel))))
                     (mapv (fn [[_ rel]] {:rel rel :tier (tier-of rel)})))]
    {:path path
     :rel "brain/tier-baseline.edn"
     :tier (tier-of "brain/tier-baseline.edn")
     :digest (sha256-file path)
     :exists (boolean exists)
     :rows (when exists (count (or entries {})))
     :orphans orphans}))

(defn- tiers-reference-lines
  "The baseline auditing itself, as report lines: the reference file with its
   tier and row count — self-stored, so its digests are not independent
   signatures — or the MISSING-REFERENCE line when absent. Orphan rows print
   beside the verdicts."
  [baseline]
  (when baseline
    (concat
     (if (:exists baseline)
       [(str "reference: " (:path baseline) " (" (:tier baseline) ", rows="
             (:rows baseline) ") — self-stored, so its sha256 digests are not "
             "independent signatures: integrity against the corpus, not "
             "authentication of it")]
       [(str "MISSING-REFERENCE: " (:path baseline) " — no digest was compared, so "
             "PRISTINE would be a guess; every row is UNBASELINED or MISSING by "
             "construction")])
     (map (fn [{:keys [rel tier]}]
            (str "  ORPHAN " rel " (" tier ") — baselined path no longer :forbidden"))
          (:orphans baseline)))))

(defn tiers-report
  "The human receipt. Names the verdict, both digests when they differ, and what
   the digest does not prove. The 2-arity adds the reference block — the
   baseline file auditing itself — without changing the 1-arity output."
  ([rows] (tiers-report rows nil))
  ([rows baseline] (tiers-report rows baseline nil))
  ([rows baseline instruments]
  (str/join
   "\n"
   (concat
    [(str "tiers: " (count rows) " :forbidden path(s) digested")]
    (map (fn [{:keys [rel verdict expected current]}]
           (case verdict
             :pristine (str "  PRISTINE  " rel "  sha256:" (subs (str current) 0 12))
             :tampered (str "  TAMPERED  " rel "  expected " (subs (str expected) 0 12)
                            " != current " (subs (str current) 0 12))
             :missing (str "  MISSING   " rel "  — no such regular file, so there are no "
                           "bytes to compare (not-found, not pristine)")
             :unbaselined (str "  UNBASELINED  " rel "  sha256:" (subs (str current) 0 12)
                               " — nothing recorded this file's digest, so PRISTINE would "
                               "be a guess; record it to start checking")))
         rows)
    (tiers-reference-lines baseline)
    (when instruments
      (concat
       [(str "instruments: " (count instruments) " file(s) that produce the verdicts "
             "this lane reports — a system must not tune its own grader, so these are "
             "named and snapshotted, never blocked (a human turn fixes a misjudging "
             "grader by hand; source nmarchand73/ai-factory docs/EVOLUTION.md#L33-L35)")]
       (map (fn [{:keys [rel verdict digest detail]}]
              (case verdict
                :present (str "  INSTRUMENT  " rel "  sha256:" (subs (str digest) 0 12))
                :missing (str "  INSTRUMENT MISSING  " rel " — no such regular file, so "
                              "verdicts attributed to it are unverifiable (not-found, not "
                              "absent by design)")
                :unreadable (str "  INSTRUMENT UNREADABLE  " rel " — the file is there but "
                                 "this process could not read it (" detail "), so fix the "
                                 "permission; do NOT create or overwrite it, because absent "
                                 "and unreadable call for opposite responses")))
            instruments)))
    [(str "not checked: content only. A matching digest does not prove nobody edited "
          "and restored the file, does not cover the :strict or :soft tiers (which "
          "change by design), and says nothing about what the bytes MEAN — a rules.clj "
          "whose digest is intact can still be wrong. Report only unless a verdict is "
          ":tampered or :missing, which exits 1.")]))))

;; --------------------------------------------------------------- snapshots

(defn snapshot!
  "Timestamped copy beside the original — every automated write is
   reversible without git archaeology (stolen from DrDustinEdwards/capsid).
   Returns the backup path, or nil when the file doesn't exist."
  [path]
  (let [p (str path)]
    (when (fs/exists? p)
      (let [ts (.format (java.time.format.DateTimeFormatter/ofPattern
                         "yyyyMMdd-HHmmss")
                        (java.time.LocalDateTime/now))
            bak (str p ".bak-" ts)]
        (fs/copy p bak {:replace-existing true})
        bak))))



;; ------------------------------------------------ the write seam (2026-10-20)

(defn snapshot-unless-same-second!
  "A pre-write snapshot that keeps the EARLIEST bytes of a second.

   `snapshot!` copies with `:replace-existing true` at second resolution, so two
   writes inside one second would replace the first backup with the state left by
   the first write — the very bytes a pre-write snapshot exists to keep. When the
   target `.bak-<ts>` already exists this returns that path and writes nothing."
  [path]
  (let [ts (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                    (java.time.LocalDateTime/now))
        bak (str path ".bak-" ts)]
    (if (fs/exists? bak)
      bak
      (snapshot! path))))

(defn guard-write!
  "The write seam: classify a path against `protection-tiers`, refuse
   `:forbidden`, and snapshot `:strict` files before the bytes move.

   Stolen from miketako3/dsh-fracture, `src/guard.ts:1-8` — \"The loop edits
   the harness that is running it, so some rows must stay out of reach no matter
   what a diagnosis suggests. These checks run before a candidate is stored,
   probed, or adopted: refusing early keeps a bad row out of the candidate
   directory entirely.\" The placement is the steal: a guard that runs after
   the write is a report, not a guard.

   Why this exists as a seam and not just as `check-write!` — measured
   2026-10-20: `grep -rn \"check-write!|snapshot!\" src/ scripts/` returned
   ZERO production callers. Both functions landed in 2026-09/10-10 and were
   referenced only from tests, so protection-tiers and pre-write-snapshots were
   pages whose code no boundary called. The cost was already paid once: on
   2026-10-14 a `write_file` with `append? true` clobbered two knowledge pages
   and only a manually taken `.bak` saved them.

   Returns a verdict map, never throws — the caller turns `:refused` into
   whatever its own error shape is, so this stays data in / data out:
   `{:path <normalized> :relative <rel|nil> :tier <t> :verdict :allowed|:refused
   :snapshot <path|nil>}`.

   Scope, stated because a guard that over-reaches is as bad as one that
   under-reaches:
   - only paths under `(config/home)` are classified. A path outside the home is
     `:unclassified` and is NOT snapshotted — the filesystem stays open (owner
     directive 2026-09-07 removed confinement); this is a corpus guard, not a jail.
   - `:strict` (brain/) and `:instrument` (the files that produce this lane's
     verdicts) are snapshotted, not `:soft` (state/). state/ is
     machine-written every turn (`seen.edn`, `usage.edn`, poller offsets) and a
     `.bak-<ts>` per write would be litter, not reversibility. brain/ is prose a
     human reads, and one bad append destroyed two pages.
   - the `check-write!` justification gate is deliberately NOT wired here:
     requiring a justification string from `write_file` would need a new tool
     parameter and would break every owner-granted brain edit until a human
     decides that trade. Named, not smuggled in.
   - `..` is normalized before the tier lookup, so `/home/brain/../brain/rules.clj`
     cannot dodge the data. A lexical normalize, not `fs/canonicalize`: canonicalize
     resolves symlinks against the real disk and returns `/private/...` on macOS,
     which would make every prefix comparison against `$THESEUS_HOME` fail open."
  [path]
  (let [home (fs/path (config/home))
        target (fs/normalize (fs/absolutize (fs/path path)))
        inside? (fs/starts-with? target home)
        rel (when inside? (str (fs/relativize home target)))
        tier (if inside? (tier-of rel) :unclassified)
        verdict (if (= tier :forbidden) :refused :allowed)
        ;; :strict and :instrument are both snapshotted. An instrument is not
        ;; blocked (a human turn fixes a misjudging grader by hand) but its
        ;; pre-write bytes are kept, so a verdict measured under the old bytes
        ;; stays attributable to them.
        snapshot (when (and (= verdict :allowed)
                            (contains? #{:strict :instrument} tier)
                            (fs/regular-file? target))
                   (snapshot-unless-same-second! (str target)))]
    {:path (str target)
     :relative rel
     :tier tier
     :verdict verdict
     :snapshot snapshot}))

;; ----------------------------------------------------------------- curator

(defn- page-title
  "First level-1 heading, else nil."
  [path]
  (->> (str/split-lines (slurp (str path)))
       (some #(when-let [m (re-find #"^#\s+(.+)$" %)] (str/trim (second m))))))

(defn curator-candidates
  "Dry-run consolidation report for a knowledge dir — flags, never archives
   (stolen from dkedar7/langstage-hermes, minus the auto-write loop):
   :problems = stale/unmarked pages, :duplicate-titles = pages sharing a
   level-1 heading. With :ledger-paths also :never-fired (verified zeros only)
   and :unverified (zeros that could not be measured). Returns data; what to
   archive stays a human decision."
  ([dir] (curator-candidates dir {}))
  ([dir opts]
   (let [problems (stale-pages dir opts)
         titles (->> (fs/glob (str dir) "*.md")
                     (keep (fn [f]
                             (when-let [t (page-title f)]
                               {:title t :file (str f)})))
                     (group-by :title)
                     (filter #(> (count (val %)) 1))
                     (map (fn [[t fs]] {:title t :files (mapv :file fs)})))]
     (cond-> {:problems problems :duplicate-titles (vec titles)}
       (seq (:ledger-paths opts))
       (-> (assoc :never-fired (never-fired dir (:ledger-paths opts)))
           (assoc :unverified (unverified-zero dir (:ledger-paths opts))))))))

;; ---------------------------------------------------------- promotion gate

(defn promote-gate
  "No-regression promotion (half-stolen from SourceShift/mini-ork): every
   check is a named zero-arg fn returning {:ok bool :detail str}. Verdict
   is :promote only when ALL pass — new check green AND previously-passing
   checks still green. This fn records and returns the measurement;
   it never promotes anything itself. The human gate stays outside."
  [checks]
  (let [results (into (sorted-map)
                      (map (fn [[name f]]
                             [name (try (f)
                                        (catch Throwable e
                                          {:ok false :detail (str "threw: " (ex-message e))}))])
                           checks))
        verdict (if (every? :ok (vals results)) :promote :block)]
    {:verdict verdict :results results}))

;; ------------------------------------------------------------------- prune
;; Antarsoft/morphness: "the ones that caught problems stay, and the ones that
;; never fire get questioned." A rule earns its place in always-loaded context
;; by having FIRED, not by having been written.
;;
;; This is the measurement page-staleness only half-stole. `last-reviewed:` is a
;; claim the page makes about itself — mechanical confidence level 1 ("said
;; so"). A fire count derived from the ledgers is level 4 ("ran it"): the
;; ledger either cites the page or it does not. Nothing here mutates a page;
;; a prune questions, the human decides.

(defn- slug-of
  "Page slug: the file name without its .md."
  [path]
  (-> (str path) fs/file-name (str/replace #"\.md$" "")))

(defn- slug-re
  "Match the slug as a token, optionally with its .md, never as a fragment of
   a longer word — `old` must not fire on `bold`, nor `preflight` on
   `preflight-x`."
  [slug]
  (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote slug)
                   "(?:\\.md)?(?![\\w-])")))

(defn- heading?
  "A ledger block heading — the only line that can reset the citation's date."
  [line]
  (str/starts-with? (str/trim line) "##"))

(defn- heading-date
  "The date in a `## ` ledger heading, or nil when the heading carries none.
   Reset per heading — a citation under an undated heading has no date to
   claim, and inheriting the previous heading's date would manufacture one."
  [line]
  (when (heading? line)
    (re-find #"\d{4}-\d{2}-\d{2}" line)))

(defn- ledger-mentions
  "Walk one ledger: {:cites int :dates [str] :status :read|:missing}.
   A missing ledger is NOT a zero — it is not-found (aden-hive/hive: in its own
   demo the queen clears a bad worker row so the chart shows 'Cal.com as not
   found rather than zero'). Still no exception: a report must not die because
   a ledger was rotated."
  [slug path]
  (if-not (fs/regular-file? (fs/path (str path)))
    {:cites 0 :dates [] :status :missing}
    (loop [lines (str/split-lines (slurp (str path)))
           date nil cites 0 dates []]
      (if (empty? lines)
        {:cites cites :dates dates :status :read}
        (let [l (first lines)
              d (if (heading? l) (heading-date l) date)]
          (if (re-find (slug-re slug) l)
            (recur (rest lines) d (inc cites) (if d (conj dates d) dates))
            (recur (rest lines) d cites dates)))))))

(defn page-fires
  "How many times the ledgers cite one page, and the latest dated heading a
   citation sat under. {:slug str :fires int :last-fired str|nil}

   Caveat, measured 2026-10-13: this counts CITATIONS, not substantive use —
   writing *about* a page in the ledger fires it (a curator's meta-report moved
   one page 1 -> 4 and the never-fired count 5 -> 0). No machinery is added to
   special-case that; a never-fired page is a question, and a fired page is not
   thereby load-bearing. The human decides."
  [path ledger-paths]
  (let [slug (slug-of path)
        ms (mapv #(ledger-mentions slug %) ledger-paths)
        read-n (count (filterv #(= :read (:status %)) ms))
        fires (reduce + 0 (map :cites ms))]
    ;; The event unit (phense/agentic-rag, docs/pattern-synthesis.md): "at least
     ;; three independent user events", where "two quotes count as the same event
     ;; when they come from the same source". Our gate's observation is a dated
     ;; ledger entry, so six cites on one day is ONE observation, not six —
     ;; otherwise one writer in one turn satisfies a gate that exists to demand
     ;; independent recurrence. :fires stays line-shaped for no-regression.
     {:slug slug
     :file (str path)
     :fires fires
     :events (count (distinct (mapcat :dates ms)))
     :undated (reduce + 0 (map #(- (:cites %) (count (:dates %))) ms))
     :last-fired (->> (mapcat :dates ms) sort last)
     :ledgers-read read-n
     :ledgers-missing (- (count ms) read-n)
     ;; A zero is only a measurement when something was actually read: with no
     ;; ledger read, "never cited" and "never looked" are the same number.
     :verified (or (pos? fires) (and (pos? read-n) (= read-n (count ms))))}))

(defn fire-counts
  "Every page in a dir, measured against the ledgers. Sorted by events
   ascending, then fires, then slug — the never-fired rise to the top, which is
   the point of the report, and a page cited 20 times in one entry no longer
   outranks one cited twice on two separate days."
  [dir ledger-paths]
  (->> (fs/glob (str dir) "*.md")
       (map #(page-fires % ledger-paths))
       (sort-by (juxt :events :fires :slug))))

(defn never-fired
  "The pages the ledgers have never cited, measured: the prune's question
   list. A page qualifies only when its zero is :verified — every ledger was
   actually read. Data, not deletion; what happens to a page stays a human
   call. Not-found is not zero, so an unread ledger yields no questions rather
   than a corpus of false corpses (those go to unverified-zero).

   Counts the LINE unit, not :events, and that is deliberate: an undated
   citation is still a citation, so filtering on :events would report ten live
   pages as never fired (measured 2026-11-01, before `dateless` existed). A
   zero here is a measured zero; a zero we cannot date goes to `dateless`."
  [dir ledger-paths]
  (->> (fire-counts dir ledger-paths)
       (filterv #(and (zero? (:fires %)) (:verified %)))))

(defn dateless
  "Pages the ledgers cite, but only under headings that carry no date — so the
   event unit (how many independent observations, on which days) is not
   computable for them. Neither 'never fired' nor 'fired N times': the date
   dimension is not-found, and not-found is not zero (aden-hive/hive).
   Cause, measured 2026-11-01: propose blocks are filed under `## owner/repo`
   headings with no date, so every citation inside them is undated by
   construction — 10 of 28 pages sit in this bucket."
  [dir ledger-paths]
  (->> (fire-counts dir ledger-paths)
       (filterv #(and (pos? (:fires %)) (zero? (:events %))))))

(defn unverified-zero
  "The zeros that cannot be trusted: nothing was read, or a ledger was
   missing. Reported separately so the report names its own negative scope
   instead of quietly promoting 'we did not look' into 'nothing cited it'."
  [dir ledger-paths]
  (->> (fire-counts dir ledger-paths)
       (filterv #(and (zero? (:fires %)) (not (:verified %))))))

(defn- prune-report
  "One line per page: `slug  events=N  fires=N  last=DATE  [NEVER]|[DATELESS]|[UNVERIFIED]`.
   Two units are printed because they mean different things: `fires` counts
   citing lines, `events` counts distinct dated ledger entries — the observation
   unit our crystallization gate actually demands. NEVER is a measured zero;
   DATELESS means cited but undatable, so recurrence cannot be judged;
   UNVERIFIED is an admission that nothing was read."
  [counts]
  (mapv (fn [{:keys [slug fires events last-fired verified]}]
          (format "%-38s events=%-3d fires=%-3d last=%s%s" slug events fires
                  (or last-fired "-")
                  (cond
                    (pos? events) ""
                    (pos? fires) "   [DATELESS]"
                    verified "   [NEVER]"
                    :else "   [UNVERIFIED]")))
        counts))

;; ------------------------------------------------------ append integrity
;; Stolen from lucamartins/ouroboros, `scripts/textdiff.mjs:16-95`
;; (`isInsertion` + `joinsExisting`), read 2026-11-13:
;;
;;   "Whether changing `before` into `after` only adds whole lines: every line
;;    of `before` is still there, in order and unchanged (trailing whitespace
;;    aside). Text added inside an existing line rewrites that line even when
;;    its old words survive in it ('run the tests' to 'do not run the tests',
;;    or a clause appended to a rule), so it is not an insertion."
;;
;; They use it to decide whether an edit needs human approval. We use it for a
;; narrower question about our own append-only ledgers: did anything they
;; already said disappear? `brain/improvements.md` and `brain/wins.md` are
;; append-only by contract (mechanical-confidence-supersede: a correction is
;; appended and the old line is marked superseded, never rewritten), and the
;; snapshots that `snapshot!` writes beside them are the only record of what
;; each turn started from. Nothing in the repo ever read those snapshots:
;; `grep -rn "\.bak" src/ scripts/ bb.edn` returns only preflight's exclusion
;; regex and a state-hygiene comment — writers, no readers. The 2026-11-04
;; lesson, exactly: a receipt needs a reader, or it is a note to nobody.
;;
;; Measured 2026-11-13 before writing any code, over the live corpus: 60
;; ledger snapshots, and `wins.md` lost two entries — `## 2026-09-27 — six
;; scout recommendations implemented as bb-agent.knowledge` and
;; `## 2026-10-10 — propose! fails closed on raw digests` are present in
;; `wins.md.bak-20261012` (6 lines) and `wins.md.bak-20261013` (9 lines) and
;; ABSENT from every snapshot from `wins.md.bak-20261017` (20 lines) onward and
;; from the live file (`grep -c "six scout recommendations" brain/wins.md` → 0).
;; So the loss happened between 2026-10-13 and 2026-10-17 and went unnoticed
;; for 26 days, through ~20 turns that each reported "pre-write snapshots
;; taken" as a receipt. The snapshots were taken; nothing read them.

(def reword-prefix-chars
  "How many leading characters (after trimming) a live line must share with a
   lost line for the loss to read as an in-place REWORDING rather than a
   deletion. Ours, and derived from the corpus rather than imported: markdown
   heading prefixes collide at 8-10 chars (`## 2026-1` is shared by every dated
   entry of a month) and `- evidence: ` is 12 chars shared by nearly every
   bullet in both ledgers, so 24 sits above both collisions and below the 162
   and 212 shared prefixes measured on the two real rewordings of 2026-10-19
   (the bare-basename -> pinned-coordinate rewrite)."
  24)

(defn append-only-ledgers
  "The ledger files that are append-only by contract, as absolute paths.
   Deliberately NOT the knowledge pages: a page is edited in place, so a
   rewritten line there is ordinary authorship and reporting it would be
   noise — measured the same day, a probe over all 144 brain snapshots called
   132 'non-insertion', almost all of them legitimate stamp moves and
   restructured headings. The two ledgers are the files where a lost line is a
   finding."
  [home]
  (mapv #(str (fs/path home "brain" %)) ["improvements.md" "wins.md"]))

(defn ledger-snapshots
  "The `.bak-*` siblings of one file, oldest first. Sorted by mtime, then by
   name, because a name carries a date but not a time and two snapshots share
   a day (`wins.md.bak-20261104` and `-20261104b` are byte-identical, measured)."
  [path]
  (let [p (fs/path (str path))
        parent (fs/parent p)
        base (fs/file-name p)]
    (when (and parent (fs/directory? parent))
      (->> (fs/glob (str parent) (str base ".bak-*"))
           (filter fs/regular-file?)
           (sort-by (juxt #(.lastModified (fs/file %)) #(fs/file-name %)))))))

(defn insertion?
  "Is every line of `a` still present in `b`, in order? An in-order subsequence
   test over raw `split-lines` — no trimming, no blank-collapsing, no case
   folding. Ouroboros trims trailing whitespace before comparing; we do not,
   because a trailing-space change in a ledger is still a change to a line a
   human reads, and normalizing is how a real edit hides inside a 'no-op'.
   Returns the first line of `a` that `b` cannot place, or nil when `a` is an
   insertion."
  [a b]
  (let [n (count b)]
    (loop [i 0 j 0]
      (cond
        (= i (count a)) nil
        (>= j n) (nth a i)
        (= (nth a i) (nth b j)) (recur (inc i) (inc j))
        :else (recur i (inc j))))))

(defn lost-lines
  "Lines of `a` that `b` does not contain anywhere, in order and deduped.
   Set difference, not the subsequence test: `insertion?` answers 'was this an
   append?' and this answers 'what text is gone?'. They disagree legitimately —
   a line moved to the top of a file is present but out of order, so
   `insertion?` names it while `lost-lines` does not. Reported separately
   rather than reconciled, because which one matters depends on the question."
  [a b]
  (let [bset (set b)]
    (->> a (remove bset) (distinct) (vec))))

(defn reworded?
  "Does `live` hold a line that shares at least `reword-prefix-chars` leading
   characters with this lost line? A yes means the line was rewritten in place
   (our own append-and-supersede corrections do exactly that — 2026-10-19
   pinned bare basenames, 2026-11-01 turned cross-repo cites into URLs); a no
   means the text is simply gone. Prefix comparison rather than similarity
   scoring: a Jaccard or edit-distance threshold would be a number tuned to
   nobody's corpus, and a leading run is what a rewording preserves by
   construction."
  [lost live-lines]
  (let [l (str/trim lost)
        n (min reword-prefix-chars (count l))]
    (and (>= (count l) n)
         (let [pre (subs l 0 n)]
           (some #(and (>= (count (str/trim %)) n)
                       (= pre (subs (str/trim %) 0 n)))
                 live-lines)))))

(defn append-row
  "One snapshot's verdict against the live file. `:verdict` is
   :insertion-only (nothing lost), :reworded (every lost line has a live
   analogue — an in-place correction) or :lost (at least one lost line has no
   analogue anywhere in the live file). A missing live file is :live-absent
   and carries no verdict about its contents, because not-found is not zero
   (aden-hive/hive, 2026-10-14): an absent ledger is not a clean one."
  [ledger snap]
  (let [live (fs/path (str ledger))
        a (str/split-lines (slurp (str snap)))]
    (if-not (fs/regular-file? live)
      {:ledger (str ledger)
       :snapshot (str snap)
       :snap-lines (count a)
       :live-lines nil
       :verdict :live-absent}
      (let [b (str/split-lines (slurp (str live)))
            first-lost (insertion? a b)
            lost (lost-lines a b)
            verdict (cond
                      (nil? first-lost) :insertion-only
                      (every? #(reworded? % b) lost) :reworded
                      :else :lost)]
        {:ledger (str ledger)
         :snapshot (str snap)
         :snap-lines (count a)
         :live-lines (count b)
         :verdict verdict
         :lost-count (count lost)
         :first-lost (first (filter #(not (reworded? % b)) lost))}))))

(defn append-rows
  "Every (ledger, snapshot) pair under `home`, ledgers in contract order and
   snapshots oldest first. Data in / data out: no printing, no mutation."
  [home]
  (vec (mapcat (fn [ledger]
                 (map (partial append-row ledger) (ledger-snapshots ledger)))
               (append-only-ledgers home))))

(defn append-report
  "The human receipt. Lost rows first, because a report that buries its finding
   under 53 clean rows is a report nobody reads — the same reason `fire-counts`
   sorts never-fired to the top (no class prior, 2026-10-17). Counts print
   beside the verdicts so the reader can check the partition sums instead of
   trusting the headline."
  [rows]
  (let [rank {:lost 0 :live-absent 1 :reworded 2 :insertion-only 3}
        ordered (sort-by (juxt #(get rank (:verdict %) 9) :snapshot) rows)
        counts (frequencies (map :verdict rows))
        head (str "append integrity: " (count rows) " ledger snapshot(s) compared "
                  "against their live file | lost " (get counts :lost 0)
                  " | reworded " (get counts :reworded 0)
                  " | insertion-only " (get counts :insertion-only 0)
                  " | live-absent " (get counts :live-absent 0))
        finding (map (fn [r]
                       (let [gone (when (seq (:first-lost r))
                                    (str " | gone: "
                                         (pr-str (subs (:first-lost r) 0
                                                       (min 110 (count (:first-lost r)))))))]
                         (str "  " (str/upper-case (name (:verdict r))) " "
                              (fs/file-name (fs/path (:snapshot r)))
                              " (" (:snap-lines r) " snapshot line(s) -> "
                              (or (:live-lines r) "?") " live)" gone)))
                     (filter #(not= :insertion-only (:verdict %)) ordered))
        unverified (when (zero? (count rows))
                     [(str "[UNVERIFIED] 0 snapshots compared — either no `.bak-*` "
                           "sibling sits beside an append-only ledger, or the walk "
                           "has rotted. A zero here is not evidence of a clean "
                           "corpus (bug-ops/zeph: a scan that finds nothing is "
                           "broken until proven otherwise).")])
        legend [(str "verdicts: INSERTION-ONLY = every snapshot line is still in "
                     "the live file, in order. REWORDED = lines changed but each "
                     "has a live line sharing its first " reword-prefix-chars
                     " trimmed chars, which is what append-and-supersede "
                     "corrections look like. LOST = a line with no analogue "
                     "anywhere in the live file. LIVE-ABSENT = the ledger itself "
                     "is gone, so nothing about its contents was measured.")
                (str "negative scope: this compares each snapshot to the LIVE "
                     "file, not to the next snapshot, so it reports what is "
                     "missing today and cannot say which turn removed it — the "
                     "snapshots bound the window, they do not name the writer. A "
                     "LOST row is a question, not a verdict of blame: the "
                     "2026-10-09 ledger cleanup deleted 276 lines of raw-digest "
                     "pollution on purpose and reads as LOST here. Nothing was "
                     "edited, restored or archived.")]]
    (concat [head] finding unverified legend)))


;; --------------------------------------------- append waivers (2026-11-13)
;; A LOST row is a question, and two of ours have answers a human already gave:
;; `improvements.md.bak-20261009-scout-cleanup` (276 of 676 lines removed on
;; purpose, the raw-digest cleanup recorded in the ledger's own 2026-10-09
;; entry) and `improvements.md.bak-20260927-012056` (4 lines to a heading
;; merge whose content is live and verified). Leaving them as LOST means every
;; future run of `bb knowledge appends` re-raises a settled question, and a
;; report whose findings are all known-answers is a report nobody reads.
;;
;; A waiver is DATA, not code: `brain/append-waivers.edn` maps a snapshot
;; basename to a reason, and the row becomes `:waived` with that reason beside
;; it. Not a filter inside `append-rows` — the comparison still runs and still
;; measures, so a waiver can never hide a NEW loss in the same snapshot.
;; Nothing is waived by default: an absent or unreadable file is an empty map
;; (not-found is not zero, aden-hive/hive 2026-10-14), and an unreadable file
;; is reported rather than swallowed.

(def waiver-rel-path
  "The waiver file's home-relative path. Kept as data so the reader, the report
   and the tests cannot drift about where waivers live."
  "brain/append-waivers.edn")

(defn read-waivers
  "`{snapshot-basename reason}` from `<home>/brain/append-waivers.edn`.
   Returns `{:waivers {} :status :read|:missing|:unreadable}` — a missing file
   is an empty waiver set, and an unreadable or malformed one is named rather
   than silently read as empty, because \"no waivers\" and \"could not read the
   waivers\" are different facts (the 2026-10-14 rule). Never throws."
  [home]
  (let [p (fs/path (str home) (first (str/split waiver-rel-path #"/"))
                   (second (str/split waiver-rel-path #"/")))]
    (cond
      (not (fs/exists? p)) {:waivers {} :status :missing}
      (not (fs/regular-file? p)) {:waivers {} :status :unreadable
                                  :detail "not a regular file"}
      :else
      (try
        (let [v (edn/read-string (slurp (str p)))]
          (if (map? v)
            {:waivers (into {} (map (fn [[k val]] [(str k) (str val)]) v))
             :status :read}
            {:waivers {} :status :unreadable
             :detail (str "expected a map of basename -> reason, got " (type v))}))
        (catch Exception e
          {:waivers {} :status :unreadable :detail (str (.getMessage e))})))))

(defn apply-waivers
  "Mark rows whose snapshot basename carries a waiver. `:waived` keeps the
   measurement (`:lost-count`, `:first-lost` stay on the row) and adds
   `:waiver` — so the reader sees what was lost AND that a human settled it.
   Only `:lost` rows can be waived: an insertion-only row has nothing to
   waive, and waiving a `:live-absent` row would claim knowledge about a file
   that could not be read."
  [rows waivers]
  (mapv (fn [r]
          (if-let [w (and (= :lost (:verdict r))
                          (get waivers (fs/file-name (fs/path (:snapshot r)))))]
            (assoc r :verdict :waived :waiver w)
            r))
        rows))

(defn append-report-waived
  "Same report as `append-report`, with waived rows separated out and their
   reason printed. Waived rows are counted in their own bucket so the
   partition still sums and a waiver cannot quietly shrink the finding count."
  [rows waiver-info]
  (let [rank {:lost 0 :live-absent 1 :reworded 2 :waived 3 :insertion-only 4}
        ordered (sort-by (juxt #(get rank (:verdict %) 9) :snapshot) rows)
        counts (frequencies (map :verdict rows))
        head (str "append integrity: " (count rows) " ledger snapshot(s) compared "
                  "against their live file | lost " (get counts :lost 0)
                  " | waived " (get counts :waived 0)
                  " | reworded " (get counts :reworded 0)
                  " | insertion-only " (get counts :insertion-only 0)
                  " | live-absent " (get counts :live-absent 0))
        finding (map (fn [r]
                       (let [gone (when (seq (:first-lost r))
                                    (str " | gone: "
                                         (pr-str (subs (:first-lost r) 0
                                                       (min 110 (count (:first-lost r)))))))
                             why (when (:waiver r) (str " | WAIVED: " (:waiver r)))]
                         (str "  " (str/upper-case (name (:verdict r))) " "
                              (fs/file-name (fs/path (:snapshot r)))
                              " (" (:snap-lines r) " snapshot line(s) -> "
                              (or (:live-lines r) "?") " live)" gone why)))
                     (filter #(not= :insertion-only (:verdict %)) ordered))
        unverified (when (zero? (count rows))
                     [(str "[UNVERIFIED] 0 snapshots compared — either no `.bak-*` "
                           "sibling sits beside an append-only ledger, or the walk "
                           "has rotted. A zero here is not evidence of a clean "
                           "corpus (bug-ops/zeph: a scan that finds nothing is "
                           "broken until proven otherwise).")])
        waiver-scope (str "waivers: " waiver-rel-path " "
                          (case (:status waiver-info)
                            :read (str "read, " (count (:waivers waiver-info)) " entry(ies)")
                            :missing "not present, so nothing is waived — an absent file is an empty waiver set, not a clean corpus"
                            :unreadable (str "UNREADABLE (" (:detail waiver-info) "), so nothing is waived rather than waived-blindly")
                            (str "status " (:status waiver-info)))
                          ". A waiver keeps the measurement and adds a human's reason; it cannot hide a NEW loss in the same snapshot, and only :lost rows can be waived.")
        legend [(str "verdicts: INSERTION-ONLY = every snapshot line is still in "
                     "the live file, in order. REWORDED = lines changed but each "
                     "has a live line sharing its first " reword-prefix-chars
                     " trimmed chars, which is what append-and-supersede "
                     "corrections look like. LOST = a line with no analogue "
                     "anywhere in the live file. WAIVED = LOST with a human's "
                     "recorded reason. LIVE-ABSENT = the ledger itself is gone, "
                     "so nothing about its contents was measured.")
                (str "negative scope: this compares each snapshot to the LIVE "
                     "file, not to the next snapshot, so it reports what is "
                     "missing today and cannot say which turn removed it — the "
                     "snapshots bound the window, they do not name the writer. A "
                     "LOST row is a question, not a verdict of blame: the "
                     "2026-10-09 ledger cleanup deleted 276 lines of raw-digest "
                     "pollution on purpose and reads as LOST here until waived. "
                     "Nothing was edited, restored or archived.")]]
    (concat [head] finding unverified [waiver-scope] legend)))

;; --------------------------------------------------------------------- cli
;; The seam. A measurement no boundary calls measures nothing — that is
;; exactly how preflight.clj landed unwired on 2026-10-12. This is report-only
;; by design: it prints, it never touches a page.

(defn- default-ledgers
  "The two ledgers a knowledge page can fire in."
  [home]
  [(str (fs/path home "brain" "improvements.md"))
   (str (fs/path home "brain" "wins.md"))])

(defn- missing-path
  "First of `paths` that is not a regular file, or nil."
  [paths]
  (first (remove #(fs/regular-file? (fs/path (str %))) paths)))

(defn -main
  "Usage: bb knowledge prune [dir] [ledger...] | bb knowledge curator [dir]
          | bb knowledge guards [dir] [--index dir]... | bb knowledge routing
          | bb knowledge tiers [home]
          | bb knowledge appends [home]
   Exit 0 = report printed, 2 = usage or a missing path, 1 = a :forbidden file is
   TAMPERED or MISSING. Never mutates (the baseline is written by hand)."
  [args]
  (let [home (config/home)
        default-dir (str (fs/path home "brain" "knowledge"))
        ledgers (default-ledgers home)
        [cmd & rest-args] args]
    (case cmd
      "prune"
      (let [[dir & more] (if (empty? rest-args) [default-dir] rest-args)
            paths (or (seq more) ledgers)
            bad (or (and (not (fs/exists? (str dir))) (str dir)) (missing-path paths))]
        (if bad
          (do (println "knowledge prune: no such path —" bad) 2)
          (let [counts (fire-counts dir paths)
                missing (or (:ledgers-missing (first counts)) 0)]
            (doseq [line (prune-report counts)] (println line))
            (println (str "prune: " (count counts) " pages | "
                          (count (never-fired dir paths)) " never fired | "
                          (count (dateless dir paths)) " dateless | "
                          (count (unverified-zero dir paths)) " unverifiable | ledgers: "
                          (str/join ", " (map #(fs/file-name (fs/path %)) paths))))
            (println "units: fires = citing lines, events = distinct DATED ledger entries.
  A page cited only under undated headings (propose blocks are filed as
  `## owner/repo`) is DATELESS, not never fired — its recurrence is not
  computable, and a gap in the measurement is not a zero.")
            (when (pos? missing)
              (println (str "negative scope: " missing " ledger(s) not read — those zeros are "
                            "not-found, not measured. Report only: nothing was archived or edited.")))
            0)))

      "curator"
      (let [dir (or (first rest-args) default-dir)]
        (if-not (fs/exists? (str dir))
          (do (println "knowledge curator: no such dir —" dir) 2)
          (let [{:keys [problems duplicate-titles never-fired]}
                (curator-candidates dir {:ledger-paths ledgers})]
            (println "problems (stale/unmarked):" (count problems))
            (doseq [p problems] (println "  " (:status p) (fs/file-name (fs/path (:file p)))))
            (println "duplicate titles:" (count duplicate-titles))
            (doseq [d duplicate-titles] (println "  " (:title d) (str/join " " (:files d))))
            (println "never fired (prune questions):" (count never-fired))
            (doseq [n never-fired] (println "  " (:slug n)))
            (let [unv (unverified-zero dir ledgers)]
              (println "unverifiable (no ledger read):" (count unv))
              (doseq [u unv] (println "  " (:slug u))))
            0)))

      "routing"
      (let [rows (routing-rows (brain/knowledge-index))]
        (doseq [line (routing-report rows)] (println line))
        0)

      "appends"
      (let [h (or (first rest-args) home)]
        (if-not (fs/directory? (fs/path h))
          (do (println "knowledge appends: no such home dir —" h) 2)
          (let [wi (read-waivers h)
                rows (apply-waivers (append-rows h) (:waivers wi))]
            (doseq [line (append-report-waived rows wi)] (println line))
            0)))

      "tiers"
      (let [h (or (first rest-args) home)]
        (if-not (fs/directory? (fs/path h))
          (do (println "knowledge tiers: no such home dir —" h) 2)
          (let [rows (tier-digests h)
                baseline (tiers-baseline-audit h)
                instruments (instrument-rows h)]
            (println (tiers-report rows baseline instruments))
            (if (some #{:tampered :missing} (map :verdict rows)) 1 0))))

      "guards"
      (let [;; Split argv ONCE: `--index` plus its value are flags, everything
            ;; else is positional, and the first positional is the corpus dir.
            ;; Reading argv[0] as the dir made the documented invocation
            ;; `bb knowledge guards --index <dir>` print
            ;; `no such path — --index` and exit 2 — measured live 2026-11-01,
            ;; twice. The suite stayed green because every test passed a brain
            ;; dir in front of the flag, so the shape a human types against the
            ;; real corpus was never exercised. `bb coords` fixed the same
            ;; defect on 2026-10-19 for the same reason: a tool that demands an
            ;; argument its own usage line marks optional is a tool nobody runs.
            {idx :idx positional :positional}
            (loop [acc {:idx [] :positional []} [a & r] rest-args]
              (cond
                (nil? a) acc
                (= "--index" a) (recur (update acc :idx conj (first r)) (rest r))
                :else (recur (update acc :positional conj a) r)))
            dir (or (first positional) default-dir)
            index-dirs (if (seq idx) idx [(str home) (str (fs/path home "theseus"))])
            ;; index-dirs are DIRECTORIES, so missing-path (which tests
            ;; fs/regular-file?) is the wrong predicate here: it returned the
            ;; first index dir as 'no such path' on every run. Caught red.
            bad (or (and (not (fs/exists? (str dir))) (str dir))
                    (first (remove #(fs/exists? (fs/path (str %))) index-dirs)))]
        (if bad
          (do (println "knowledge guards: no such path —" bad) 2)
          (let [s (guard-coverage-summary dir index-dirs ledgers)]
            (doseq [line (guards-report s)] (println line))
            0)))

      (do (println (str "usage: bb knowledge prune [dir] [ledger...] | bb knowledge curator [dir]"
                        " | bb knowledge guards [dir] [--index dir]..."
                        " | bb knowledge routing | bb knowledge tiers [home]"
                        " | bb knowledge appends [home]"))
          2))))

;; ------------------------------------------------------- guard coverage
;; Stolen from alkanfel1987/pantheon-seed, memory/pattern-promotion.md:118 —
;; a named mistake class WITHOUT a mechanical check gave 6 repeats in one
;; session while classes WITH one never reached consequences, hence the
;; promotion rule "build a check, don't strengthen the wording". Our
;; page-fires counts CITATIONS of a page; nothing asked whether a machine
;; stands behind the rule. This is that question, composed from measurements
;; that already exist rather than a new substrate: coordinates/audit resolves
;; every cite, page-status gives decay, page-fires gives use. The join is new.

(def code-extensions
  "Extensions that mean 'a program', so a resolved coordinate naming one is a
   machine that could enforce a rule. Deliberately narrower than
   coordinates/corpus-extensions: a cite of another .md page is prose citing
   prose, which is exactly what this measurement must not count as a guard."
  #{"clj" "cljc" "cljs" "bb" "sh"})

(defn- code-cite?
  [row]
  (contains? code-extensions (fs/extension (fs/path (:path row)))))

(defn page-machines
  "The programs one page's coordinates actually resolve to. A dangling or
   external code cite is NOT a machine — counting it would report a guard that
   does not exist, the false-clean shape this lane has caught five times."
  [rows]
  (->> rows
       (filter :resolved)
       (remove :external)
       (remove :out-of-range)
       ;; 2026-10-31: an ambiguous resolution is a GUESS (first of N
       ;; same-named files), so counting it as a machine would report
       ;; enforcement that may live in a different file. Red first —
       ;; guard-coverage-a-guessed-cite-is-not-a-machine.
       (remove :ambiguous)
       (filter code-cite?)
       (mapv :resolved)
       distinct
       sort
       vec))

(defn page-guesses
  "Code coordinates a page cites that resolved only by GUESS — a bare basename
   chosen from several same-named files. Neither a machine nor a ghost: the file
   may be the one meant, and the audit cannot say. Reported separately because a
   verdict taken from a guess reads exactly like one taken from a pinned cite —
   the mistake class coordinate-discipline.md named on 2026-10-18."
  [rows]
  (->> rows
       (filter code-cite?)
       (filter :ambiguous)
       (mapv #(str (:path %) ":" (:line %)))
       distinct
       sort
       vec))

(defn page-ghosts
  "Code coordinates a page asserts that do NOT resolve: the page claims a
   machine stands behind it and no such file exists. The inverse failure, and
   the one worth naming separately because it reads as a guard in prose."
  [rows]
  ;; audit puts dangling rows in their own bucket and sets no :dangling flag
  ;; (only :external and :out-of-range are assoc'd), so "no resolved file" is
  ;; the test. Filtering on a flag that is never set returned () for every
  ;; ghost — caught red, and it is the false-clean shape this lane keeps
  ;; meeting: a claim of a machine that does not exist reported as clean.
  (->> rows
       (filter code-cite?)
       ;; ambiguous rows DID resolve (to a guess), so they are not ghosts:
       ;; calling them missing would report a defect that may not exist.
       (remove :ambiguous)
       (filter #(or (nil? (:resolved %)) (:out-of-range %)))
       (mapv #(str (:path %) ":" (:line %)))
       distinct
       sort
       vec))

(defn guard-coverage
  "One row per knowledge page: does a machine stand behind this rule?
   [{:slug :file :status :fires :verified :machines [path] :ghosts [cite]}]
   Sorted loud-unbacked first (fires desc), then slug — no class prior, so a
   heavily-cited wording-only rule cannot hide behind a quiet one.

   :machines empty means WORDING-ONLY: the page is a rule with no program
   behind it. That is a question for the curator, not a verdict — a page can
   be load-bearing through the brain index without a line of code, and this
   measurement cannot see that. It answers 'which rules are prose?', which is
   the question pantheon-seed measured before deciding to build checks."
  ([dir index-dirs] (guard-coverage dir index-dirs []))
  ([dir index-dirs ledger-paths]
  (let [a (coordinates/audit dir index-dirs)
        ;; :external rows are left out on purpose — another repo's file can
        ;; never be a machine standing behind OUR rule.
        audit-rows (concat (:resolved a) (:dangling a) (:out-of-range a))
        by-page (group-by #(fs/file-name (fs/path (:source %))) audit-rows)]
    (->> (fs/glob (str dir) "*.md")
         (map (fn [p]
                (let [base (fs/file-name (fs/path (str p)))
                      rows (get by-page base [])
                      f (page-fires p ledger-paths)]
                  (merge (select-keys f [:slug :fires :verified :last-fired])
                         (select-keys (page-status p) [:status])
                         {:file (str p)
                          :machines (page-machines rows)
                          :ghosts (page-ghosts rows)
                          :guessed (page-guesses rows)}))))
         (sort-by (juxt #(- (:fires %)) :slug))))))

(defn index-code-files
  "Code-extension files the index walk can actually see, distinct. The
   denominator behind every WORDING-ONLY and GHOST verdict: `guard-coverage`
   calls `coordinates/audit`, which resolves a relative cite against this walk,
   so a walk that sees no code file makes EVERY local code cite unresolvable BY
   CONSTRUCTION — and the report would then print `G = 1/29 pages machine-backed`
   plus GHOST rows naming our own real source files, exit 0, with nothing saying
   the measurement was pointed at an empty tree.

   Measured live 2026-11-03, before this existed:
     bb knowledge guards --index <an empty temp dir>
   printed `G = 1/29 pages machine-backed (3%)` and five GHOST rows —
   `protection-tiers claims ... src/bb_agent/knowledge.clj:100 ...
   src/bb_agent/tool/file.clj:73 — no such file/line` — for files that exist and
   are cited correctly. The one page still counted as machine-backed was
   `coordinate-discipline`, whose cite is ABSOLUTE and so resolves without the
   index (the 2026-10-18 fix). A false-DIRTY verdict about our own guards, from
   a flag the operator passed; the same family as the false NEVER (10-14), the
   whitelist-clean ratio (10-17), the false OUT OF RANGE (10-18), the vacuous
   zero (10-19) and armory's `PASS — 28555 valid` (10-20).

   Shape stolen twice over, from sources that both state it:
   * bug-ops/zeph `.github/scripts/check_integrity_audit.sh` — a scan that finds
     nothing is broken until proven otherwise (`FATAL ... pattern rot`).
   * hung12ct/culi `docs/evaluation.md` — the retrieval suite scores ABSTAIN
     cases beside hit rate, because a layer that always answers has no measured
     failure mode; and `internal/store/effectiveness.go` — \"one quiet session
     proves nothing\", so the floor is derived from the base rate, not asserted.

   `file-seq`, not `fs/glob \"**/*\"`: the glob form silently skips files one
   level under the root (measured 2026-10-19 in preflight, where it made every
   script invisible to the scan)."
  [index-dirs]
  (->> index-dirs
       (mapcat #(when (fs/exists? (fs/path (str %)))
                  (file-seq (io/file (str %)))))
       (filter #(.isFile ^java.io.File %))
       (filter #(contains? code-extensions (fs/extension (str %))))
       (map str)
       distinct
       vec))

(defn guard-coverage-summary
  "The loop metric: G = share of pages a machine stands behind. Data, so the
   report and any future comparison read the same number. Carries `:index` —
   the walk's own size — because a rate with no denominator is a claim about
   something the reader cannot check (governed-evidence-stamps)."
  ([dir index-dirs] (guard-coverage-summary dir index-dirs []))
  ([dir index-dirs ledger-paths]
  (let [rows (guard-coverage dir index-dirs ledger-paths)
        n (count rows)
        backed (filterv #(seq (:machines %)) rows)
        code-files (index-code-files index-dirs)]
    {:pages n
     :index {:dirs (mapv str index-dirs)
             :code-files (count code-files)}
     :machine-backed (count backed)
     ;; a double, not a ratio: the report formats it and callers compare it.
     :g (if (zero? n) 0.0 (double (/ (count backed) n)))
     :wording-only (filterv #(empty? (:machines %)) rows)
     :ghosted (filterv #(seq (:ghosts %)) rows)
     :guessed (filterv #(seq (:guessed %)) rows)})))

(defn- guards-report
  [s]
  (let [n (:pages s)]
    (mapv str
          (concat
           [(format "G = %d/%d pages machine-backed (%.0f%%) | index: %d code file(s) under %d dir(s)"
                    (:machine-backed s) n (* 100.0 (:g s))
                    (get-in s [:index :code-files]) (count (get-in s [:index :dirs])))]
           (when (zero? (get-in s [:index :code-files]))
             [(str "  [UNVERIFIED] the index walk saw NO code file, so no local "
                   "code coordinate could resolve. Every WORDING-ONLY and GHOST "
                   "row below describes the index, not the corpus: a GHOST naming "
                   "a file that exists is this report's blind spot, not a missing "
                   "guard. Point --index at a tree that holds the repo's source.")])
           (when (zero? n)
             [(str "  [UNVERIFIED] 0 pages measured — either the corpus is empty "
                   "or the *.md walk has rotted. A zero here is not evidence of a "
                   "clean corpus.")])
           (map #(format "  WORDING-ONLY %-34s fires=%-3d status=%s"
                         (:slug %) (:fires %) (name (:status %)))
                (:wording-only s))
           (map #(format "  GHOST %s claims %s — no such file/line"
                         (:slug %) (str/join ", " (:ghosts %)))
                (:ghosted s))
           (map #(format "  GUESSED %s cites %s — a bare basename matched several files, so the verdict is not counted"
                         (:slug %) (str/join ", " (:guessed %)))
                (:guessed s))
           [(str "negative scope: an empty :machines means no RESOLVING code "
                 "coordinate in the page. It does not prove the rule is "
                 "unenforced — a guard can live in a file the page never cites, "
                 "and a GHOST means no resolving file IN THE INDEX WALK, which "
                 "is not the same as no such file on disk, "
                 "and a page can be load-bearing through the brain index. "
                 "Conversely a resolving cite does not prove the program "
                 "ENFORCES the rule; it proves the page names one that exists. "
                 "A cite resolved from several same-named files is a GUESS and "
                 "is counted as neither machine nor ghost. "
                 "Wiring is a separate question (bb preflight sites, bb knowledge prune). "
                 "Report only — nothing was edited.")]))))


;; ---------------------------------------------------------------------
;; 2026-11-03 — does the brain index ROUTE? Red first (the four deftests below
;; failed at analysis with `Unable to resolve symbol: k/routing-terms`), then
;; implemented directly after `bb-knowledge-routing-rep-434091` errored on a
;; provider timeout — the third `HttpTimeoutException` of the day on
;; `ali/qwen3.8-max-0902` (rounds 17/25/37, 466s/1035s/1134s). The spec is
;; unchanged; the supervision boundary could not reach a model, which is a
;; receipt about the lane, not about the work.
;;
;; Source, level 2: hung12ct/culi `docs/evaluation.md` — a retrieval layer is
;; scored on five metrics where "Abstain accuracy: Share of abstain cases
;; (empty `expect`) where nothing was injected" sits beside hit rate, MRR,
;; recall and precision, and the suite "keeps its failing cases; they are the
;; useful part" under `### Known misses`. The reason: a layer that always
;; answers has no measured failure mode.
;;
;; Our index cannot abstain — `bb-agent.brain/knowledge-index` rides whole in
;; the system message by design, one `name — scope` line per page — so the
;; honest analogue of an abstain case is a line that routes to NOTHING: a scope
;; with too few content terms to select the page, or one indistinguishable from
;; another. Both were measured once with a throwaway script in /tmp on
;; 2026-11-03 (29 lines, terms 3-11, max Jaccard 0.200, zero content-free
;; lines) and then left there, which is `bb coords` on 2026-10-14 exactly: a
;; real finding produced by a script no boundary calls.

(def routing-stopwords
  "Words that carry no routing information. Kept tiny and explicit rather than
   imported: a big stopword list is a second corpus to maintain, and this
   measurement has 29 subjects."
  #{"the" "a" "an" "of" "for" "and" "to" "in" "on" "is" "are" "it" "its"
    "with" "that" "this" "be" "not" "as" "at" "by" "from" "or" "one" "two"
    "each" "every" "when" "what" "how" "why" "which" "who"})

(def routing-thin-min
  "Below this many content terms a scope line cannot select its page. 3, and it
   is ours: the live corpus's minimum is exactly 3 (measured 2026-11-03), so the
   flag fires on the tail we already have and nothing above it. culi's
   `noveltyJaccard = 0.6` and `carddedup`'s `JacNear = 0.40` are tuned to their
   corpora; importing either would be laundering a threshold
   (mechanical-confidence-supersede)."
  3)

(def routing-collision-jaccard
  "Two scope lines sharing at least this Jaccard of content terms cannot be
   selected between. Also ours, from the measured distribution: the corpus's
   observed maximum is 0.200, so this fires on nothing today and names the shape
   a future page would have to reach."
  0.40)

(defn routing-terms
  "Content terms of one scope line: lowercase alnum tokens longer than 2 chars,
   minus stopwords. Data in, data out — no file access."
  [scope]
  (->> (str/lower-case (str scope))
       (re-seq #"[a-z0-9][a-z0-9_-]*")
       (remove routing-stopwords)
       (filter #(> (count %) 2))
       set))

(defn- jaccard-sim [a b]
  (let [u (count (set/union a b))]
    (if (zero? u) 0.0 (double (/ (count (set/intersection a b)) u)))))

(defn routing-rows
  "`index-lines` are `slug.md — scope` strings — exactly what
   `bb-agent.brain/knowledge-index` emits — so this is a pure function over the
   index, not a second walk of the corpus.

   One row per line:
   {:slug :scope :terms n :max-jaccard d :nearest slug :thin? bool :collision? bool}

   Two flags, and they are the abstain cases of an index that cannot abstain:
   * :thin? — fewer than `routing-thin-min` content terms. The line cannot select
     its page, so the page is unreachable by scope however good it is. This is
     the failure a hit-rate-only measurement never sees.
   * :collision? — another line shares >= `routing-collision-jaccard` of its
     content terms. Neither can be chosen over the other, so one of them is dead
     weight and the index is lying about the split.

   Deliberately NOT a score and NOT a gate: report-only, exit 0. A thin line may
   still be load-bearing because a human reads the whole index anyway, and
   refusing to load the brain over a Jaccard number would be a guard rejecting
   truthful prose (the 2026-10-12 preflight lesson)."
  [index-lines]
  (let [parsed (mapv (fn [line]
                       (let [[slug scope] (str/split (str line) #" — " 2)]
                         {:slug (or slug (str line))
                          :scope (or scope "")
                          :terms (routing-terms (or scope ""))}))
                     index-lines)]
    (mapv (fn [{:keys [slug terms] :as p}]
            (let [others (remove #(= (:slug %) slug) parsed)
                  scored (map (fn [o] [(jaccard-sim terms (:terms o)) (:slug o)]) others)
                  [best nearest] (if (seq scored)
                                   (first (reverse (sort-by first scored)))
                                   [0.0 nil])]
              (-> p
                  (dissoc :terms)
                  (assoc :terms (count terms)
                         :max-jaccard (double best)
                         :nearest nearest
                         :thin? (< (count terms) routing-thin-min)
                         :collision? (>= (double best) routing-collision-jaccard)))))
          parsed)))

(defn routing-report
  "Lines for the CLI. Prints the denominator beside the verdict (the
   2026-11-03 guards lesson: a rate with no denominator is a claim the reader
   cannot check), both flags as lists, and the known misses — culi keeps its
   failing cases because 'they are the useful part', so a clean run says so
   explicitly instead of printing nothing."
  [rows]
  (let [n (count rows)
        thin (filterv :thin? rows)
        coll (filterv :collision? rows)
        terms (map :terms rows)]
    (mapv str
          (concat
           [(format "routing: %d index line(s) | content terms %s..%s | max Jaccard %.3f | THIN %d | COLLISION %d"
                    n
                    (if (seq terms) (str (apply min terms)) "-")
                    (if (seq terms) (str (apply max terms)) "-")
                    (double (if (seq rows) (apply max (map :max-jaccard rows)) 0.0))
                    (count thin) (count coll))]
           (when (zero? n)
             [(str "  [UNVERIFIED] 0 index lines measured — either the corpus is "
                   "empty or the index walk has rotted. A zero here is not evidence "
                   "of a routable corpus.")])
           (map #(format "  THIN %s — %d content term(s), cannot select its page: %s"
                         (:slug %) (:terms %) (:scope %))
                thin)
           (map #(format "  COLLISION %s <-> %s — Jaccard %.2f, a reader cannot choose between them"
                         (:slug %) (:nearest %) (:max-jaccard %))
                coll)
           [(if (and (seq rows) (empty? thin) (empty? coll))
              (str "  known misses: none at these thresholds. That is a measurement "
                   "of THIS corpus at THIS size, not a proof the index routes — the "
                   "measure is term overlap, and a scope line can be distinctive in "
                   "words and still ambiguous to a reader.")
              (str "  known misses: the flagged rows above are kept rather than "
                   "fixed by the tool — culi's suite keeps its failing cases because "
                   "they are the useful part."))
            (str "negative scope: content terms are alnum tokens >2 chars minus a "
                 "31-word stoplist, and similarity is Jaccard over those sets. That "
                 "measures WORD overlap, not meaning — two scopes can share no words "
                 "and still state the same rule, which an embedding would catch and "
                 "this report cannot. Thresholds are ours, chosen from the measured "
                 "distribution, not imported. Report only: no page was edited and no "
                 "index line was rewritten.")]))))
