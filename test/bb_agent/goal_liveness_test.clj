(ns bb-agent.goal-liveness-test
  "Red first: the dispatch lane had no liveness verification, so a goal whose
   authoring process died was indistinguishable from a slow one — it aged out
   silently. Evidence: `propose-must-fail-closed-260708`, dispatched
   2026-10-09 09:11, its authoring pid gone, workspace holding spec.txt +
   launch.log only (no config.edn, no run.log, no outcome.edn), and six days
   later nothing anywhere reported it FAILED. Open act:code since 2026-10-10.

   Two contracts matter here:
   1. A stamped authoring pid that is dead with no receipt is FAILED — measured.
   2. A workspace with NO stamp is never reported FAILED — not-found is not
      zero (aden-hive/hive). It is :unstamped or :abandoned (age-inferred),
      and the report names the difference in its negative scope."
  (:require [babashka.fs :as fs]
            [bb-agent.goal-liveness :as gl]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *root* nil)

(defn- ws!
  "One goal workspace under the fixture root."
  [name]
  (let [d (fs/path *root* name)]
    (fs/create-dirs d)
    (str d)))

(defn- stamp!
  [name pid]
  (let [w (ws! name)]
    (gl/stamp-dispatch! w name pid :chat-id -1 :thread-id 5 :spec-chars 42)
    w))

(defn- alive-fn
  "Injected liveness: only the listed pids are alive. The library takes it as
   data so no test depends on this machine's process table."
  [& alive]
  (fn [pid] (boolean (some #(= % pid) alive))))

(defn- fixture [f]
  (let [r (str (fs/create-temp-dir {:prefix "gl-test"}))]
    (binding [*root* r]
      (f))
    (fs/delete-tree r)))

(use-fixtures :each fixture)

(deftest stamp-dispatch-writes-a-readable-stamp-test
  (testing "the stamp carries the pid, the spec size and the topic"
    (let [w (ws! "g1")
          spec "build the thing"
          s (gl/stamp-dispatch! w "g1" 4242
                                :chat-id -1003995594829 :thread-id 5
                                :spec-chars (count spec))
          on-disk (edn/read-string (slurp (str (fs/path w "dispatch.edn"))))]
      (is (= 4242 (:authoring-pid on-disk)))
      (is (= 15 (:spec-chars s)))
      (is (= 5 (:thread-id s)))
      (is (some? (:dispatched-at on-disk)))
      (is (= "g1" (:name (gl/read-stamp w)))))))

(deftest stamp-status-distinguishes-missing-from-unreadable-test
  (testing "not-found is not zero: absent is :missing, corrupt bytes are :unreadable"
    (let [a (ws! "a") b (ws! "b")]
      (is (= :missing (gl/stamp-status a)))
      (spit (str (fs/path b "dispatch.edn")) "{:authoring-pid ")
      (is (= :unreadable (gl/stamp-status b)))
      (stamp! "c" 7)
      (is (= :read (gl/stamp-status (str (fs/path *root* "c"))))))))

(deftest classify-named-statuses-test
  (testing "live / failed / receipted / unstamped / abandoned"
    (let [live (stamp! "live" 11)
          dead (stamp! "dead" 22)
          done (stamp! "done" 33)
          bare (ws! "bare")
          old (ws! "old")]
      (spit (str (fs/path done "run.log")) "GOAL FULFILLED\n")
      (fs/set-last-modified-time old
                                 (java.time.Instant/ofEpochMilli
                                  (- (System/currentTimeMillis) (* 1000 60 60 48))))
      (let [opts {:alive? (alive-fn 11)}
            by (fn [w] (:status (gl/classify w opts)))]
        (is (= :live (by live)))
        (is (= :failed (by dead)))
        (is (= :receipted (by done)))
        (is (= :unstamped (by bare)))
        (is (= :abandoned (by old)))
        (is (= 22 (:authoring-pid (gl/classify dead opts)))
            "the failed verdict carries the pid it checked")
        (is (= #{:run-log} (:receipts (gl/classify done opts))))))))

(deftest audit-groups-and-counts-every-workspace-once-test
  (testing "counts sum to the workspace count; no stamp can never read as failed"
    (stamp! "live" 11)
    (stamp! "dead" 22)
    (stamp! "dead2" 23)
    (ws! "bare")
    (let [a (gl/audit *root* {:alive? (alive-fn 11)})]
      (is (= :read (:root-status a)))
      (is (= 4 (:workspaces a)))
      (is (= ["dead" "dead2"] (sort (map :name (:failed a)))))
      (is (= ["live"] (map :name (:live a))))
      (is (= ["bare"] (map :name (:unstamped a))))
      (is (empty? (:abandoned a)))
      (is (= 4 (+ (count (:live a)) (count (:receipted a)) (count (:failed a))
                  (count (:abandoned a)) (count (:unstamped a))))))))

(deftest audit-of-a-missing-root-is-unverifiable-not-zero-test
  (testing "a goals root that does not exist reports :root-status :missing, never 0 workspaces"
    (let [a (gl/audit (str (fs/path *root* "nope")) {:alive? (alive-fn)})]
      (is (= :missing (:root-status a)))
      (is (nil? (:workspaces a)))
      (is (str/includes? (gl/report a) "goals root not read"))))

  (testing "an existing empty root is a measured zero"
    (let [a (gl/audit *root* {:alive? (alive-fn)})]
      (is (= :read (:root-status a)))
      (is (= 0 (:workspaces a))))))

(deftest report-names-failures-and-its-negative-scope-test
  (testing "the report is a receipt: names, ages, and what it did not check"
    (stamp! "dead" 22)
    (ws! "bare")
    (let [r (gl/report (gl/audit *root* {:alive? (alive-fn)}))]
      (is (str/includes? r "FAILED (authoring pid dead, no receipt): dead"))
      (is (str/includes? r "pid 22"))
      (is (str/includes? r "negative scope"))
      (is (str/includes? r "1 workspace(s) carry no readable dispatch.edn"))
      (is (not (str/includes? r "FAILED (authoring pid dead, no receipt): bare"))
          "an unstamped workspace is never named FAILED"))))

(deftest cli-main-test
  (testing "exit 0 with a report; exit 2 on a root that is not a directory"
    (stamp! "dead" 22)
    (is (= 0 (gl/-main [*root*])))
    (is (= 2 (gl/-main [(str (fs/path *root* "nope"))])))))

;; ---------- 2026-11-03: an authoring receipt that records :error is not a success ----------

(deftest errored-authoring-is-not-receipted-test
  (testing "authoring.edn with :result :error classifies as :errored, carrying the message"
    (let [w (stamp! "boom" 44)]
      (spit (str (fs/path w "authoring.edn"))
            (pr-str {:model "ali/qwen3.8-max-0902" :rounds 17 :duration-ms 466344
                     :result :error :finished "2026-11-03T15:15:42Z"
                     :error "java.net.http.HttpTimeoutException: request timed out"}))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :errored (:status row))
            "the authoring turn returned, but it returned an error — that is not a receipt of work")
        (is (= :error (:authoring-verdict row)))
        (is (str/includes? (str (:authoring-error row)) "HttpTimeoutException")
            "the verdict carries the reason it failed, so the report can name it")
        (is (contains? (:receipts row) :authoring)
            "the file is still a receipt — it is evidence, just not evidence of success")))))

(deftest errored-authoring-beats-other-receipts-test
  (testing "an error verdict outranks a run.log receipt: a halted run is not a finished one"
    (let [w (stamp! "halted" 55)]
      (spit (str (fs/path w "run.log")) "iter-000 GOAL FAILED\n")
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :error :error "boom"}))
      (is (= :errored (:status (gl/classify w {:alive? (alive-fn)})))))))

;; SUPERSEDED 2026-11-15 (mechanical-confidence-supersede.md): the six fixtures
;; below used to write {:result :ok}, a value no production code writes —
;; goal_bridge.clj:385 records :authored for success (vocabulary: :stalled |
;; :timed-out | :authored | :invalid, plus :error at :376), and
;; `grep -rn ":result :ok" src/ scripts/` returns 0 hits. Under the old
;; contract (only :error is a failure) any other keyword read as healthy, so
;; those fixtures asserted a contract about a word that does not exist — the
;; same blind spot that let :invalid and :timed-out sit unrecognised for ten
;; weeks. Corrected to :authored rather than deleted, with the reason kept here
;; so the next reader cannot "fix" the code back into ignoring unknown verdicts.
(deftest healthy-authoring-still-receipts-test
  (testing "no-regression: an authoring.edn recording the success verdict is still :receipted"
    (let [w (stamp! "fine" 66)]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :authored :rounds 9}))
      (is (= :receipted (:status (gl/classify w {:alive? (alive-fn)}))))
      (is (nil? (:authoring-verdict (gl/classify w {:alive? (alive-fn)})))))))

(deftest unreadable-authoring-is-not-an-error-test
  (testing "corrupt bytes in authoring.edn do not manufacture an error verdict"
    (let [w (stamp! "corrupt" 77)]
      (spit (str (fs/path w "authoring.edn")) "{:result ")
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :receipted (:status row)) "the file exists, so the authoring returned")
        (is (nil? (:authoring-verdict row))
            "not-found / unreadable is not :error — the same rule as stamp-status")))))

