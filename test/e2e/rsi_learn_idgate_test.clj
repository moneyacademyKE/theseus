(ns e2e.rsi-learn-idgate-test
  "RED-FIRST tests for the id-gate coverage patch (2026-11-08).

   The measured defect, reproduced this turn rather than argued:
   `vicfei/deepseek-bot` IS `vicfei/dsh-dot`, refused 2026-11-01 at
   improvements.md:875. GET /repos/vicfei/dsh-dot returns HTTP 301 with
   `location: /repositories/1410001857`; both READMEs are byte-identical at
   13718 B (sha1 prefix e172e25bfcce); created_at is 2026-10-08T08:38:27Z on
   today's API row and on the 11-01 ledger line. But seen.edn holds
   {:name \"vicfei/dsh-dot\" :id nil} beside
   {:name \"vicfei/deepseek-bot\" :id 1410001857} — two rows, no shared key, so
   seen-identity's id half had nothing to match and its name half cannot see a
   rename to a different spelling. 177 of 221 rows are :id nil.

   And propose!'s rename gate reads `^- id:` lines out of the ledger, of which
   the live ledger carried ZERO over 87 repo lines — so ledger-ids returned the
   empty set and the gate has never had an input. A gate whose input is empty
   reads as a gate that passed: the eighth false-clean this lane has met."
  (:require [babashka.fs :as fs]
            [bb-agent.rsi-learn :as learn]
            [clojure.test :refer [deftest is]]))

(defn- temp-home! []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "brain"))
    dir))

(def ^:private now (java.time.Instant/parse "2026-11-08T09:00:00Z"))

;; ---------- identity-verdict: a miss says whether it could have decided ----------

(deftest identity-verdict-is-known-by-name-or-by-id-test
  (is (= :seen-by-name (:status (learn/identity-verdict
                                 {:full-name "someone/repo" :id 9}
                                 [{:name "Someone/Repo" :id nil}]))))
  (is (= :seen-by-id (:status (learn/identity-verdict
                               {:full-name "new/spelling" :id 9}
                               [{:name "old/spelling" :id 9}])))))

(deftest identity-verdict-is-fresh-when-the-ledger-can-decide-test
  (let [v (learn/identity-verdict {:full-name "new/thing" :id 9}
                                  [{:name "other/repo" :id 7}])]
    (is (= :fresh (:status v)))
    (is (false? (:id-blind v)) "every row carries an id, so the miss is real")))

(deftest identity-verdict-is-id-blind-on-the-measured-rename-shape-test
  ;; The live failure: a nil-id legacy row beside a renamed repo that has an id.
  (let [v (learn/identity-verdict {:full-name "vicfei/deepseek-bot" :id 1410001857}
                                  [{:name "vicfei/dsh-dot" :id nil}])]
    (is (= :fresh (:status v))
        "the status stays :fresh — inferring a rename from a name is the heuristic refusal-classes.md forbids")
    (is (true? (:id-blind v))
        "and the miss is attributed to the ledger's missing id, not read as a measured no")))

(deftest identity-verdict-is-not-id-blind-without-a-nil-id-row-test
  ;; An item with no id against a ledger whose rows all have ids: name identity
  ;; is all there is, and that is not the blind shape.
  (is (false? (:id-blind (learn/identity-verdict {:full-name "new/thing" :id nil}
                                                 [{:name "other/repo" :id 7}]))))
  (is (false? (:id-blind (learn/identity-verdict {:full-name "new/thing" :id 9} [])))
      "an empty ledger cannot be blind, it simply has nothing"))

;; ---------- seen-coverage: the denominator beside the gate ----------

(deftest seen-coverage-partition-sums-test
  (is (= {:rows 3 :with-id 1 :id-blind 2}
         (learn/seen-coverage [{:name "a/b" :id 1} {:name "c/d" :id nil} {:name "e/f" :id nil}])))
  (is (= {:rows 0 :with-id 0 :id-blind 0} (learn/seen-coverage []))
      "an empty ledger is a measured zero, not a missing measurement")
  (let [c (learn/seen-coverage [{:name "a" :id 1} {:name "b" :id nil}])]
    (is (= (:rows c) (+ (:with-id c) (:id-blind c))))))

(deftest seen-coverage-of-the-live-ledger-is-not-vacuous-test
  ;; zeph's rule (2026-10-19): a scan that finds nothing is broken until proven
  ;; otherwise. This asserts the live corpus really does hold nil-id rows, so the
  ;; coverage number is about something. If this ever fails, the ledger was
  ;; migrated and the REPORT needs re-reading — not the test weakened.
  (let [rows (learn/load-seen-rows)
        c (learn/seen-coverage rows)]
    (when (seq rows)
      (is (= (:rows c) (count rows)))
      (is (= (:rows c) (+ (:with-id c) (:id-blind c))))
      (is (pos? (:id-blind c))))))

;; ---------- ledger-id-coverage: was the propose-side gate ever armed? ----------

(deftest ledger-id-coverage-counts-repo-blocks-not-narrative-headings-test
  (let [text (str "## someone/a\n- url: https://github.com/someone/a\n- id: 5\n- act: code\n\n"
                  "## someone/b\n- url: https://github.com/someone/b\n- act: code\n\n"
                  "## 2026-11-08T00:00Z — scout lane narrative\n- found: x\n")]
    (is (= {:repo-blocks 2 :with-id 1 :without-id 1} (learn/ledger-id-coverage text)))
    (is (= {:repo-blocks 0 :with-id 0 :without-id 0} (learn/ledger-id-coverage "")))))

