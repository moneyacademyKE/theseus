(ns bb-agent.preflight-test
  "The upload-boundary guard: a file about to leave the repo must not carry
   credentials or personal data. Red-first — written before src existed."
  (:require [babashka.fs :as fs]
            [bb-agent.preflight :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- tmp [name contents]
  (let [d (fs/create-temp-dir {:prefix "preflight-test"})
        f (fs/path d name)]
    (spit (str f) contents)
    (str f)))

(deftest scan-file-finds-credentials
  (testing "each credential family is caught and labelled"
    (let [f (tmp "leak.md"
                 (str "key: sk-abcdefghijklmnop1234\n"
                      "tok: ghp_ABCDEFGHIJKLMNOPQRSTUV12\n"
                      "aws: AKIAABCDEFGHIJKLMNOP\n"
                      "pk: -----BEGIN RSA PRIVATE KEY-----\n"))
          found (p/scan-file f)]
      (is (= 4 (count found)))
      (is (every? #(= :block (:severity %)) found))
      (is (= #{:credential} (set (map :kind found)))))))

(deftest scan-file-masks-instead-of-echoing
  (testing "the hit text never appears in the finding — a guard must not leak what it guards"
    (let [secret "sk-abcdefghijklmnop1234"
          f (tmp "leak.md" (str "export KEY=" secret "\n"))
          found (p/scan-file f)]
      (is (= 1 (count found)))
      (is (not (some #(= secret (:text %)) found)))
      (is (not (some #(and (:text %) (re-find (re-pattern secret) (:text %))) found)))
      (is (re-find #"…|…" (:masked (first found)))))))

(deftest scan-file-flags-personal-data-as-warn-not-block
  (testing "a home path is a warning by default — blocking on it would make every doc a false alarm"
    (let [f (tmp "notes.md" "built at /Users/moe/theseus/theseus today\n")
          found (p/scan-file f)]
      (is (= 1 (count found)))
      (is (= :warn (:severity (first found))))
      (is (= :personal-data (:kind (first found)))))))

(deftest clean-file-yields-nothing
  (testing "a plain report passes and says so"
    (is (empty? (p/scan-file (tmp "ok.md" "# Report\n\nNo secrets here, just prose.\n"))))))

(deftest missing-file-is-an-error-not-a-pass
  (testing "fail closed: a path that does not exist throws, it does not scan clean"
    (is (thrown? Exception (p/scan-file "/tmp/definitely-not-here-preflight.md")))))

(deftest scan-covers-many-files
  (let [a (tmp "a.md" "ghp_ABCDEFGHIJKLMNOPQRSTUV12\n")
        b (tmp "b.md" "all clear\n")
        c (tmp "c.md" "contact me at someone@gmail.com\n")]
    (testing "findings carry their file"
      (let [found (p/scan [a b c])]
        (is (= 2 (count found)))
        (is (= #{a c} (set (map :file found))))))))

(deftest verdict-is-data
  (testing "block findings fail, warn findings pass, strict promotes warn to fail"
    (let [blocked [{:severity :block}]
          warned [{:severity :warn}]]
      (is (= :fail (p/verdict blocked {})))
      (is (= :pass (p/verdict warned {})))
      (is (= :fail (p/verdict warned {:strict? true})))
      (is (= :pass (p/verdict [] {}))))))

(deftest -main-exit-codes
  (testing "clean file exits 0, credential exits 1, missing path exits 2"
    (is (= 0 (p/-main [(tmp "ok.md" "clean\n")])))
    (is (= 1 (p/-main [(tmp "bad.md" "sk-abcdefghijklmnop1234\n")])))
    (is (= 2 (p/-main ["/tmp/nope-preflight.md"])))
    (is (= 2 (p/-main [])))))

;; ---------- 2026-10-19: outbound-path parity ----------
;; Stolen from evarness-ai/evarness (IRIS):
;; docs/architecture/deterministic-path-parity.md names the root cause in one
;; sentence — "governance is attached to *model calls* and to the
;; *generated-answer stage*, while the harness's own unit of work is the *turn*,
;; and a turn can be answered without either." Our identical hole: preflight was
;; wired into bb-agent.telegram-upload/send-file! on 2026-10-12 while
;; scripts/goal_watch.bb and scripts/send_document.bb put files on the SAME wire
;; with curl and never met the guard. That was written down as not-checked on
;; 10-12 and left there for seven days. The unit of work is "a file leaves for
;; Telegram", not "send-file! was called".
;; Two rules from the sources are load-bearing here:
;;   * IRIS tests/security/test_no_bypass.py, "It could not count" — call sites
;;     collected into a SET keyed by file:scope meant a second ungoverned call
;;     inside an already-governed method changed nothing and the gate stayed
;;     green. So sites are ROWS, and the real count is pinned in a test.
;;   * bug-ops/zeph .github/scripts/check_integrity_audit.sh exits FATAL when the
;;     scan finds zero matches, "the pattern is broken (pattern rot)". A gate
;;     that can go quiet is a gate that can only ever look green.

(defn- repo-root
  "The repo this test runs in — found by walking up from the process cwd until a
   directory holds both src/bb_agent and scripts, then throwing if none does.

   NOT *file*: bb rebinds it to a temp form-init path once tests are running, so
   (-> (fs/path *file*) .getParent .getParent .getParent) resolved to /Users/moe
   and the parity tests silently scanned an empty tree — a green gate measuring
   nothing, caught only because the vacuity test below asserts the scan finds
   sites. And not a literal path either: IRIS's _module_source docstring is right
   that a spelled-out path is a second, silent copy of the layout which goes stale
   the moment a file moves and then reads like a governance regression."
  []
  (let [start (fs/path (System/getProperty "user.dir"))
        chain (take-while some? (iterate #(when % (fs/parent %)) start))
        hit (first (filter #(and (fs/directory? (fs/path % "src/bb_agent"))
                                 (fs/directory? (fs/path % "scripts")))
                           chain))]
    (or hit
        (throw (ex-info "repo-root: no ancestor of user.dir holds src/bb_agent and scripts"
                        {:cwd (str start)})))))

(defn- fixture-repo!
  "A fake repo: a guarded sender with TWO send lines, an unguarded sender, a
   quiet file, a test file, and a .bak snapshot — the last two must not be
   scanned, or a test's fake curl reads as a live leak path."
  [d]
  (fs/create-dirs (str d "/src/x"))
  (fs/create-dirs (str d "/scripts"))
  (fs/create-dirs (str d "/test/x"))
  (spit (str d "/src/x/guarded.clj")
        (str "(defn send! [p]\n"
             "  (preflight/inspect-outbound! p)\n"
             "  (curl \"/botT/sendDocument\" p)\n"
             "  (curl \"/botT/sendPhoto\" p))\n"))
  (spit (str d "/scripts/loose.bb") "(curl \"/botT/sendDocument\" p)\n")
  (spit (str d "/src/x/quiet.clj") ";; nothing leaves here\n")
  (spit (str d "/test/x/guard_test.clj") "(curl \"/botT/sendDocument\")\n")
  (spit (str d "/src/x/old.clj.bak-20261019") "(curl \"/botT/sendDocument\")\n")
  (str d))

(deftest outbound-sites-counts-sites-not-files-test
  (let [rows (p/outbound-sites (fixture-repo! (fs/create-temp-dir {:prefix "parity-"})))]
    (testing "one ROW per send line, so a second site in a guarded file is still visible"
      (is (= 2 (count (filterv #(= "src/x/guarded.clj" (:file %)) rows)))
          "a set keyed by file reports 1 here and the second site is invisible")
      (is (= 3 (count rows))))
    (testing "guarded-ness is per row, and says which file it came from"
      (is (= [true true] (mapv :guarded? (filterv #(= "src/x/guarded.clj" (:file %)) rows))))
      (is (= [false] (mapv :guarded? (filterv #(= "scripts/loose.bb" (:file %)) rows)))))
    (testing "tests and .bak snapshots are not scanned"
      (is (empty? (filterv #(str/includes? (:file %) "test/") rows)))
      (is (empty? (filterv #(str/includes? (:file %) ".bak-") rows))))
    (testing "a row names what it matched and where"
      (let [r (first (filterv #(= "scripts/loose.bb" (:file %)) rows))]
        (is (= ["sendDocument"] (:endpoints r)))
        (is (= 1 (:line r)))
        (is (str/includes? (:text r) "curl"))))))

(deftest outbound-scan-is-not-vacuous-test
  (testing "the scan over the real repo finds sites today"
    (let [rows (p/outbound-sites (repo-root))]
      (is (seq rows)
          "zero sites means outbound-endpoints or the file walk rotted, NOT that the repo is clean")
      (is (some #(str/ends-with? (:file %) "goal_watch.bb") rows))
      (is (some #(str/ends-with? (:file %) "send_document.bb") rows)))))

(deftest every-real-outbound-site-is-guarded-test
  (testing "no file puts a document or photo on the wire without meeting the guard"
    (let [loose (->> (p/outbound-sites (repo-root)) (remove :guarded?) vec)]
      (is (empty? loose)
          (str "unguarded outbound path(s): "
               (pr-str (mapv (juxt :file :line) loose)))))))

(deftest outbound-site-count-is-pinned-test
  ;; IRIS: "Sites are counted now, and a count that changes fails until
  ;; reviewed — which is what 'newly-added call sites fail CI' was always
  ;; supposed to mean." The pin lives in the test so the re-review is a human
  ;; act, not a code change nobody reads.
  ;; Measured 10 on 2026-10-19: 2 real wire calls (goal_watch.bb, send_document.bb),
  ;; 2 endpoint-name data tables (telegram_upload.clj, preflight.clj) and 6
  ;; docstring/receipt rows that merely SAY sendDocument. The scan is
  ;; deliberately recall-biased — a prose row costs one human look, while a
  ;; precision-tuned regex that missed a real send is a silent hole (zeph's
  ;; pattern was empirically broken in both directions at once).
  (is (= 10 (count (p/outbound-sites (repo-root))))
      "the outbound-site count moved: a send path appeared or went away. Re-review it, then move this number."))

(deftest inspect-outbound-lives-in-preflight-test
  (testing "a credential in a text payload is refused, with the caller's tag on the data"
    (let [f (tmp "leaky-parity.md" "export K=sk-abcdefghijklmnop1234\n")]
      (try (p/inspect-outbound! f {:telegram/send-file? true})
           (is false "expected a refusal, got none")
           (catch Exception e
             (let [d (ex-data e)]
               (is (:preflight/blocked d))
               (is (:telegram/send-file? d)
                   "the caller's tag rides along so the transport recognises its own refusal")
               (is (= ["anthropic-or-openai-key"] (:preflight/rules d)))
               (is (= [1] (:preflight/lines d)))
               (is (not (str/includes? (ex-message e) "sk-abcdefghijklmnop1234"))
                   "the refusal must not echo the secret it caught"))))))
  (testing "a clean text payload passes; a non-text payload is SKIPPED, never clean"
    (is (= :pass (p/inspect-outbound! (tmp "ok-parity.md" "nothing here\n"))))
    (is (= :skipped (p/inspect-outbound! (tmp "blob-parity.bin" "sk-abcdefghijklmnop1234\n"))))))

(deftest preflight-sites-cli-test
  (testing "an unguarded site fails the gate and is named with its negative scope"
    (let [d (fixture-repo! (fs/create-temp-dir {:prefix "parity-cli-"}))
          out (with-out-str (is (= 1 (p/-main ["sites" d]))))]
      (is (str/includes? out "loose.bb"))
      (is (str/includes? out "UNGUARDED"))
      (is (str/includes? out "not checked") "what the scan cannot see is stated, not implied")
      (fs/delete-tree d)))
  (testing "a fully guarded tree exits 0"
    (let [d (fixture-repo! (fs/create-temp-dir {:prefix "parity-cli2-"}))]
      (fs/delete (str d "/scripts/loose.bb"))
      ;; with-out-str returns the captured STRING, so the exit-code assertion has
      ;; to live inside the capture — (= 0 (with-out-str ...)) can never be true.
      (with-out-str (is (= 0 (p/-main ["sites" d]))))
      (fs/delete-tree d))))

;; ---------------------------------------------------------------------
;; Rule liveness — 2026-10-21
;;
;; Source: CodePrometheus/chopin-ai, packages/core/src/layer/gates.ts:40-49 —
;; "Configuration errors against the tools actually registered. A gate on a tool
;; nobody can satisfy would refuse forever, so it is an error rather than an
;; ordinary refusal." Companion: alkanfel1987/pantheon-seed,
;; memory/pattern-promotion.md:264-283 — "a zero fire count by itself proves
;; nothing", and the mandatory second step is a probe that reproduces the class.
;;
;; The gap these tests close, measured before writing them: bb-agent.preflight
;; declared 11 rules and this suite proved FOUR could fire (sk-, ghp_, AKIA,
;; /Users/). The other 7 (github_pat_, xox[baprs]-, AIza, the telegram-bot-token
;; shape, private-key-block, consumer-email, phone-number) had no fixture
;; anywhere under test/ — verified by grep, not by memory. A typo in any of them
;; left the suite green and the outbound wire unguarded: the same shape as zeph's
;; pattern rot and pantheon-seed's silent interceptor, sitting on the one guard
;; that inspects secrets leaving the repo.
;;
;; RED FIRST: all six deftests below failed at analysis with
;; `Unable to resolve symbol: p/rule-samples` before src/bb_agent/preflight.clj
;; grew all-rules / rule-liveness / rotted-rules / patterns-report.

(deftest every-declared-rule-carries-a-probe-that-fires-it
  (testing "the data-driven pin: a NEW rule with no sample breaks the build here"
    (let [rows (p/rule-liveness)
          by (into {} (map (juxt :name identity) rows))]
      ;; 11 is pinned the way the outbound-site count is pinned above: changing
      ;; it is the point of the pin, so a rule added or dropped is a reviewed
      ;; act rather than a silent widening or narrowing of the guard.
      (is (= 11 (count rows))
          "the rule count moved — re-review the table, then move this number")
      (doseq [r (p/all-rules)]
        (is (contains? p/rule-samples (:name r))
            (str "rule has no probe, so it has never been proven able to fire: "
                 (:name r)))
        (is (= :live (:status (get by (:name r))))
            (str "rule cannot be proven to fire: " (:name r)))))))

(deftest rule-liveness-names-a-rule-whose-probe-cannot-fire
  (testing "pattern rot is a status with a name, not a silence"
    (let [rows (p/rule-liveness
                [{:name "typo" :re #"sk-[A-Za-z0-9]{99,}"
                  :kind :credential :severity :block}]
                {"typo" (str "sk-" "AbCdEf0123456789xyz")})]
      (is (= 1 (count rows)))
      (is (= :dead (:status (first rows))))
      (is (= ["typo"] (mapv :name (p/rotted-rules rows))))
      (is (str/includes? (str/join "\n" (p/patterns-report rows)) "DEAD")))))

(deftest rule-liveness-names-a-rule-that-has-no-probe
  (testing "never-proven is neither live nor dead — not found is not zero"
    (let [rows (p/rule-liveness
                [{:name "unprobed" :re #"sk-[A-Za-z0-9]{16,}"
                  :kind :credential :severity :block}]
                {})]
      (is (= :no-sample (:status (first rows))))
      (is (false? (:sample? (first rows))))
      (is (nil? (:matched (first rows))))
      (is (= ["unprobed"] (mapv :name (p/rotted-rules rows))))
      (is (str/includes? (str/join "\n" (p/patterns-report rows)) "NO-SAMPLE")))))

(deftest rule-liveness-reports-a-probe-that-does-not-isolate-its-rule
  (testing "a probe that trips two rules says so instead of implying it proved one"
    (let [both (str "sk-" "abcdefghijklmnop1234 ghp_" "ABCDEFGHIJKLMNOPQRSTUV12")
          rows (p/rule-liveness
                [{:name "anthropic-or-openai-key" :re #"sk-[A-Za-z0-9]{16,}"
                  :kind :credential :severity :block}
                 {:name "github-token-classic" :re #"ghp_[A-Za-z0-9]{20,}"
                  :kind :credential :severity :block}]
                {"anthropic-or-openai-key" both
                 "github-token-classic" both})
          by (into {} (map (juxt :name identity) rows))]
      (is (= :live (:status (get by "anthropic-or-openai-key"))))
      (is (= ["github-token-classic"]
             (:also-fired (get by "anthropic-or-openai-key"))))
      (is (= ["anthropic-or-openai-key"]
             (:also-fired (get by "github-token-classic")))))))

(deftest the-rule-table-does-not-match-its-own-source
  (testing "samples are synthetic and built by concatenation, so scanning the guard finds no credential in it"
    (let [f (str (fs/path (repo-root) "src/bb_agent/preflight.clj"))
          blocks (filterv #(= :block (:severity %)) (p/scan-file f))]
      (is (empty? blocks)
          (str "preflight flags its own source, so the guard gets commented out "
               "the first time somebody scans the repo: "
               (pr-str (mapv (juxt :rule :line) blocks)))))))

(deftest preflight-patterns-cli-test
  (testing "the real table reports every rule and exits 0"
    (let [out (with-out-str (is (= 0 (p/-main ["patterns"]))))]
      (is (str/includes? out "11 declared"))
      (is (str/includes? out "anthropic-or-openai-key"))
      (is (str/includes? out "phone-number"))
      (is (str/includes? out "not checked")
          "the negative scope is part of the answer, not decoration")
      (is (not (str/includes? out "ROTTED")))))
  (testing "an empty table is UNVERIFIED rather than clean (zeph pattern-rot rule)"
    (let [out (str/join "\n" (p/patterns-report []))]
      (is (str/includes? out "[UNVERIFIED]"))
      (is (str/includes? out "zero rules")))))

;; ---------------------------------------------------------------------
;; Rule DISCRIMINATION — 2026-10-31
;;
;; Stolen from 0xSneaks/SUSHILOOP core/evolve.py:20-29: "discrimination (0-10)
;; measures whether the skill distinguishes input it should flag from input it
;; shouldn't; 2 = keyword-matching theater, 5 = real signal." A positive probe
;; alone proves a rule CAN fire, never that it fires on the right things — the
;; liveness table landed 2026-10-21 with one side only.
;;
;; The hole is measured, not argued, and it was live. On 2026-10-31 `bb preflight`
;; over this lane's own recommendations file printed
;;   BLOCK …:27 [credential] telegram-bot-token → 1234…60-char, masked
;; and `bb preflight --strict` exited 1, for a file carrying no secret: line 27
;; quoted an ISO-ish digest filename (ten digits, a colon, a timestamp body with
;; hyphens), which satisfies the old bot-token pattern's `[0-9]{6,12}:[…]{30,}`.
;; The same guard's polarity was already measured from the other side in
;; state/rsi/report/2026-10-13.md §6/§10: it PASSED a real-shaped hyphenated
;; provider key while BLOCKING documentation that quoted a synthetic fixture.
;; Flagged there as "Not applied — this lane edits nothing"; eighteen days later
;; this lane edits it, both directions at once.
;;
;; Scope, stated because it is a decision: discrimination is demanded of
;; severity :block rules only. A :warn personal-data rule firing on an honest
;; /Users coordinate is the documented intent (10-12: personal data never blocks
;; a send), so calling it overbroad would be a false finding — the same mistake
;; as blocking a send on a truthful receipt.
;;
;; RED FIRST: the deftests below failed at analysis with
;; `Unable to resolve symbol: p/benign-samples` before src/bb_agent/preflight.clj
;; grew benign-samples / :overbroad?, and then failed for real on the two
;; regexes until both were corrected.

(deftest every-block-rule-carries-a-benign-probe-it-must-not-fire
  (testing "the data-driven pin, second side: a NEW :block rule with no benign sample breaks the build here"
    (let [rows (p/rule-liveness)
          by (into {} (map (juxt :name identity) rows))]
      (doseq [r (filterv #(= :block (:severity %)) (p/all-rules))]
        (is (contains? p/benign-samples (:name r))
            (str "block rule has no benign probe, so nothing proves it discriminates: "
                 (:name r)))
        (is (false? (:overbroad? (get by (:name r))))
            (str "block rule fires on prose that carries no secret: " (:name r)
                 " — " (pr-str (:overbroad-match (get by (:name r))))))))))

(deftest benign-samples-are-quiet-for-their-own-rule
  (testing "the real table, both sides at once"
    (let [by (into {} (map (juxt :name identity) (p/rule-liveness)))]
      (doseq [[name text] p/benign-samples]
        (let [row (get by name)]
          (is (some? row) (str "benign sample names a rule that does not exist: " name))
          (is (empty? (filterv #(= name (:rule %)) (p/scan-text text name)))
              (str "benign prose fires " name " — the rule is too wide"))
          (is (= :live (:status row))
              (str "the positive probe must still fire while the benign one stays quiet: " name)))))))

(deftest rule-liveness-names-a-rule-that-fires-on-benign-prose
  (testing "overbroad is a status with a name, not a silence"
    (let [rows (p/rule-liveness
                [{:name "anything-with-a-colon" :re #"[0-9]+:[A-Za-z0-9-]+"
                  :kind :credential :severity :block}]
                {"anything-with-a-colon" "token 1761926789:AAbCdEf0123456789wxyz"}
                {"anything-with-a-colon" "digest 1761926789:2026-10-31T00-00-00Z.md"})
          r (first rows)]
      (is (= :live (:status r)) "the positive probe still fires, so it is live")
      (is (true? (:overbroad? r)) "and separately marked overbroad")
      (is (some? (:overbroad-match r)) "the offending match is named, masked")
      (is (= ["anything-with-a-colon"] (mapv :name (p/rotted-rules rows)))
          "a one-sided proof cannot read as clean, so the CLI exits 1")
      (is (str/includes? (str/join "\n" (p/patterns-report rows)) "OVERBROAD")))))

(deftest rule-liveness-keeps-a-two-sided-proof-live
  (testing "a rule with both probes behaving is not rotted"
    (let [rows (p/rule-liveness
                [{:name "narrow" :re #"sk-[A-Za-z0-9]{16,}" :kind :credential :severity :block}]
                {"narrow" (str "sk-" "AbCdEf0123456789wxyz")}
                {"narrow" "backup: disk-20261031-abcdef0123456789.tar.gz"})
          r (first rows)]
      (is (= :live (:status r)))
      (is (false? (:overbroad? r)))
      (is (empty? (p/rotted-rules rows))))))

(deftest rule-liveness-does-not-call-a-warn-rule-overbroad
  (testing "a personal-data rule firing on an honest coordinate is the documented intent, not a defect"
    (let [rows (p/rule-liveness
                [{:name "home-directory-path" :re #"/Users/[A-Za-z0-9._-]+"
                  :kind :personal-data :severity :warn}]
                {"home-directory-path" (str "/Use" "rs/moe/theseus")}
                {"home-directory-path" (str "receipt at /Use" "rs/moe/theseus/brain/x.md")})
          r (first rows)]
      (is (= :live (:status r)))
      (is (false? (:overbroad? r))
          "warn-severity rules are exempt: calling this overbroad would be a false finding")
      (is (empty? (p/rotted-rules rows))))))

(deftest anthropic-rule-fires-on-the-real-hyphenated-shape
  (testing "the 2026-10-13 KNOWN miss: a real-shaped provider key must not sail through"
    (let [real (str "export ANTHROPIC_API_KEY=sk-" "ant" "-api03-AbCdEf0123456789wxyz")
          f (tmp "ant-key-1031.md" (str real "\n"))]
      (is (seq (filterv #(= "anthropic-or-openai-key" (:rule %)) (p/scan-file f)))
          "a hyphenated provider key is the production format; missing it is failing OPEN")
      (is (= :fail (p/verdict (p/scan-file f) {}))))))

(deftest telegram-bot-token-does-not-fire-on-an-iso-digest-filename
  (testing "the live 2026-10-31 false block: honest prose about this lane must be sendable"
    (let [benign (str "digest filename: 1234567890:2026-10-31T17-46-29-408901Z"
                      "-recommendations.md")
          f (tmp "honest-1031.md" (str benign "\n"))]
      (is (empty? (filterv #(= "telegram-bot-token" (:rule %)) (p/scan-file f)))
          "an ISO-ish timestamp body is not a bot token")
      (is (empty? (filterv #(= :block (:severity %)) (p/scan-file f)))
          "and nothing else blocks this file either")
      (is (= :pass (p/verdict (p/scan-file f) {}))))
    (testing "while a real-shaped token still blocks — narrowing must not become failing open"
      (let [real (str "token 123456789" ":AA" "BcDeF0123456789wxyzAbCdEf01234")
            f (tmp "real-token-1031.md" (str real "\n"))]
        (is (seq (filterv #(= "telegram-bot-token" (:rule %)) (p/scan-file f))))
        (is (= :fail (p/verdict (p/scan-file f) {})))))))

(deftest patterns-cli-stays-green-on-a-two-sided-table
  (testing "the real table now proves both directions, so the report is clean and exits 0"
    (let [out (with-out-str (is (= 0 (p/-main ["patterns"]))))]
      (is (str/includes? out "0 overbroad"))
      (is (not (str/includes? out "ROTTED")))
      (is (str/includes? out "a benign probe proves it stays quiet on ONE honest")
          "the negative scope survives the widening, and says both sides are synthetic"))))

;; ---------------------------------------------------------------------
;; Evidence class on a guard report — 2026-11-01
;;
;; Source: umararshad4/honey-stack, honey/doctor.py:94 — printed
;; unconditionally on every session-health run: "Coverage describes observed
;; payloads; policy enforcement is not established by a heartbeat." Its
;; companion at :82-83 folds a NATIVE registry read into health, and an
;; unparseable response becomes status 'unknown' rather than 'trusted'
;; (codex_hooks.inspect, :73-103).
;;
;; The gap this closes, measured before writing the test: `bb preflight sites`
;; exits 0 with "N site(s) ... 0 unguarded" and nothing in the output says the
;; evidence is a STATIC text enumeration of call sites rather than an
;; observation that the guard ran when a file left. The seam has already moved
;; once (preflight -> bb-agent.preflight, 2026-10-19) with the enumeration
;; re-run by hand each time. A clean exit 0 that does not name its own evidence
;; class reads as a proven guard — the false-clean shape.
(deftest sites-report-states-its-own-evidence-class-test
  (testing "a guarded tree still exits 0, and says what kind of proof that is"
    (let [d (fixture-repo! (fs/create-temp-dir {:prefix "parity-evidence-"}))]
      (fs/delete (str d "/scripts/loose.bb"))
      (let [out (with-out-str (is (= 0 (p/-main ["sites" d]))))]
        (is (str/includes? out "static enumeration")
            "the report names its evidence class, so exit 0 cannot read as runtime proof")
        (is (str/includes? out "not proof that the guard runs")
            "and states plainly what it does not establish")
        (is (str/includes? out "0 unguarded") "the measurement itself is unchanged"))
      (fs/delete-tree d)))
  (testing "an unguarded tree keeps failing, and the evidence class rides along too"
    (let [d (fixture-repo! (fs/create-temp-dir {:prefix "parity-evidence2-"}))
          out (with-out-str (is (= 1 (p/-main ["sites" d]))))]
      (is (str/includes? out "UNGUARDED"))
      (is (str/includes? out "static enumeration")
          "the clause is not conditional on a clean result — a failure states its class too")
      (fs/delete-tree d)))
  (testing "the library function carries it, not just the CLI"
    (let [d (fixture-repo! (fs/create-temp-dir {:prefix "parity-evidence3-"}))
          rows (p/outbound-sites d)]
      (is (some #(str/includes? % "static enumeration") (p/sites-report rows "."))
          "sites-report is the seam the CLI and any future caller both read")
      (fs/delete-tree d))))