(deftest report-names-errored-workspaces-test
  (testing "the human receipt names ERRORED goals and says an authoring receipt is not a success"
    (let [bad (stamp! "bb-tiers-475780" 88)
          ok (stamp! "real" 99)]
      (spit (str (fs/path bad "authoring.edn"))
            (pr-str {:result :error :error "java.net.http.HttpTimeoutException: request timed out"}))
      (spit (str (fs/path ok "run.log")) "GOAL FULFILLED\n")
      (let [r (gl/report (gl/audit *root* {:alive? (alive-fn)}))]
        (is (str/includes? r "ERRORED"))
        (is (str/includes? r "bb-tiers-475780"))
        (is (str/includes? r "HttpTimeoutException"))
        (is (str/includes? r "not evidence of work done"))
        (is (not (str/includes? r "ERRORED (authoring returned an error): real"))
            "a healthy goal is not named")))))

(deftest audit-groups-errored-separately-test
  (testing "errored workspaces leave the :receipted bucket, so the count cannot flatter the lane"
    (let [bad (stamp! "bad" 111)
          ok (stamp! "ok" 222)]
      (spit (str (fs/path bad "authoring.edn")) (pr-str {:result :error :error "x"}))
      (spit (str (fs/path ok "authoring.edn")) (pr-str {:result :authored}))
      (let [a (gl/audit *root* {:alive? (alive-fn)})]
        (is (= 1 (count (:errored a))))
        (is (= 1 (count (:receipted a))))
        (is (= 2 (:workspaces a)))))))