(deftest id-gate-unarmed-names-a-gate-that-has-never-had-input-test
  (is (true? (learn/id-gate-unarmed? "## a/b\n- url: u\n- act: code\n\n## c/d\n- url: u\n- act: code\n"))
      "repo blocks exist, none carries an id, so block-id/ledger-ids can never fire")
  (is (false? (learn/id-gate-unarmed? "## a/b\n- url: u\n- id: 3\n- act: code\n")))
  (is (false? (learn/id-gate-unarmed? "")) "no repo blocks is not an unarmed gate, it is no gate to arm")
  (is (false? (learn/id-gate-unarmed? "## 2026-11-08T00:00Z\n- found: x\n"))
      "a ledger of narrative headings only has no gate to arm"))

;; ---------- propose! reports whether its gate was armed ----------

(deftest propose-reports-an-unarmed-id-gate-test
  (let [home (temp-home!)
        imp (fs/path home "brain" "improvements.md")]
    (fs/create-dirs (fs/parent imp))
    (spit (str imp) "## someone/old\n- url: u\n- act: code\n")
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec) "## someone/new\n- url: u\n- id: 5\n- act: code\n")
        (let [r (learn/propose! (str rec))]
          (is (= 1 (:added r)))
          (is (false? (:id-gate-armed r))
              "the ledger held no id, so the rename gate had nothing to match — say so")
          (is (= 1 (:ledger-repo-blocks r))))))))

(deftest propose-reports-an-armed-id-gate-test
  ;; SUPERSEDED in place 2026-11-08, and the reason is recorded rather than left
  ;; for the next reader to guess: this originally asserted
  ;; `(:id-gate-armed r)` was true on an ARMED ledger. That encoded my FIRST
  ;; implementation, which put the coverage keys on every non-empty return and
  ;; thereby broke two pre-existing tests whose message is literally
  ;; "the non-empty return shape is unchanged" (rsi_learn_test.clj:106, :192).
  ;; Editing their assertions to tolerate my new key would have weakened a
  ;; no-regression guard to suit the change under test, so the code changed
  ;; instead — the 2026-10-16 verified-empty? precedent: keep the old shape
  ;; byte-identical and report only what is news. An ARMED gate is the normal
  ;; case and stays silent; coverage in full is asked of id-gate-report, which
  ;; always answers, so nothing is left unmeasured.
  (let [home (temp-home!)
        imp (fs/path home "brain" "improvements.md")]
    (fs/create-dirs (fs/parent imp))
    (spit (str imp) "## someone/old\n- url: u\n- id: 4\n- act: code\n")
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec) "## someone/new\n- url: u\n- id: 5\n- act: code\n")
        (let [r (learn/propose! (str rec))]
          (is (= {:added 1 :skipped 0} r)
              "an armed gate leaves the pre-existing return shape byte-identical")
          ;; id-gate-report reads the LIVE ledger, so by the time this asserts,
          ;; propose! has already appended the new block: two repo blocks, both
          ;; carrying an id. The first draft of this assertion expected one and
          ;; failed on exactly that (actual: 2), which is the report working —
          ;; it measures the file, not the moment before the append.
          (is (= {:repo-blocks 2 :with-id 2 :without-id 0 :id-gate-armed true}
                 (learn/id-gate-report))
              "coverage is asked for in full through the report that always answers"))))))

(deftest id-gate-report-is-not-a-pass-on-a-missing-ledger-test
  ;; Not-found is not zero, and it is not a pass either: an absent ledger has no
  ;; repo blocks, so there is no gate to arm and the report says exactly that.
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [g (learn/id-gate-report)]
        (is (= {:repo-blocks 0 :with-id 0 :without-id 0 :id-gate-armed false} g))
        (is (false? (learn/id-gate-unarmed? nil))
            "no repo blocks at all is not an unarmed gate, it is no gate to arm")))))

(deftest propose-verified-no-op-keeps-its-return-shape-test
  ;; No-regression: the new keys appear only when there were blocks to judge, so
  ;; the 2026-10-16 {:added 0 :skipped 0 :empty true} shape stays byte-identical.
  (let [home (temp-home!)]
    (fs/create-dirs (fs/path home "brain"))
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec) (str "# digest\n\nqueries: [\"topic:x\"] | new: 0\n\n"
                             "nothing new under the topics since the window opened.\n"))
        (is (= {:added 0 :skipped 0 :empty true} (learn/propose! (str rec)))
            "additive, never rewritten")))))

;; ---------- fetch! names the finds the ledger could not identify ----------

(deftest fetch-reports-seen-coverage-and-id-blind-finds-test
  (let [home (temp-home!)]
    (fs/create-dirs (fs/path home "state" "rsi" "learn"))
    (fs/create-dirs (fs/path home "brain"))
    ;; A legacy row with no id, exactly like the live 177.
    (spit (str (fs/path home "state" "rsi" "learn" "seen.edn"))
          "[{:name \"vicfei/dsh-dot\", :id nil}]")
    (with-redefs [bb-agent.config/home (constantly home)
                  bb-agent.config/load-config (constantly {})]
      (let [items [{:full-name "vicfei/deepseek-bot" :url "u" :description "d"
                    :stars 2 :pushed-at "2026-11-08T03:01:04Z" :id 1410001857}]
            r (learn/fetch! {:now now
                             :search-fn (fn [{:keys [query]}]
                                          (if (= query "topic:self-learning") items []))
                             :readme-fn (fn [_] "readme text")
                             :dry-run? true})]
        (is (= {:rows 1 :with-id 0 :id-blind 1} (:seen-coverage r)))
        (is (= ["vicfei/deepseek-bot"] (:id-blind-finds r))
            "the find the ledger could not identify is named, not silently filed as fresh")))))
