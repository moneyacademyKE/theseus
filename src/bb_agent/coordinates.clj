(ns bb-agent.coordinates
  "Are the coordinates our own brain asserts real? Stolen from
   assafkip/claudemy — \"a checker refuses any lesson that cites a page the
   research didn't actually read.\" We take the checker and refuse the
   refusal: a dangling `file:line` is REPORTED, never blocked. Our honest
   receipts cite /tmp paths (the 2026-10-12 preflight proof did exactly
   that), and a guard that rejects truthful provenance is worse than a
   report that names it.

   Why this exists: coordinate-discipline.md says every claim carries a
   file:line / URL / commit. Nothing in the repo ever checked one, so that
   page was certainty level 1 (\"we said we'd do it\") and never level 4
   (\"ran it\"). This is the run.

   2026-10-18: a coordinate whose extension this corpus does not contain
   (another repo's .py, .ts) is EXTERNAL, not dangling — it can never resolve
   locally, so calling it DANGLING would be the same mistake as blocking a
   send on a truthful /tmp receipt. Externals are counted, named, and stated
   as unverified. Before this, coord-re's whitelist stopped at our own
   languages, so brain/**.md's 13 `.py` citations were invisible and the
   audit printed a clean ratio that was only clean inside the whitelist.

   2026-10-18, second fix the same day: an ABSOLUTE coordinate is read where
   it points. resolve-one basename-matched it too, so citing
   /Users/moe/theseus/goals/make-rsi-do-this-983334/act.sh:24 measured
   whichever of 33 `act.sh` files under $THESEUS_HOME sorted first — a 22-line
   probe fixture under .bin/ — and printed OUT OF RANGE on a coordinate that
   was correct. A measurement that reads the wrong file does not merely miss
   something; it asserts. Third of this mistake class in this lane, after the
   false NEVER (2026-10-14) and the false-clean ratio (2026-10-17).

   2026-11-01: the index counted each file once per index dir, and the DEFAULT
   index dirs are [$THESEUS_HOME, $THESEUS_HOME/theseus] — nested, so every repo
   file was walked twice. `resolve-one`'s suffix rule then found two candidates
   that were one file, and `audit` flagged every pinned relative cite as a
   guess. Measured before the fix: index 4789 entries vs 4360 distinct, and 86
   of 115 resolved rows carried `:ambiguous 2` — one value on every row, which
   is what a systematic mis-count looks like. Downstream `bb knowledge guards`
   printed G = 1/28 machine-backed (4%) with 7 GUESSED rows naming
   src/bb_agent/coordinates.clj:105, src/bb_agent/knowledge.clj:516 and friends
   — all false, because those cites pinned one file exactly. A double-walked
   index reports LESS enforcement than exists: the first false-DIRTY in this
   lane's false-verdict family, after the false NEVER (10-14), the
   whitelist-clean ratio (10-17), the false OUT OF RANGE (10-18) and the vacuous
   zero (10-19). Dedupe removes duplicate identities, not distinct ones: 33
   `act.sh` files under $THESEUS_HOME are still a real guess and still say so.

   Report-only by construction: nothing here writes, archives or rewrites."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ reading

(def coord-re
  "A `path.ext:LINE` or `path.ext:LINE-END` citation. The extension is what
   keeps prose ratios (\"4:5\") and clock times out of the measurement."
  #"([A-Za-z0-9_./-]+\.(?:clj|cljc|cljs|md|sh|bb|edn|json|txt|yml|yaml|py|ts|tsx|js|jsx|rs|go|rb|java|toml|sql)):(\d+)(?:-(\d+))?")

(def corpus-extensions
  "Extensions this repo actually contains, so a citation naming one CAN
   resolve here. Anything else is external by construction. The line between
   'we could have checked this' and 'we never could' is data, not judgment."
  #{"clj" "cljc" "cljs" "md" "sh" "bb" "edn" "json" "txt" "yml" "yaml"})

(defn- md-pages
  "Markdown pages in a dir tree, newest-irrelevant, sorted for a stable report.
   `.bak-*` snapshots are excluded — they are yesterday's copies of today's
   corpus and auditing them would double every finding."
  [dir]
  (->> (file-seq (io/file (str dir)))
       (filter #(and (.isFile ^java.io.File %)
                     (= "md" (fs/extension (str %)))
                     (not (str/includes? (str %) ".bak"))))
       (mapv str)
       sort))

(defn coords-in
  "Every coordinate in one string of text: [{:path :line :end}] in order of
   appearance. Data in, data out — no file access."
  [text]
  (mapv (fn [m]
          {:path (nth m 1)
           :line (parse-long (nth m 2))
           :end (some-> (nth m 3) parse-long)})
        (re-seq coord-re (str text))))

;; ------------------------------------------------------------- undecidable
;; 2026-11-04, from two sources read at file level this turn:
;;   * noamsto/agent-smith internal/analyst/citecheck.go:235-238 — a citation is
;;     DEMOTED when it cannot be verified and REJECTED only when it is provably
;;     wrong, because the two licence different actions.
;;   * Shambhavagrawal/rotless keeper.py:107 — `check` lists every reference the
;;     manifest does not track, with file:line. An untracked claim is a finding,
;;     not silence.
;;
;; Why: DANGLING means "we asserted a file:line and nothing on disk answers to
;; it" — a finding about OUR hygiene. A bare `Memory/contradiction-rules.md:11`
;; (ai-suplex's tree) or `INSTRUCTIONS.md:96` (Singularity-RepoQL's) names
;; somebody else's file and can never resolve here, so reporting it as DANGLING
;; is a false verdict about us. Measured live 2026-11-04: `bb coords ../brain`
;; printed both as DANGLING. The convention that makes the case decidable is
;; already written down (2026-11-01): a cite to another repository's file is a
;; URL, never a bare `path:line`. So an undecidable row is a CONVENTION
;; VIOLATION, not a broken claim, and the report says which of the two readings
;; applies instead of asserting one. Eighth member of the false-verdict family in
;; this lane, and the first in which the tool accused the corpus of a mistake it
;; did not make.

(defn undecidable-cite?
  "True when a cited path cannot be decided against this corpus at all, so no
   verdict about it is honest either way. ONE mechanical shape, a fact about the
   string rather than a guess about intent: its first path segment begins with an
   uppercase letter. This corpus is lowercase-with-hyphens (src/, brain/,
   scripts/, test/, state/), so an uppercase first segment names somebody else's
   project directory — Memory/contradiction-rules.md, INSTRUCTIONS.md,
   skills/rotless-skill-modernizer/SKILL.md.

   An ABSOLUTE path is not this predicate's business, and that is deliberate:
   `audit` reads an absolute cite where it points (the 2026-10-18 fix), so
   /tmp/leaky_demo.md is DECIDABLE — absent is a fact, and it stays DANGLING. A
   predicate with one job keeps an older rule in this file's suite true (an
   absolute cite that does not exist is still dangling) while adding the new
   bucket; folding /tmp in here would have contradicted it, and reclassifying a
   dead receipt as \"undecidable\" is the wrong answer anyway —
   coordinate-discipline.md says to EMBED such a receipt, not to excuse it."
  [cited]
  (let [p (str cited)]
    (and (not (str/starts-with? p "/"))
         (let [seg (first (str/split p #"/"))]
           (boolean (and (seq seg) (Character/isUpperCase (first seg))))))))

;; --------------------------------------------------------------- resolution

(defn- index-files
  "Every DISTINCT regular file under the index dirs, excluding .git noise.

   The unit of this collection is a FILE, not a walk — and that is load-bearing
   rather than tidy. The default index dirs are [$THESEUS_HOME,
   $THESEUS_HOME/theseus], and the second is inside the first, so a raw walk
   lists every repo file twice. `resolve-one`'s suffix rule then matched two
   paths that were one file, and `audit` reported every pinned relative cite as
   a guess.

   Measured live 2026-11-01, before the fix: index size 4789 vs 4360 distinct
   (429 duplicates); on the real corpus 86 of 115 resolved rows carried
   `:ambiguous 2`, and `distinct :ambiguous values` was `(2)` — one number on
   every row, which is what a systematic mis-count looks like. Downstream,
   knowledge/page-machines drops ambiguous rows (correctly: a guess is not a
   machine), so `bb knowledge guards` printed G = 7/28 machine-backed, 18 pages
   WORDING-ONLY and 7 GUESSED — and the GUESSED rows were false, because those
   cites pinned one file exactly. A double-walked index reports LESS enforcement
   than exists: the first false-DIRTY in this lane's false-verdict family, after
   the false NEVER (10-14), the whitelist-clean ratio (10-17), the false OUT OF
   RANGE (10-18) and the vacuous zero (10-19).

   Dedupe removes duplicate identities, not distinct ones: 33 `act.sh` files
   under $THESEUS_HOME are still a real guess, and still say so."
  [dirs]
  (->> dirs
       (mapcat #(file-seq (io/file (str %))))
       (filter #(.isFile ^java.io.File %))
       (mapv str)
       (remove #(str/includes? % "/.git/"))
       distinct
       sort))

(defn- resolve-one
  "Find the file a cited RELATIVE path names — an absolute cite is checked
   directly by `audit` and never comes here, because basename-matching an
   absolute path silently measures a different file. Preference order,
   because the ledgers cite both repo-relative and bare basenames:
     1. a candidate whose full path ends with the cited path (most specific);
     2. the unique candidate with that basename;
     3. the first such candidate — deterministic, and named in the report's
        negative scope rather than hidden behind an ambiguity flag.
   nil when nothing matches: that is a dangling coordinate."
  [cited indexed by-base]
  (let [base (fs/file-name (fs/path cited))
        tail (str "/" cited)
        suffix (filterv #(str/ends-with? % tail) indexed)
        names (get by-base base [])]
    (or (first suffix)
        (when (= 1 (count names)) (first names))
        (first names))))

;; ---------------------------------------------------------------- claim-cite
;; The house shape `path.ext:LINE` — `symbol`: a coordinate with the claim it
;; supports written beside it. `bb coords` proves the file and line EXIST; this
;; asks whether the line holds the symbol the ledger says it holds — the
;; mechanically checkable subset of Jialei-03/open-research-agent's rule
;; (backend/research_evidence.py:123-134: every number a result states must
;; appear in what it cites). That rule landed 2026-10-20 as a WRITING convention
;; because a regex cannot tell which of our digits are results. A symbol beside a
;; coordinate is the part that CAN be checked, so this checks that part and says
;; plainly what it left alone. Coverage-net shape from Shambhavagrawal/rotless
;; keeper.py:107 — a check rescans the text and reports every reference the
;; manifest does not track, with file:line.

(def claim-cite-re
  "A backticked `path.ext:LINE` (optionally `-END`), a dash — em, en or plain,
   because this corpus uses all three — and a backticked symbol."
  #"`([^`\s]+\.(?:clj|cljc|cljs|md|sh|bb|edn|json|txt|yml|yaml|py|ts|tsx|js|jsx|rs|go|rb|java|toml|sql)):(\d+)(?:-\d+)?`\s*(?:—|–|-)\s*`([^`]+)`")

(defn claim-cite
  "Every `path:LINE` — `symbol` claim in one string, as [{:path :line :sym}] in
   order of appearance. [] when a coordinate carries no claim beside it:
   not-found is not zero, and inventing a claim for a bare coordinate would turn
   every cite into a finding. Data in, data out — no file access, so this is
   testable without a corpus."
  [text]
  (mapv (fn [m]
          {:path (nth m 1) :line (parse-long (nth m 2)) :sym (str/trim (nth m 3))})
        (re-seq claim-cite-re (str text))))

(defn unsupported-claims
  "Every `path:LINE` — `symbol` claim whose cited line does not contain the
   symbol. Rows carry :source (the page asserting it), :page-line (where in that
   page), :path (the cite AS WRITTEN, so a report can quote the ledger back at
   it), :line, :sym, and :text — what the cited line actually says.

   Two kinds of row are SKIPPED rather than reported. An undecidable cite is
   another repository's line and not ours to judge; reporting it would be the
   same false verdict the :undecidable bucket exists to prevent. A cite that does
   not resolve at all is already a DANGLING finding, and reporting it twice would
   double-count one mistake.

   Report-only, and deliberately not fail-closed: claudemy REFUSES output citing
   an unread page and we refused that refusal on 2026-10-14, because our honest
   receipts cite /tmp paths and a guard that rejects truthful provenance is worse
   than a report that names it."
  ([dir index-dirs] (unsupported-claims dir index-dirs {}))
  ([dir index-dirs {:keys [root]}]
   (let [indexed (index-files index-dirs)
         by-base (group-by #(fs/file-name (fs/path %)) indexed)]
     (vec
      (mapcat
       (fn [page]
         (let [rel (if root (str/replace page (str root "/") "") page)
               lines (str/split-lines (slurp page))]
           (for [[i line] (map-indexed vector lines)
                 cl (claim-cite line)
                 :let [cited (:path cl)
                       f (when-not (undecidable-cite? cited)
                           (resolve-one cited indexed by-base))]
                 :when f
                 :let [target-lines (str/split-lines (slurp f))
                       target (nth target-lines (dec (:line cl)) nil)
                       ;; Where the symbol IS, 1-based, when it is somewhere in
                       ;; that file. nil — never [] — when it is nowhere: a
                       ;; drifted number and an unsupported claim are different
                       ;; findings with different fixes, and an empty list read
                       ;; as zero is the conflation this file exists to report
                       ;; rather than emit.
                       elsewhere (when (string? target)
                                   (let [hits (keep-indexed
                                               (fn [j l]
                                                 (when (str/includes? l (:sym cl))
                                                   (inc j)))
                                               target-lines)]
                                     (when (seq hits) (vec hits))))]
                 :when (and (string? target) (not (str/includes? target (:sym cl))))]
             {:source rel :page-line (inc i) :path cited :line (:line cl)
              :sym (:sym cl) :text (str/trim target)
              :kind (if elsewhere :drifted :unsupported)
              :elsewhere elsewhere})))
       (md-pages dir))))))

(defn claims-print!
  "Print the claim rows labelled by kind, then the two counts separately.

  Why two labels — measured on the live corpus 2026-11-05, not assumed: `bb coords`
  printed **31** `CLAIM NOT ON THAT LINE` rows and the 2026-11-04 ledger entry
  filed them as \"a question list, not a verdict\" because nothing separated the
  kinds. Triaged against the resolved files, **29 are DRIFTED** (the cited symbol
  is in the cited file, on another line — `knowledge.clj:27` cited for
  `page-status` points at a blank line while the defn is at 32;
  `preflight.clj:212` cited for `outbound-sites` points at a comment while the
  defn is at 315) and **2 are UNSUPPORTED** (both
  `coordinate-discipline.md:316,331` cite `src/bb_agent/coordinates.clj:169` for
  the string `:ambiguous n`, which appears nowhere in the file — a prose citation
  of a report format, not of a symbol). One label over two kinds made 29
  one-edit fixes read as 31 possible false claims: the armory shape
  (`PASS — 28555 valid` over unexamined cards) pointed at our own corpus, and the
  second false-DIRTY in this lane after `bb knowledge guards` on 2026-11-03.

  No filter was added for the prose-shaped case. A heuristic deciding which
  citations \"are really\" symbol claims would be silently wrong sometimes —
  the same refusal as meta-citation exclusion in page-fires (2026-10-13).

  Report-only, and deliberately not fail-closed: claudemy REFUSES output citing
  an unread page and we refused that refusal on 2026-10-14, because our honest
  receipts cite /tmp paths and a guard that rejects truthful provenance is worse
  than a report that names it."
  [rows]
  (doseq [cl rows]
    (println (str "  " (if (= :drifted (:kind cl)) "LINE DRIFTED   " "UNSUPPORTED    ")
                  (:source cl) ":" (:page-line cl)
                  " -> " (:path cl) ":" (:line cl) " is cited for `" (:sym cl) "`"
                  " but the line says: " (:text cl)
                  (if (= :drifted (:kind cl))
                    (str " — the symbol IS in that file at line "
                         (str/join ", " (take 6 (:elsewhere cl)))
                         ", so this is a stale number, not a false claim")
                    (str " — and the symbol appears nowhere in that file, so the"
                         " claim is unsupported")))))
  (let [drifted (count (filter #(= :drifted (:kind %)) rows))
        unsupported (- (count rows) drifted)]
    (println (str "claims: " (count rows) " `path:LINE` — `symbol` cite(s) whose"
                  " line does not hold the symbol — " drifted " drifted, "
                  unsupported " unsupported. A drifted row names the line the"
                  " symbol IS on, so its fix is one re-pin; an unsupported one"
                  " says the symbol appears nowhere in that file. Only that shape"
                  " is checked: prose numbers, quoted text and commit shas are"
                  " not, because a regex cannot tell which of our digits are"
                  " results."))))

;; -------------------------------------------------------------------- audit

(defn audit
  "Measure every coordinate asserted in `dir`'s markdown against the files
   indexed under `index-dirs`. Returns
   {:total int :resolved [row] :dangling [row] :external [row]
    :out-of-range [row]} — a partition, so no assertion is counted twice.
   A row is {:source :path :line :end :resolved :base :lines} plus
   :external true when the cited extension is not in `corpus-extensions`.

   A dangling coordinate is a claim with nothing behind it; an out-of-range
   one names a line past EOF, which is what a citation looks like after the
   file it pointed at grew or shrank. Neither is an error — both are facts
   the curator reads."
  ([dir index-dirs] (audit dir index-dirs {}))
  ([dir index-dirs {:keys [root]}]
   (let [indexed (index-files index-dirs)
         by-base (group-by #(fs/file-name (fs/path %)) indexed)
         pages (md-pages dir)
         rows (for [page pages
                    :let [rel (if root (str/replace page (str root "/") "") page)]
                    line (str/split-lines (slurp page))
                    c (coords-in line)]
                {:source rel :path (:path c) :line (:line c) :end (:end c)
                 :base (fs/file-name (fs/path (:path c)))})
         classified (mapv (fn [r]
                            (let [cited (:path r)
                                  ;; An absolute cite is read where it points.
                                  ;; Basename-matching one lands on whichever
                                  ;; same-named file sorts first in the index,
                                  ;; and then the audit ASSERTS about that file:
                                  ;; 2026-10-18, improvements.md cited
                                  ;; goals/make-rsi-do-this-983334/act.sh:24
                                  ;; (93 lines, the test:bb-agent gate on line
                                  ;; 24) and the report printed OUT OF RANGE
                                  ;; against a 22-line probe fixture under
                                  ;; .bin/ — the first of 33 act.sh files.
                                  direct (when (str/starts-with? cited "/")
                                           (let [q (fs/path cited)]
                                             (when (fs/regular-file? q) (str q))))
                                  f (or direct (resolve-one cited indexed by-base))
                                  n (when f (count (str/split-lines (slurp f))))
                                  ext (fs/extension (fs/path cited))
                                  ;; A resolution is a GUESS when nothing
                                  ;; pinned it: not absolute, no unique
                                  ;; suffix match, and several files share
                                  ;; the basename. The row says how many it
                                  ;; chose between, because a verdict taken
                                  ;; against the wrong same-named file reads
                                  ;; exactly like one taken against the right
                                  ;; file. 2026-10-18: three OUT OF RANGE
                                  ;; rows from a bare `act.sh:24` mention in
                                  ;; this scout's own prose, 33 candidates.
                                  ;; Which set decided, and did it have a
                                  ;; choice? A bare basename matches every
                                  ;; file with that name through BOTH the
                                  ;; suffix rule and the basename rule, so
                                  ;; "the suffix rule fired" is not the same
                                  ;; as "one file was pinned".
                                  suff (filterv #(str/ends-with? % (str "/" cited)) indexed)
                                  cands (cond (= 1 (count suff)) 1
                                              (> (count suff) 1) (count suff)
                                              :else (count (get by-base (fs/file-name (fs/path cited)) [])))
                                  guess? (and (nil? direct) (> cands 1))]
                              (cond-> (assoc r :resolved f :lines n)
                                guess?
                                (assoc :ambiguous cands)
                                (and f n (> (or (:end r) (:line r)) n))
                                (assoc :out-of-range true)
                                (and (nil? f) (not (contains? corpus-extensions ext)))
                                (assoc :external true)
                                ;; 2026-11-04: a cite this audit can never
                                ;; decide is its own bucket. DANGLING means
                                ;; OUR hygiene failed; another repo's file is
                                ;; a convention violation instead (cite a URL,
                                ;; not a bare path:line).
                                (and (nil? f) (undecidable-cite? cited))
                                (assoc :undecidable true))))
                          rows)]
     {:total (count classified)
      :resolved (filterv #(and (:resolved %) (not (:out-of-range %))) classified)
      :dangling (filterv #(and (nil? (:resolved %)) (not (:external %))
                               (not (:undecidable %)))
                         classified)
      :external (filterv :external classified)
      ;; Buckets must be MUTUALLY EXCLUSIVE or the partition over-counts.
      ;; Measured live 2026-11-04 with the naive filter: 167 + 41 + 106 + 23
      ;; + 30 = 367 against a :total of 365 — two rows counted twice, because
      ;; a cite like `Memory/foo.py:11` is BOTH :external (a .py this corpus
      ;; does not contain) and :undecidable (an uppercase first segment). A
      ;; partition that does not sum is the false-clean shape this file exists
      ;; to report rather than emit, so :external keeps those rows (that is
      ;; the older, wider reading other pages already cite) and the row still
      ;; carries :undecidable true for anyone reading the data.
      :undecidable (filterv #(and (:undecidable %) (not (:external %))) classified)
      :out-of-range (filterv :out-of-range classified)
      :claims (unsupported-claims dir index-dirs {:root root})})))

;; ------------------------------------------------------------------ externals
;; 2026-11-04, closing the act:code filed 2026-11-03 whose goal
;; (bb-coords-externals-list-489058) errored on a provider timeout after 25
;; rounds. Source, level 2, read this turn: Shambhavagrawal/rotless
;; skills/rotless-skill-modernizer/scripts/keeper.py:72 — a controlled verdict
;; vocabulary (FAIL / CHANGED / LEARN / NEW / GAP / WARN / INFO / OK / BASELINE)
;; — and :107, where `check` rescans the prose and lists every reference the
;; manifest does not track, with file:line. An untracked claim is a finding, not
;; silence.
;;
;; The gap, measured rather than argued: `audit` above classifies a cite whose
;; extension this corpus does not contain as EXTERNAL and then declines to look
;; outward at all — its own negative scope says "another repo's files, which this
;; audit cannot see at all". Today that is 105 external coordinates. This corpus
;; cites other repositories constantly, so a page stolen from aden-hive/hive on
;; 2026-10-14 could cite a repo since renamed, archived or emptied and nothing
;; would ever say so.
;;
;; Report-only, and the DEFAULT state is `not-checked`: this prints what the
;; corpus cites and says plainly that it fetched nothing. Only `--fetch` spends
;; network calls, and it reports OK | FAIL <status> | CHANGED <sha>, never
;; silently substituting one for another. Exit 0 in every case — a gate that
;; refused a ledger append over an unresolvable coordinate would refuse our own
;; honest /tmp receipts, the claudemy refusal we refused on 2026-10-14.

(def url-re
  "A GitHub or raw.githubusercontent URL in prose. Deliberately narrow: a URL
   this cannot parse is simply not counted, and the report prints how many it
   found rather than implying it found every citation in the corpus.

   The terminator set is punctuation AND markup, measured rather than guessed
   (2026-11-05, first real --fetch): a trailing comma inside the class made
   `https://github.com/chillax97/edu4self,` a 404 about a repository that
   exists, and a `<` inside it made the placeholder
   `https://github.com/owner/repo/blob/<ref>` an `Illegal character in path`
   FAIL. Three of 17 rows named somebody else's repository for our own quoting,
   which is the false-verdict shape this file exists to report rather than
   emit."
  #"(https?://(?:raw\.githubusercontent\.com|github\.com)/[^\s\)\]\"'`><,;]+)")

(defn external-citations
  "Every external URL cited by the markdown under `dir`, as
   [{:source :url :kind}] where :kind is :raw (a file whose bytes mean something)
   or :page (a repo/blob URL — fetchable, but the bytes are HTML, so a hash
   proves little about the file it renders). Sorted for a stable report;
   `.bak-*` snapshots excluded, because md-pages already excludes them.

   The unit is a CITATION, not a URL: one URL cited by four pages is four rows,
   because four pages would each need fixing. That is the self-amplification this
   lane has measured twice (page-fires 2026-10-13, bb coords 2026-10-14) and it
   is stated rather than deduped — deduping would hide which pages carry a dead
   link."
  [dir]
  (mapcat
   (fn [page]
     (let [text (slurp page)]
       (for [m (re-seq url-re text)
             :let [url (second m)]]
         {:source page :url url
          :kind (if (str/includes? url "raw.githubusercontent.com") :raw :page)})))
   (md-pages dir)))

(defn externals-report
  "Group citations for printing: counts by kind, distinct URLs, and per-URL the
   pages that cite it. No network. Every row starts at :not-checked, because a
   default of `ok` would be a verdict nobody earned."
  [cites]
  (let [by-url (group-by :url cites)]
    {:citations (count cites)
     :distinct-urls (count by-url)
     :raw (count (filterv #(= :raw (:kind %)) cites))
     :page (count (filterv #(= :page (:kind %)) cites))
     :fetched 0
     :by-url (->> by-url
                  (map (fn [[url rows]]
                         {:url url
                          :kind (:kind (first rows))
                          :cited-by (count rows)
                          :sources (->> rows (map :source) distinct sort vec)
                          :status :not-checked}))
                  (sort-by :url)
                  vec)}))

(defn- sha1-hex
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        bs (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) bs))))

(defn default-fetch
  "One URL -> {:http :body}, or {:http nil :error ...}. Never throws: an
   unreachable host is a FAIL verdict with a reason, not an exception that
   aborts the report halfway and leaves the reader guessing which URLs were
   never attempted.

   Transport is babashka.http-client — the same seam bb-agent.rsi-learn uses —
   because the java.net.URLConnection version of this function NEVER reached a
   socket. Measured 2026-11-05 on the first real `bb coords externals --fetch`
   since the report landed on 2026-11-04: **146 of 146 URLs FAIL**, every one
   with the identical reason `Method setConnectTimeout on class
   sun.net.www.protocol.https.HttpsURLConnectionImpl not allowed!`. This
   runtime's reflector forbids those setters, so each row failed inside our own
   code before any connection was attempted — and printed as a verdict about
   somebody else's repository. A refused connection is a fact about the network;
   a forbidden method is a bug in ours, and the two must never look the same.
   2026-11-04's own not-checked line named exactly this hole (`no --fetch run
   was made against the network`); running it is what found it. One HTTP client
   in a repo beats two, and the second one was the one that did not work."
  [url]
  (try
    (let [r (http/get url {:throw false
                           :timeout 15000
                           :headers {"user-agent" "theseus-coords/1.0"}})]
      {:http (:status r) :body (:body r)})
    (catch Exception e
      {:http nil :body nil :error (str (class e) ": " (.getMessage e))})))

(defn check-external-url
  "Fetch ONE url -> {:status :ok|:fail :http :sha :bytes} or
   {:status :fail :reason}. `fetch-fn` is injectable so tests never touch the
   network — the same seam rsi-learn's readme fetch uses. A 200 carries a sha1 of
   the body, so a later run can say CHANGED instead of only OK."
  ([url] (check-external-url url default-fetch))
  ([url fetch-fn]
   (let [r (fetch-fn url)]
     (cond
       (nil? (:http r))
       {:status :fail :http nil
        :reason (or (:error r) "no response — not-found, not zero")}

       (= 200 (:http r))
       {:status :ok :http 200 :bytes (count (str (:body r)))
        :sha (when (:body r) (sha1-hex (:body r)))}

       :else
       {:status :fail :http (:http r) :reason (str "HTTP " (:http r))}))))

(defn fetch-externals
  "Resolve every distinct URL in a report. Returns the report with each row
   carrying :status :ok|:fail plus :http/:sha/:reason, and :fetched set to how
   many rows were actually attempted — a count over part of the input reads
   exactly like a count over all of it, which is the false-clean this lane keeps
   meeting (armory's `PASS — 28555 valid` over cards whose eval_score was null)."
  ([rep] (fetch-externals rep default-fetch))
  ([rep fetch-fn]
   (let [checked (mapv #(merge % (check-external-url (:url %) fetch-fn))
                       (:by-url rep))]
     (assoc rep :by-url checked :fetched (count checked)))))

(defn externals-print!
  "The human receipt: every row with its status, then what this did NOT check.
   The negative scope is part of the receipt, not a footer."
  [rep]
  (println (str "external citations: " (:citations rep) " cite(s) | "
                (:distinct-urls rep) " distinct URL(s) | "
                (:raw rep) " raw-file, " (:page rep) " repo/blob page | "
                "fetched: " (:fetched rep) " of " (:distinct-urls rep)
                (when (zero? (:fetched rep))
                  " — every status below is not-checked, which is a state, not a pass")))
  (doseq [row (:by-url rep)]
    (println (str "  " (name (:status row))
                  (when (:http row) (str "  HTTP " (:http row)))
                  (when (:sha row) (str "  sha1:" (subs (:sha row) 0 12)))
                  (when (:reason row) (str "  " (:reason row)))
                  "  " (:url row)
                  "  <- " (str/join ", "
                                    (mapv #(str (fs/file-name (fs/path %)))
                                          (take 3 (:sources row))))
                  (when (> (:cited-by row) 3)
                    (str " (+" (- (:cited-by row) 3) " more)")))))
  (println (str "not checked: whether a fetched page still says what the ledger "
                "quoted (a 200 proves the URL resolves, not that the claim "
                "holds); commit shas; hosts other than github.com and "
                "raw.githubusercontent.com; whether a repo/blob URL's HTML bytes "
                "mean anything about the file it renders"
                (when (zero? (:fetched rep))
                  (str "; NOTHING was fetched — `--fetch` is what turns "
                       "not-checked into OK or FAIL"))
                ". Report only — no page was edited and no citation was rewritten.")))


;; ---------------------------------------------------------------------- cli
;; The seam — a measurement no boundary calls measures nothing, which is
;; exactly how preflight.clj landed unwired on 2026-10-12. Report-only:
;; prints, never edits. Exit 0 even when coordinates dangle (see the ns
;; docstring: refusing a truthful /tmp receipt would be the worse failure).

(defn- report!
  [r]
  (println (str "coordinates: " (:total r) " asserted | "
                (count (:resolved r)) " resolved | "
                "dangling: " (count (:dangling r)) " | "
                "external: " (count (:external r)) " | "
                "undecidable: " (count (:undecidable r)) " | "
                "out of range: " (count (:out-of-range r))))
  (when (zero? (:total r))
    ;; bug-ops/zeph's check_integrity_audit.sh treats zero matches as FATAL,
    ;; "the pattern is broken (pattern rot)", and its own history says why: the
    ;; proposed pattern missed session_resume.rs (it calls ReplayEngine::fold, not
    ;; ::replay) and over-matched 40+ files in a different trust domain. A scan
    ;; that can go quiet is a scan that can only ever look green — so a zero here
    ;; is reported as UNVERIFIED, never as a clean bill. Exit stays 0: this is a
    ;; hand-run report over a corpus that may legitimately be empty, not a CI gate
    ;; over a known non-empty set (zeph's is, which is why theirs is FATAL)."
    (println (str "  [UNVERIFIED] 0 coordinates asserted — either this corpus "
                  "cites nothing, or coord-re / the .md walk has rotted. A zero "
                  "here is not evidence of a clean corpus.")))
  (doseq [d (:dangling r)]
    (println (str "  DANGLING    " (:source d) " -> " (:path d) ":" (:line d)
                  "  (no file on disk with that name)"
                  (when (:ambiguous d)
                    (str "; basename matched " (:ambiguous d)
                         " files — took the first, so this verdict is a guess")))))
  (doseq [u (:undecidable r)]
    (println (str "  UNDECIDABLE " (:source u) " -> " (:path u) ":" (:line u)
                  "  (another repo's file — a cite to another repository's file"
                  " is a URL, never a bare path:line, so this row is a"
                  " convention violation, not a broken claim)")))
  (doseq [e (:external r)]
    (println (str "  EXTERNAL    " (:source e) " -> " (:path e) ":" (:line e)
                  "  (another repo's file — unverifiable here, NOT checked)")))
  (doseq [o (:out-of-range r)]
    (println (str "  OUT OF RANGE " (:source o) " -> " (:path o) ":" (:line o)
                  (when (:end o) (str "-" (:end o)))
                  "  (file has " (:lines o) " lines)"
                  (when (:ambiguous o)
                    (str "; basename matched " (:ambiguous o)
                         " files — took the first, so this verdict is a guess")))))
  (claims-print! (:claims r))
  (println (str "not checked: whether a resolved coordinate still holds the claim it "
                "supports (only that the file and line exist); URLs and commit shas; "
                ".bak-* snapshots; ambiguous RELATIVE basenames resolved to the "
                "first match, while an absolute cite is read where it points; "
                (count (:external r)) " external coordinate(s) and "
                (count (:undecidable r)) " undecidable one(s) — another repo's"
                " files, which this audit cannot see at all, so they are neither"
                " resolved nor broken. An UNDECIDABLE row is a convention"
                " violation (cite a URL, not a bare path:line), NOT proof our"
                " corpus is broken; a DANGLING row is ours. "
                "Report only — nothing was edited.")))

(defn -main
  "Usage: bb coords [brain-dir] [--index dir]...
         bb coords externals [brain-dir] [--fetch]
   Defaults: $THESEUS_HOME/brain, indexed against $THESEUS_HOME and
   $THESEUS_HOME/theseus. Exit 0 = report printed, 2 = usage or a missing
   path. Never mutates."
  [args]
  (let [home (or (System/getenv "THESEUS_HOME")
                 (str (fs/path (System/getProperty "user.home") "theseus")))
        ;; bare `bb coords` means "audit the default brain" — a tool that
        ;; demands its own name as the first argument is a tool nobody runs.
        ;; And a first argument that IS a directory means the same thing with a
        ;; named corpus: the usage line has always advertised
        ;; `bb coords [brain-dir]`, but reading argv[0] as the subcommand made
        ;; the one invocation a human would type print usage and exit 2. Found by
        ;; proven-check on 2026-10-19 (pointing the audit at an empty dir to
        ;; prove the UNVERIFIED line prints), not by review.
        args* (if (and (seq args) (fs/directory? (fs/path (first args))))
                (cons "coords" args)
                args)
        [cmd & rest-args] (if (empty? args*) ["coords"] args*)
        idx-flag? #(= "--index" %)
        ;; split: everything before the first --index is the brain dir(s);
        ;; everything after each --index is an index dir.
        positional (take-while (complement idx-flag?) rest-args)
        index-dirs (->> rest-args (drop-while (complement idx-flag?)) rest)
        dir (or (first positional) (str (fs/path home "brain")))
        indexes (or (seq index-dirs) [home (str (fs/path home "theseus"))])
        missing (first (remove #(fs/exists? (fs/path (str %))) (cons dir indexes)))]
    (cond
      ;; 2026-11-04: the second report. `audit` counts external coordinates and
      ;; then declines to look at them; this lists them with `not-checked` as
      ;; the default state, and --fetch as the only path that spends network.
      (= cmd "externals")
      (let [fetch? (some #{"--fetch"} rest-args)
            pos (vec (remove #{"--fetch"} positional))
            bad (first (remove #(fs/exists? (fs/path (str %))) pos))]
        (if bad
          (do (println "coords externals: no such path —" bad) 2)
          (do (-> (or (first pos) dir)
                  external-citations
                  externals-report
                  ((fn [rep] (if fetch? (fetch-externals rep) rep)))
                  externals-print!)
              0)))

      (not= cmd "coords")
      (do (println "usage: bb coords [brain-dir] [--index dir]... | bb coords externals [brain-dir] [--fetch]") 2)

      missing
      (do (println "coords: no such path —" missing) 2)

      :else
      (do (report! (audit dir indexes {:root (str home)})) 0))))