;; ------------------------------------------------------------------ delivery
;; Stolen from new-world-arkitech/Self's acceptance_test_generator.py: a
;; generated sign-off artifact that authorizes nothing is a gate in name only
;; (its AUTHORIZE EVOLUTION button is an alert(), and nothing in the repo reads
;; the file it writes). One level down, ours: all four goals dispatched
;; 2026-11-03/04 have a launch.log whose last line is
;; `sendMessage ok=false res=Bad Request: chat not found`, and
;; `grep -rn "launch.log" src/ scripts/` returns writers only — goal_bridge.clj:605,
;; goal_launch.bb:45, goal_resume.bb:47 — and ZERO readers. So dispatch-spec!
;; printed "authoring started, progress updates below" for a topic the bot
;; cannot see. The receipt existed on disk and no boundary read it.
;; `:delivery-blind` is an ANNOTATION, never a fifth status, so the partition
;; still sums: a goal can be receipted AND delivery-blind, and both matter.

(defn- log!
  "Append one launch.log line in the launcher's own format
   (scripts/goal_launch.bb:45 — `<date> <method> ok=<bool> res=<message_id|description>`)."
  [w line]
  (spit (str (fs/path w "launch.log")) (str line "\n") :append true)
  w)

(deftest delivery-parses-the-launchers-own-format-test
  (testing "ok=true is :ok, ok=false is :rejected with the API's reason"
    (let [good (log! (ws! "good") "Sun Sep 27 01:42:25 EAT 2026 editMessageText ok=true res=897")
          bad (log! (ws! "bad") "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found")]
      (is (= :ok (:status (gl/delivery good))))
      (is (= "897" (:reason (gl/delivery good))))
      (is (= :rejected (:status (gl/delivery bad))))
      (is (= "Bad Request: chat not found" (:reason (gl/delivery bad))))
      (is (= "sendMessage" (:method (gl/delivery bad)))))))

