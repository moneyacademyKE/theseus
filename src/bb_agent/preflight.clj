(ns bb-agent.preflight
  "Content inspection at the boundary where a generated file leaves the repo.

   deliverables-upload.md mandates that every `.md` we generate is sent to the
   chat in the same turn. That boundary is walked daily and, until now, nothing
   looked at what crossed it. This namespace looks.

   Shape is deliberate (Hickey): pattern table as data, one pure scan function,
   verdict as a function of findings. No framework, no config file, no state.
   Findings are MASKED — a guard that echoes the credential it caught into the
   terminal (and into whatever log captures the run) leaks what it guards. The
   source it was adapted from does exactly that; we do not.

   Source: gnokit/hkdata `scripts/public-preflight.sh` —
   https://github.com/gnokit/hkdata (2026-10-12 digest). Its credential and
   personal-data pattern families are reused; its push-hook wiring, PII-scope
   policy and raw-hit printing are not.

   Rule-liveness source: CodePrometheus/chopin-ai
   packages/core/src/layer/gates.ts:40-49 —
   https://github.com/CodePrometheus/chopin-ai (2026-10-21 digest): “a gate on
   a tool nobody can satisfy would refuse forever, so it is an error rather
   than an ordinary refusal”. Adapted: a pattern nobody has proven can fire is
   a configuration error, not a guard. Companion rule from
   alkanfel1987/pantheon-seed memory/pattern-promotion.md:264-283 — a zero fire
   count proves nothing until a probe discriminates.

   Discrimination source: 0xSneaks/SUSHILOOP core/evolve.py:20-29 —
   https://github.com/0xSneaks/SUSHILOOP (2026-10-31 digest): discrimination
   'measures whether the skill distinguishes input it should flag from input
   it shouldn't'. Adapted as `benign-samples` + `:overbroad?`; its numeric
   thresholds are not imported. Its run_cycle.py:39-47 exit-code rule (only
   ERROR is a failure — a gate that correctly refused is the system working)
   is the same shape as our 2026-10-16 `verified-empty?` fix, so no change
   was needed here."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private private-key-re
  "Built by concatenation so this file does not contain a literal that matches
   its own rule — a guard that flags its own source gets commented out."
  (re-pattern (str "-----" "BEGIN [A-Z ]*PRIVATE KEY" "-----")))

(def ^:private personal-email-re
  (re-pattern (str "[A-Za-z0-9._%+-]+@(gmail|googlemail|hotmail|outlook"
                   "|icloud|yahoo|qq|163|126)\\.[A-Za-z]{2,}")))