(deftest delivery-reads-the-LAST-line-not-the-first-test
  (testing "launch.log accumulates across attempts (goal_bridge.clj:415), so the
            newest receipt is the one that describes this dispatch"
    (let [w (ws! "multi")]
      (log! w "Sun Sep 27 01:41:30 EAT 2026 editMessageText ok=true res=897")
      (log! w "Sun Sep 27 01:42:25 EAT 2026 editMessageText ok=false res=Bad Request: chat not found")
      (is (= :rejected (:status (gl/delivery w)))))))

(deftest delivery-missing-log-is-unknown-not-rejected-test
  (testing "no launch.log means no evidence either way — not-found is not a
            failure verdict, and it is not a zero (the 2026-10-14 rule)"
    (let [w (ws! "nolog")]
      (is (= :unknown (:status (gl/delivery w))))
      (is (nil? (:delivery-blind? (gl/classify w {:alive? (alive-fn)})))
          "an unmeasured delivery is never annotated blind"))))

(deftest delivery-unparseable-line-is-unknown-test
  (testing "a last line that is not the launcher's format is :unknown, not :ok —
            guessing 'looks fine' is how a false-clean is born"
    (let [w (log! (ws! "junk") "curl: (6) Could not resolve host")]
      (is (= :unknown (:status (gl/delivery w))))
      (is (str/includes? (:reason (gl/delivery w)) "unparseable")))))

(deftest classify-annotates-delivery-blind-without-changing-status-test
  (testing "a receipted goal whose ack was rejected stays :receipted and gains
            :delivery-blind? — the partition still sums, because this is an
            annotation about the wire, not a verdict about the work"
    (let [w (log! (stamp! "blinded" 31) "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found")]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :authored :rounds 9}))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :receipted (:status row)) "the work receipt is unchanged")
        (is (true? (:delivery-blind? row)))
        (is (= "Bad Request: chat not found" (get-in row [:delivery :reason])))))))

(deftest audit-reports-delivery-blind-separately-test
  (testing "the audit names blind deliveries beside the status buckets, so a
            lane that printed acks nobody received cannot read as a lane that
            delivered them"
    (let [blind (log! (stamp! "blind-1" 41) "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found")
          seen (log! (stamp! "seen-1" 42) "Sun Sep 27 01:42:25 EAT 2026 editMessageText ok=true res=897")]
      (spit (str (fs/path blind "authoring.edn")) (pr-str {:result :authored}))
      (spit (str (fs/path seen "authoring.edn")) (pr-str {:result :authored}))
      (let [a (gl/audit *root* {:alive? (alive-fn)})]
        (is (= ["blind-1"] (mapv :name (:delivery-blind a))))
        (is (= 2 (:workspaces a)) "annotation, not a bucket: nothing left :receipted")
        (is (= 2 (count (:receipted a))))))))

(deftest report-names-delivery-blind-rows-test
  (testing "the human receipt prints a DELIVERY-BLIND row and says what that
            does and does not prove"
    (let [w (log! (stamp! "blind-goal" 51) "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found")]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :error :error "java.net.http.HttpTimeoutException: request timed out"}))
      (let [r (gl/report (gl/audit *root* {:alive? (alive-fn)}))]
        (is (str/includes? r "DELIVERY-BLIND"))
        (is (str/includes? r "blind-goal"))
        (is (str/includes? r "chat not found"))
        (is (str/includes? r "not evidence that a topic saw it")
            "an ack printed locally is not a delivery")))))

(deftest delivery-blind-count-survives-a-clean-root-test
  (testing "a root where every ack landed reports 0 blind and still prints the
            count — a zero that is measured says so, and a zero that is
            unmeasured is not the same number"
    (let [w (log! (stamp! "clean" 61) "Sun Sep 27 01:42:25 EAT 2026 editMessageText ok=true res=897")]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :authored}))
      (let [a (gl/audit *root* {:alive? (alive-fn)})
            r (gl/report a)]
        (is (= 0 (count (:delivery-blind a))))
        (is (str/includes? r "delivery-blind 0"))
        (is (not (str/includes? r "DELIVERY-BLIND (dispatch ack rejected)"))
            "no rows when nothing was blind")))))

;; ---------- 2026-11-15: :error is not the only non-success authoring verdict ----------
;; Measured on the live goals root before writing this: `grep -ho ":result [a-z:-]*"
;; */authoring.edn | sort | uniq -c` -> 5 :authored, 5 :error, 1 :invalid,
;; 1 :timed-out. goal_bridge.clj:382 writes FOUR non-success results
;; (:stalled / :timed-out / :invalid / :error), and classify only recognised :error.
;; So `exp-max-report-studio` ({:result :timed-out}, 75 rounds, 45 min, no
;; config.edn, no run.log — nothing was ever built) read as :receipted.
;; The second half of the measurement is what stops this from over-reaching:
;; `make-rsi-do-this-983334` carries {:result :invalid} at 2026-09-26T22:42 and
;; ALSO carries config.edn + run.log written 2026-09-30T05:06, i.e. a resume
;; succeeded four days later and its run.log ends "GOAL ALREADY SATISFIED".
;; Blanket-reclassifying every non-success verdict would have called a recovered
;; goal dead — a false FAILED, the mirror image of the false :receipted.

(deftest timed-out-authoring-with-no-later-receipt-is-errored-test
  (testing ":timed-out is a non-success verdict, and with nothing after it the
            goal never produced a config — so it is not a receipt of work"
    (let [w (stamp! "exp-max-report-studio" 75)]
      (spit (str (fs/path w "authoring.edn"))
            (pr-str {:model "ali/qwen3.8-max" :rounds 75 :duration-ms 2700105
                     :result :timed-out :finished "2026-09-12T19:37:04Z"}))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :errored (:status row)))
        (is (= :timed-out (:authoring-verdict row)))
        (is (false? (:authoring-recovered? row))
            "no later receipt, so nothing recovered it")
        (is (str/includes? (str (:authoring-error row)) "timed-out")
            "the report has something to print even with no :error message")))))

(deftest invalid-authoring-carries-its-problems-test
  (testing ":invalid carries :problems rather than :error, and that text is what
            the report must name — a verdict with no reason is a dead end"
    (let [w (stamp! "bad-config" 41)]
      (spit (str (fs/path w "authoring.edn"))
            (pr-str {:result :invalid :rounds 41
                     :problems "Config missing required key: :name"}))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :errored (:status row)))
        (is (= :invalid (:authoring-verdict row)))
        (is (str/includes? (str (:authoring-error row)) "Config missing required key")
            ":problems is the reason when :error is absent")))))

(deftest stalled-authoring-is-also-not-a-receipt-test
  (testing ":stalled is the fourth non-success verdict goal_bridge writes"
    (let [w (stamp! "stalled" 17)]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :stalled :rounds 17}))
      (is (= :errored (:status (gl/classify w {:alive? (alive-fn)}))))
      (is (= :stalled (:authoring-verdict (gl/classify w {:alive? (alive-fn)})))))))