(def credential-rules
  "Kind :credential, severity :block. Names are for humans reading a finding."
  [["anthropic-or-openai-key" #"\bsk-[A-Za-z0-9][A-Za-z0-9_-]{15,}"]
   ["github-token-classic"    #"ghp_[A-Za-z0-9]{20,}"]
   ["github-token-fine"       #"github_pat_[A-Za-z0-9_]{20,}"]
   ["slack-token"             #"xox[baprs]-[A-Za-z0-9-]{10,}"]
   ["aws-access-key-id"       #"AKIA[0-9A-Z]{16}"]
   ["google-api-key"          #"AIza[0-9A-Za-z_-]{30,}"]
   ["telegram-bot-token"      #"\b[0-9]{8,}:AA[A-Za-z0-9_-]{20,}"]
   ["private-key-block"       private-key-re]])

(def personal-data-rules
  "Kind :personal-data, severity :warn by default — a home path in a report is
   usually an honest coordinate, not a leak. `--strict` promotes warn to fail."
  [["home-directory-path" #"/Users/[A-Za-z0-9._-]+"]
   ["consumer-email"      personal-email-re]
   ["phone-number"        #"\+?[0-9]{1,3}[ -][0-9]{3,4}[ -][0-9]{4}"]])

(def rule-samples
  "One synthetic probe per rule: the minimum text that MUST trip it.

   Why this table exists, measured 2026-10-21: preflight declared eleven rules
   and the suite proved FOUR could fire (`sk-…`, `ghp_…`, `AKIA…`, `/Users/…`).
   The other seven had no fixture anywhere under test/, so a typo in any of them
   left the suite green and the outbound wire unguarded — zeph's pattern rot and
   pantheon-seed's silent interceptor, sitting on the guard that inspects secrets
   leaving the repo. A rule is now live only when its own probe fires it.

   Every sample is built by CONCATENATION so this file never contains a literal
   that matches its own table — the same trick as private-key-re above, and
   pinned by `the-rule-table-does-not-match-its-own-source` in the suite. A guard
   that flags its own source is a guard that gets commented out."
  {"anthropic-or-openai-key" (str "sk-" "AbCdEf0123456789wxyz")
   "github-token-classic"    (str "ghp_" "AbCdEf0123456789wxyz")
   "github-token-fine"       (str "github_pat_" "AbCdEf0123456789wxyz")
   "slack-token"             (str "xox" "b-AbCdEf0123456789wxyz")
   "aws-access-key-id"       (str "AKI" "AABCDEFGH01234567")
   "google-api-key"          (str "AIza" "AbCdEf0123456789wxyzAbCdEf0123456789wxyz")
   "telegram-bot-token"      (str "123456789" ":AA"
                                   "BcDeF0123456789wxyzAbCdEf01234")
   "private-key-block"       (str "-----" "BEGIN RSA PRIVATE KEY" "-----")
   "home-directory-path"     (str "/Use" "rs/moe/theseus")
   "consumer-email"          (str "someone@" "gmail" ".com")
   "phone-number"            (str "+1 55" "5 1234")})

(def benign-samples
  "One BENIGN probe per rule: honest prose carrying no secret of that kind,
   which must therefore stay quiet. The other half of discrimination.

   Why this table exists, measured 2026-10-31: `rule-samples` proves a rule
   CAN fire; nothing proved it fires on the right things. Both halves of the
   defect were measured on this lane's own artifacts:

     * FAILING CLOSED ON HONESTY — `bb preflight` over a recommendations file
       that quoted an ISO-ish digest filename printed
       `BLOCK …:27 [credential] telegram-bot-token → 1234…60-char, masked`
       and `--strict` exited 1, for a file carrying no secret at all. The same
       false block reproduced on `brain/improvements.md:813`.
     * FAILING OPEN ON THE REAL SHAPE — state/rsi/report/2026-10-13.md §6
       recorded the credential rule passing a real-shaped hyphenated provider
       key while blocking documentation that quoted a synthetic fixture,
       flagged 'Not applied — this lane edits nothing'. Eighteen days later
       this lane edits it, both directions at once.

   Scope decision, stated because it is a decision: benign probes are demanded
   of severity :block rules only. A :warn personal-data rule firing on an
   honest coordinate is the documented intent (2026-10-12: personal data never
   blocks a send), so marking it overbroad would be a false finding — the same
   mistake as refusing a truthful receipt.

   Source: 0xSneaks/SUSHILOOP core/evolve.py:20-29 — discrimination 'measures
   whether the skill distinguishes input it should flag from input it
   shouldn't; 2 = keyword-matching theater, 5 = real signal'. Its numeric
   thresholds (MIN_DISCRIMINATION 5/10, MIN_BEHAVIORAL_TOTAL 18/30) are NOT
   imported: they are their scorer's numbers on their corpus, and importing a
   threshold measured on nobody's run is the laundering
   mechanical-confidence-supersede.md refuses.

   Built by concatenation where a prefix could otherwise match the table
   itself, same as `rule-samples`."
  {"anthropic-or-openai-key" "the provider token is read from config at call
                             time; vendor docs show sk-ant-abc123 shapes only"
   "github-token-classic"    "classic tokens carry the ghp_ prefix and 36
                             characters; this receipt quotes none"
   "github-token-fine"       "fine-grained tokens begin github_pat_ and are
                             not present in this file"
   "slack-token"             "the workspace token lives in config; nothing
                             below resembles xoxb or xoxp"
   "aws-access-key-id"       "aws key ids are 20 characters beginning AKIA;
                             this receipt quotes none"
   "google-api-key"          "google api keys begin AIza and run 39
                             characters; none are quoted below"
   "telegram-bot-token"      "bot tokens are digits, a colon, then 35 chars -
                             digest 1761926789:2026-10-31T00-00-00Z.md"
   "private-key-block"       "no PEM material here; such a block would begin
                             with five dashes, BEGIN, then the key type"
   "home-directory-path"     "the receipt is written under the repo root and
                             quoted relative to it"
   "consumer-email"          "the farm leaked two aliases of the shape
                             302289190+x@users.noreply.github.com"
   "phone-number"            "window 2026-10-24 to 2026-10-31, 3 days,
                             n=7 repos, exit code 0"})

(defn- mask
  "Prefix + ellipsis + length. Enough to find the line again, not enough to
   replay the secret."
  [s]
  (let [n (count s)]
    (str (subs s 0 (min 4 n)) "…" (max 0 (- n 4)) "-char, masked")))

(defn- scan-text
  "Findings for one blob of text. Pure: text in, data out."
  [text file]
  (let [lines (str/split-lines text)
        scan-rules (fn [rules kind severity]
                     (for [[li line] (map-indexed vector lines)
                           [name re] rules
                           :let [raw (re-find re line)
                           m (if (vector? raw) (first raw) raw)]
                           :when m]
                       {:file file :line (inc li) :rule name :kind kind
                        :severity severity :text (mask m) :masked (mask m)}))]
    (concat (scan-rules credential-rules :credential :block)
            (scan-rules personal-data-rules :personal-data :warn))))

(defn scan-file
  "Findings for one path. Fails closed: a missing file throws rather than
  reporting clean — silence is not a pass."
  [path]
  (when-not (fs/regular-file? (fs/path path))
    (throw (ex-info (str "no such file: " path) {:path path})))
  (scan-text (slurp path) path))

(defn scan
  "Findings across many paths; the first missing path fails the whole run."
  [paths]
  (mapcat scan-file paths))

(defn verdict
  "Pure decision: :fail on any :block, or on any :warn when strict. :pass else."
  [findings {:keys [strict?]}]
  (let [sev (set (map :severity findings))]
    (if (or (sev :block) (and strict? (sev :warn))) :fail :pass)))

(defn report
  "Human-readable lines. The negative-scope line is not decoration: a PASS here
   means 'no pattern in this table matched', never 'this file is safe'."
  [findings paths]
  (let [by-sev (fn [s] (filterv #(= s (:severity %)) findings))]
    (concat
     (map (fn [f]
            (format "%s %s:%s  [%s] %s → %s"
                    (if (= :block (:severity f)) "BLOCK" "WARN ")
                    (fs/file-name (fs/path (:file f))) (:line f)
                    (name (:kind f)) (:rule f) (:text f)))
          findings)
     [(format "preflight: %d file(s), %d block, %d warn"
              (count paths) (count (by-sev :block)) (count (by-sev :warn)))
      "not-checked: pattern-match only — no entropy analysis, no binary or"
      "  non-UTF8 payload inspection, no network/egress check, no judgement about"
      "  whether disclosed content is sensitive-but-unpatterned. PASS ≠ safe."])))

;; ---------------------------------------------------------------------
;; Outbound-path parity — 2026-10-19
;;
;; Stolen from evarness-ai/evarness (IRIS),
;; docs/architecture/deterministic-path-parity.md, whose root cause is one
;; sentence: "governance is attached to *model calls* and to the
;; generated-answer stage, while the harness's own unit of work is the *turn*,
;; and a turn can be answered without either."
;;
;; Our identical hole, measured: preflight was wired into
;; bb-agent.telegram-upload/send-file! on 2026-10-12, while scripts/goal_watch.bb
;; and scripts/send_document.bb put files on the SAME Telegram wire with curl and
;; never met the guard. That was recorded as not-checked on 10-12 and left there
;; for seven days. The unit of work is "a file leaves for Telegram", not
;; "send-file! was called" — so the guard moves here, where every path can reach
;; it, and the paths are enumerated instead of remembered.
;;
;; Two rules from the sources are load-bearing:
;;   * IRIS tests/security/test_no_bypass.py, "It could not count": sites were
;;     collected into a SET keyed by file:scope, so a second ungoverned call
;;     inside an already-governed method produced a label already present and
;;     changed nothing — the gate stayed green over a real hole. Sites here are
;;     ROWS, one per line, and the count is pinned in a test.
;;   * bug-ops/zeph .github/scripts/check_integrity_audit.sh exits FATAL when its
;;     scan finds zero matches, because "the pattern is broken (pattern rot)".
;;     A gate that can go quiet is a gate that can only ever look green, so the
;;     test suite asserts this scan is not vacuous.

(def text-extensions
  "Payload kinds worth reading before they leave the repo. Binary and unknown
   extensions are SKIPPED, which is reported as skipped — never as clean."
  #{"md" "markdown" "txt" "text" "clj" "cljs" "cljc" "edn" "json" "csv"
    "tsv" "log" "yaml" "yml" "toml" "ini" "sh" "bb" "sql" "html" "htm"
    "xml" "js" "ts" "py" "rb" "go" "rs" "java" "css" "env" "conf" "cfg"})

(def max-inspect-bytes (* 2 1024 1024))

(defn inspect-outbound!
  "Fail closed on credentials in a text payload about to leave the repo.
   Returns :pass, or :skipped (unchecked — never clean), or throws
   {:preflight/blocked true} merged with `tag` so the calling transport
   recognises its own refusal. Findings are masked: a guard that echoes the
   credential it caught into a log leaks what it guards."
  ([path] (inspect-outbound! path nil))
  ([path tag]
   (let [p (str path)
         ext (some-> (fs/extension (fs/path p)) str/lower-case)]
     (cond
       (not (contains? text-extensions ext)) :skipped
       (> (fs/size (fs/path p)) max-inspect-bytes) :skipped
       :else
       (let [found (scan-file p)
             blocked (filterv #(= :block (:severity %)) found)]
         (if (= :fail (verdict found {}))
           (throw (ex-info (str "outbound file refused by preflight: "
                                (count blocked) " credential pattern(s) in "
                                (fs/file-name (fs/path p))
                                " - masked here by design; run `bb preflight " p
                                "` to locate them")
                           (merge (or tag {})
                                  {:preflight/blocked true
                                   :preflight/path p
                                   :preflight/rules (vec (distinct (map :rule blocked)))
                                   :preflight/lines (vec (map :line blocked))
                                   :preflight/findings blocked})))
           :pass))))))

(def outbound-endpoints
  "Telegram Bot API methods that put a FILE on the wire. sendMessage carries text
   and is deliberately out of scope for a file guard — naming that is the
   negative scope, not an omission."
  ["sendDocument" "sendPhoto"])

(def outbound-guard-marks
  "What counts as 'this file routes through the guarded door'."
  ["inspect-outbound!" "send-file!"])

(def outbound-scan-roots ["src" "scripts"])
(def outbound-scan-exts #{"clj" "cljc" "cljs" "bb" "sh"})
(def outbound-skip-re
  "Tests and snapshots are not live paths: a test's fake curl is a fixture, and
   yesterday's .bak is a copy."
  #"(^|/)(test|tests)/|\.bak-")

(defn- outbound-files
  [root]
  (let [base (fs/path root)]
    (->> (for [r outbound-scan-roots
               :let [d (fs/path base r)]
               :when (fs/exists? d)
               ;; "**", not "**/*": the latter requires at least one
               ;; directory level, so it silently skips every file sitting
               ;; directly in the root — which is ALL of scripts/. Measured
               ;; 2026-10-19: `**/*` over scripts/ returned [] while the
               ;; directory held two live send paths. Directories come back too
               ;; and the regular-file? test drops them.
               p (fs/glob d "**")
               :when (and (fs/regular-file? p)
                          (contains? outbound-scan-exts
                                     (some-> (fs/extension p) str/lower-case))
                          (not (re-find outbound-skip-re (str p))))]
           p)
         (map str)
         sort)))

(defn outbound-sites
  "Every Telegram FILE-send site under `root` as data: one row per LINE, so a
   second send added inside an already-guarded file is still visible (IRIS's
   'it could not count'). :guarded? is file-level and says so — a guard in the
   wrong function of a guarded file is invisible to this scan, which is stated
   rather than implied, exactly as IRIS states what its own gate cannot see."
  [root]
  (let [base (fs/path root)]
    (->> (outbound-files root)
         (mapcat
          (fn [f]
            (let [lines (str/split-lines (slurp f))
                  body (str/join "\n" lines)
                  guarded? (boolean (some #(str/includes? body %) outbound-guard-marks))]
              (for [[i line] (map-indexed vector lines)
                    :let [hits (filterv #(str/includes? line %) outbound-endpoints)]
                    :when (seq hits)]
                {:file (str (fs/relativize base (fs/path f)))
                 :line (inc i)
                 :text (str/trim line)
                 :endpoints hits
                 :guarded? guarded?}))))
         (sort-by (juxt :file :line))
         vec)))

(defn sites-report
  "Human-readable parity report. The negative scope is part of the answer."
  [rows root]
  (let [loose (filterv (complement :guarded?) rows)]
    (concat
     (map (fn [r]
            (format "%s %s:%s  %s"
                    (if (:guarded? r) "guarded  " "UNGUARDED")
                    (:file r) (:line r) (str/join "," (:endpoints r))))
          rows)
     [(format "outbound parity: %d site(s) in %d file(s) under %s of %s — %d unguarded"
              (count rows)
              (count (distinct (map :file rows)))
              (str/join "+" outbound-scan-roots)
              root
              (count loose))
      (if (empty? rows)
        "[UNVERIFIED] zero sites found — either no file leaves for Telegram from
   src/+scripts/, or outbound-endpoints / the file walk has rotted. A zero here
   is not a clean bill of health (zeph's pattern-rot rule)."
        "evidence class: a static enumeration of call sites found in text, not proof that the guard runs when a file actually leaves — a heartbeat is not enforcement, and nothing here observes a send, source honey-stack doctor.py:94. not checked: guarded-ness is FILE-level, so a guard sitting in a
   different function of a guarded file passes; sendMessage-only paths (text,
   not files) are out of scope; test/ and .bak-* are never scanned; a send built
   by string concatenation that never spells the endpoint is invisible; and a
   PASS means 'no credential pattern matched', never 'this file is safe'.")])))

;; ---------------------------------------------------------------------
;; Rule liveness — 2026-10-21 (chopin-ai checkGates + pantheon-seed's probe)
;;
;; A guard that can never fire is a configuration error, not a refusal. So the
;; question is not "did the table match this file?" but "can each rule match
;; ANYTHING?", and that question gets a probe per rule plus a report a human
;; reads — the same shape as `bb coords` and `bb knowledge guards`.

(defn all-rules
  "The whole pattern table as data — name, regex, kind, severity — so a liveness
   check or a test iterates rules instead of knowing them."
  []
  (vec (concat (map (fn [[name re]]
                      {:name name :re re :kind :credential :severity :block})
                    credential-rules)
               (map (fn [[name re]]
                      {:name name :re re :kind :personal-data :severity :warn})
                    personal-data-rules))))

(defn rule-liveness
  "Per rule: can it fire, proven by its own probe?

   :status :live      — the probe produced a finding naming THIS rule;
           :no-sample — nothing in `samples` names it, so it has never been
                        proven able to fire (chopin-ai's configuration error);
           :dead      — a probe exists and does not fire the rule (pattern rot).
   :also-fired names other rules the same probe tripped, so a probe that does not
   isolate its rule says so rather than implying it proved one.

   Pure and total: rows in, rows out, never throws. A missing probe is a finding,
   not an exception — the report is the deliverable."
  ([rules samples benign]
   (mapv (fn [{:keys [name re kind severity]}]
           (let [sample (get samples name)
                 ;; This rule's own regex, applied to this rule's own probes.
                 ;; `scan-text` runs the GLOBAL tables, so it cannot see a
                 ;; caller-supplied rule: before this change an inline rule
                 ;; always read :dead and a test using one proved nothing.
                 raw-pos (when sample (re-find re sample))
                 pos (when raw-pos (if (vector? raw-pos) (first raw-pos) raw-pos))
                 hits (when sample
                        (vec (scan-text sample (str "probe:" name))))
                 fired (when pos [{:rule name :masked (mask pos)}])
                 ;; The second side: does this rule also fire on prose that
                 ;; carries no secret? Only demanded of :block rules — see
                 ;; benign-samples' scope decision. Masked, because echoing
                 ;; what tripped a credential rule would echo a credential.
                 raw-neg (when (and (= :block severity)
                                     (contains? benign name))
                           (re-find re (get benign name)))
                 neg (when raw-neg (if (vector? raw-neg) (first raw-neg) raw-neg))
                 over (when neg {:rule name :masked (mask neg)})]
             {:name name
              :kind kind
              :severity severity
              :sample? (boolean sample)
              :benign? (contains? benign name)
              :status (cond (nil? sample) :no-sample
                            (seq fired) :live
                            :else :dead)
              :overbroad? (boolean over)
              :overbroad-match (some-> over :masked)
              :also-fired (->> hits (map :rule) (remove #{name}) distinct vec)
              :matched (first fired)}))
         rules))
  ([rules samples] (rule-liveness rules samples {}))
  ([] (rule-liveness (all-rules) rule-samples benign-samples)))

(defn rotted-rules
  "Rows the report owes a human: a rule that cannot be proven to fire, OR one
   proven to fire on prose carrying no secret. Empty means every rule is
   proven on both sides — never that the rules are RIGHT, because a probe
   proves a pattern matches the shapes it was written for, not the secrets in
   the wild.

   Widened 2026-10-31: a one-sided proof read as clean, the false-clean shape
   this lane keeps meeting."
  [rows]
  (filterv #(or (not= :live (:status %)) (:overbroad? %)) rows))

(defn patterns-report
  "Human-readable liveness report. Exit 1 in the CLI when any row is not :live,
   because a rule nobody can prove fires is a hole with a name on it."
  [rows]
  (let [bad (rotted-rules rows)
        n (fn [s] (count (filterv #(= s (:status %)) rows)))]
    (concat
     (map (fn [r]
            (format "%s %-24s [%s/%s]%s%s"
                    (case (:status r)
                      :live      "live     "
                      :dead      "DEAD     "
                      :no-sample "NO-SAMPLE")
                    (:name r) (name (:kind r)) (name (:severity r))
                    (if (:overbroad? r)
                      (str "  OVERBROAD — fires on benign prose: "
                           (:overbroad-match r))
                      "")
                    (if (seq (:also-fired r))
                      (str "  (probe also trips: " (str/join ", " (:also-fired r)) ")")
                      "")))
          rows)
     [(format "preflight rules: %d declared | %d live | %d never proven | %d dead | %d overbroad"
              (count rows) (n :live) (n :no-sample) (n :dead)
              (count (filterv :overbroad? rows)))
      (if (empty? rows)
        "[UNVERIFIED] zero rules found — either the table is empty or all-rules
   has rotted. A zero here is not evidence of a clean guard (zeph's pattern-rot
   rule)."
        "not checked: a probe proves a pattern CAN match the shape it was
   written for, and a benign probe proves it stays quiet on ONE honest
   sentence. Neither proves the pattern matches the secrets that exist in the
   wild, and neither proves the guard is WIRED into the path carrying them —
   that is a different scan (`bb preflight sites`). Samples are synthetic on
   both sides.")
      (when (seq bad)
        (str "ROTTED: " (str/join ", " (map :name bad))
             " — a rule that cannot be proven to fire is a configuration"
             " error, not a refusal (chopin-ai checkGates); a rule that fires"
             " on prose carrying no secret is a guard that refuses honest"
             " receipts (SUSHILOOP discrimination)."))])))

(defn -main
  "Exit 0 pass, 1 blocked, 2 usage/missing path.
   Three modes: `bb preflight [--strict] <file>...` scans content,
   `bb preflight sites [root]` audits outbound-path parity, and
   `bb preflight patterns` proves each rule can fire — three different questions
   about our own guard, so each gets its own word rather than a flag."
  [args]
  (cond
    (= "patterns" (first args))
    (let [rows (rule-liveness)]
      (doseq [line (patterns-report rows)] (println line))
      (if (seq (rotted-rules rows)) 1 0))

    (= "sites" (first args))
    (let [root (or (second args) ".")
          rows (outbound-sites root)]
      (doseq [line (sites-report rows root)] (println line))
      (if (some (complement :guarded?) rows) 1 0))
    :else
    (let [[opts paths] (split-with #(str/starts-with? % "-") args)
          strict? (some #{"--strict"} opts)]
      (cond
        (empty? paths)
        (do (println "usage: bb preflight [--strict] <file>... | sites [root] | patterns") 2)

        (some #(not (fs/regular-file? (fs/path %))) paths)
        (do (println (str "preflight: no such file — "
                          (first (remove #(fs/regular-file? (fs/path %)) paths))))
            2)

        :else
        (let [found (vec (scan paths))]
          (doseq [line (report found paths)] (println line))
          (if (= :fail (verdict found {:strict? strict?})) 1 0))))))