(deftest bad-authoring-recovered-by-a-later-run-stays-receipted-test
  (testing "a non-success verdict older than a receipt that proves the goal went
            on is HISTORY, not a live failure — the live make-rsi-do-this-983334
            shape (:invalid 2026-09-26, config.edn + run.log 2026-09-30)"
    (let [w (stamp! "recovered" 41)
          au (fs/path w "authoring.edn")]
      (spit (str au) (pr-str {:result :invalid :problems "Config missing required key: :name"}))
      ;; push the verdict into the past, then let a later attempt leave a receipt
      (fs/set-last-modified-time au (java.time.Instant/parse "2026-09-26T22:42:25Z"))
      (spit (str (fs/path w "run.log")) "iter-000 GOAL ALREADY SATISFIED\n")
      (fs/set-last-modified-time (fs/path w "run.log")
                                 (java.time.Instant/parse "2026-09-30T05:06:29Z"))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :receipted (:status row))
            "calling this ERRORED would be a false FAILED: something newer proved it ran")
        (is (true? (:authoring-recovered? row)))
        (is (= :invalid (:authoring-verdict row))
            "the bad verdict stays visible — recovered is not the same as never-happened")
        (is (contains? (:receipts row) :run-log))))))

(deftest authored-verdict-is-not-recovered-and-not-errored-test
  (testing "no-regression: :authored is a success, so it carries no verdict and no
            recovery annotation (a false :authoring-recovered? would be noise)"
    (let [w (stamp! "fine" 9)]
      (spit (str (fs/path w "authoring.edn")) (pr-str {:result :authored :rounds 9}))
      (let [row (gl/classify w {:alive? (alive-fn)})]
        (is (= :receipted (:status row)))
        (is (nil? (:authoring-verdict row)))
        (is (false? (:authoring-recovered? row)))))))

(deftest audit-groups-recovered-as-an-annotation-not-a-bucket-test
  (testing "recovered rows stay in :receipted and are listed separately, so the
            partition still sums — the same contract :delivery-blind holds"
    (let [bad (stamp! "timed-out-goal" 75)
          rec (stamp! "resumed-goal" 41)]
      (spit (str (fs/path bad "authoring.edn")) (pr-str {:result :timed-out}))
      (spit (str (fs/path rec "authoring.edn")) (pr-str {:result :invalid :problems "x"}))
      (fs/set-last-modified-time (fs/path rec "authoring.edn")
                                 (java.time.Instant/parse "2026-09-26T22:42:25Z"))
      (spit (str (fs/path rec "run.log")) "GOAL ALREADY SATISFIED\n")
      (fs/set-last-modified-time (fs/path rec "run.log")
                                 (java.time.Instant/parse "2026-09-30T05:06:29Z"))
      (let [a (gl/audit *root* {:alive? (alive-fn)})]
        (is (= ["timed-out-goal"] (mapv :name (:errored a))))
        (is (= ["resumed-goal"] (mapv :name (:recovered-after-bad-authoring a))))
        (is (= 1 (count (:receipted a)))
            "annotation, not a bucket: the recovered goal is still counted once")
        (is (= 2 (:workspaces a)))
        (is (= (:workspaces a)
               (+ (count (:errored a)) (count (:receipted a))))
            "partition sums")))))

(deftest report-names-recovered-and-unrecovered-separately-test
  (testing "the human receipt distinguishes a dead verdict from a historical one,
            because they call for opposite responses: re-dispatch versus nothing"
    (let [bad (stamp! "dead-goal" 75)
          rec (stamp! "resumed-goal" 41)]
      (spit (str (fs/path bad "authoring.edn"))
            (pr-str {:result :timed-out :finished "2026-09-12T19:37:04Z"}))
      (spit (str (fs/path rec "authoring.edn"))
            (pr-str {:result :invalid :problems "Config missing required key: :name"}))
      (fs/set-last-modified-time (fs/path rec "authoring.edn")
                                 (java.time.Instant/parse "2026-09-26T22:42:25Z"))
      (spit (str (fs/path rec "run.log")) "GOAL ALREADY SATISFIED\n")
      (fs/set-last-modified-time (fs/path rec "run.log")
                                 (java.time.Instant/parse "2026-09-30T05:06:29Z"))
      (let [r (gl/report (gl/audit *root* {:alive? (alive-fn)}))]
        (is (str/includes? r "ERRORED"))
        (is (str/includes? r "dead-goal"))
        (is (str/includes? r "RECOVERED"))
        (is (str/includes? r "resumed-goal"))
        (is (str/includes? r "later receipt"))
        (is (not (str/includes? r "ERRORED (authoring did not succeed): resumed-goal"))
            "a recovered goal is not named as a live failure")))))
