(ns e2e.ops-stats-test
  "Pins `bb stats` (audit F5): one-screen ops summary built ONLY from
   ledgers that already exist -- usage.edn, outbox depth/dead-letters,
   goal run.log verdicts. No new infra."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.stats :as stats]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def ^:dynamic *home* nil)

(defn- fresh-home [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "ops-stats-test"}))]
    (binding [*home* tmp]
      (with-redefs [config/home (constantly tmp)]
        (f)))))

(use-fixtures :each fresh-home)

(defn- seed-ledgers! []
  (let [h *home*]
    ;; usage: two events
    (fs/create-dirs (fs/path h "state"))
    (spit (str (fs/path h "state" "usage.edn"))
          (pr-str [{:usage/event :turn :provider :a :model "m"
                    :tokens/input 10 :tokens/output 5 :tokens/cache-read 0
                    :tokens/cache-write 0 :tokens/total 15 :cost/estimate-usd 0.01
                    :created/at "2026-09-09T00:00:00Z" :session/id "s1"}
                   {:usage/event :turn :provider :a :model "m"
                    :tokens/input 1 :tokens/output 1 :tokens/cache-read 0
                    :tokens/cache-write 0 :tokens/total 2 :cost/estimate-usd 0.0
                    :created/at "2026-09-09T01:00:00Z" :session/id "s1"}]))
    ;; outbox: 2 pending, 1 dead
    (fs/create-dirs (fs/path h "state" "outbox" "dead"))
    (spit (str (fs/path h "state" "outbox" "a.edn")) {:id "a"})
    (spit (str (fs/path h "state" "outbox" "b.edn")) {:id "b"})
    (spit (str (fs/path h "state" "outbox" "dead" "c.edn")) {:id "c"})
    ;; goals: fulfilled, stalled, no-run, a logs dir (excluded), 1 active
    (doseq [[ws verdict] [["ws-fulfilled" "[t] INFO   GOAL FULFILLED {:world {:ok \"pass\"}}"]
                          ["ws-stalled"   "[t] HALT   HALT: stall {:reason :stall}"]
                          ["ws-empty"     nil]]]
      (fs/create-dirs (fs/path h "goals" ws))
      (when verdict
        (spit (str (fs/path h "goals" ws "run.log")) (str verdict "\n"))))
    (fs/create-dirs (fs/path h "goals" "logs"))
    (spit (str (fs/path h "goals" "active.edn"))
          (pr-str {"-100" {196 {:workspace "ws-fulfilled"}}}))))

(deftest summary-counts-from-fixtures
  (seed-ledgers!)
  (let [{:keys [usage outbox goals]} (stats/summary)]
    (is (= 2 (:usage/events usage)))
    (is (= 17 (:tokens/total usage)))
    (is (= {:outbox/pending 2 :outbox/dead 1} outbox))
    (is (= 1 (:goal/active goals)))
    (is (= 3 (:goal/workspaces goals)))
    (is (= 1 (:fulfilled goals)))
    (is (= 1 (:stalled goals)))
    (is (= 1 (:no-run goals)))
    (is (zero? (:failed goals)))))

(deftest empty-home-is-all-zeros
  (let [{:keys [outbox goals]} (stats/summary)]
    (is (= {:outbox/pending 0 :outbox/dead 0} outbox))
    (is (zero? (:goal/workspaces goals)))
    (is (zero? (:goal/active goals)))))

(deftest bb-stats-shell-task
  (seed-ledgers!)
  (let [{:keys [out err exit]} (p/shell {:out :string
                                         :err :string
                                         :continue true
                                         :extra-env {"THESEUS_HOME" *home*}}
                                        "bb" "stats")]
    (is (zero? exit))
    (is (str/includes? out "Theseus stats"))
    (is (str/includes? out "2 pending, 1 dead-lettered"))
    (is (str/includes? out "fulfilled 1, stalled 1"))
    (is (empty? err))))

;; ---------- 2026-11-04: the second door of the delivery-blind finding ----------
;; Red first. `bb goal-liveness` learned :delivery-blind on 2026-11-04 and the
;; ops screen did not, so a measurement only one command reports stayed a
;; measurement nobody runs (the 2026-10-12 lesson). Worse: this summary was
;; missing TWO buckets goal-liveness already carried — :errored (2026-11-03) and
;; :delivery-blind — so the line printed 20 of 24 workspaces and read as healthy
;; while four dispatches had each died on a provider timeout with their ack
;; refused by the wire. A partition that does not sum is a false-clean, the
;; eighth in this lane; so the line now prints its own denominator and says so
;; when the sum is short. The correction to this file's own history: an earlier
;; turn claimed this wiring landed and it had been REVERTED by a balance guard
;; (improvements.md, 2026-11-04 correction). A wins entry is a receipt, so it is
;; written after the read-back, never before it.
(deftest liveness-summary-carries-errored-and-delivery-blind
  (seed-ledgers!)
  (let [ws (fs/path *home* "goals" "ws-blind")]
    (fs/create-dirs ws)
    (spit (str (fs/path ws "dispatch.edn"))
          (pr-str {:name "ws-blind" :authoring-pid 999999
                   :dispatched-at "2026-11-04T07:56:50Z"}))
    (spit (str (fs/path ws "authoring.edn"))
          (pr-str {:model "m" :rounds 3 :duration-ms 900000 :result :error
                   :error "java.net.http.HttpTimeoutException: request timed out"}))
    (spit (str (fs/path ws "launch.log"))
          "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found\n")
    (let [s (stats/liveness-summary)]
      (is (= 1 (:goal/errored s)) "an authoring that returned an error is its own bucket")
      (is (= ["ws-blind"] (:goal/errored-names s)))
      (is (= 1 (:goal/delivery-blind s)) "and its refused ack is annotated")
      (is (= ["ws-blind"] (:goal/blind-names s)) "named, not just counted")
      (is (= 4 (:goal/workspaces-audited s)) "the denominator, so the sum is checkable")
      ;; SUPERSEDED 2026-11-04, corrected against the fixture rather than my
      ;; intention: this asserted (zero? (:goal/receipted s)), which is false by
      ;; design — seed-ledgers! creates ws-fulfilled and ws-stalled, both with a
      ;; run.log, so 2 rows ARE receipted. What the rule says is that an authoring
      ;; which returned an error is not AMONG them, and that the buckets sum to
      ;; the denominator the line prints.
      (is (= 2 (:goal/receipted s)) "the two seeded run.log workspaces, not the errored one")
      (is (= 4 (+ (:goal/live s) (:goal/receipted s) (:goal/dead-authoring s)
                  (:goal/errored s) (:goal/abandoned s) (:goal/unstamped s)))
          "the partition sums to :goal/workspaces-audited"))))

(deftest print-liveness-renders-the-new-buckets-and-names
  (seed-ledgers!)
  (let [ws (fs/path *home* "goals" "ws-blind")]
    (fs/create-dirs ws)
    (spit (str (fs/path ws "dispatch.edn")) (pr-str {:name "ws-blind" :authoring-pid 999999}))
    (spit (str (fs/path ws "authoring.edn")) (pr-str {:result :error :error "timeout"}))
    (spit (str (fs/path ws "launch.log"))
          "Wed Nov 04 10:56:51 EAT 2026 sendMessage ok=false res=Bad Request: chat not found\n")
    (let [out (with-out-str (stats/print-liveness! (stats/liveness-summary)))]
      (is (str/includes? out "1 errored") "the count is on the ops line itself")
      (is (str/includes? out "1 delivery-blind"))
      (is (str/includes? out "delivery-blind: ws-blind")
          "and so is the name — a count with no subject is armory's PASS-28555")
      (is (not (str/includes? out "PARTITION"))
          "4 workspaces: 3 no-receipt + 1 errored; the buckets sum, so no warning"))))

(deftest partition-warning-fires-when-a-bucket-is-missing
  ;; Not a hypothetical: this is exactly how :errored and :delivery-blind went
  ;; missing from the ops line on 2026-11-03/04 while goal-liveness carried both.
  (seed-ledgers!)
  (let [out (with-out-str (stats/print-liveness!
                           (assoc (stats/liveness-summary) :goal/workspaces-audited 9)))]
    (is (str/includes? out "PARTITION DOES NOT SUM"))
    (is (str/includes? out "9 workspace(s) audited") "names the denominator it disagreed with")))

(deftest liveness-absent-root-reports-no-count
  (let [s (stats/liveness-summary)]
    (is (= {:goal/liveness-status :missing} s)
        "not-found is not zero: no counts are invented for a root that was not read")
    (is (nil? (:goal/delivery-blind s)))
    (is (nil? (:goal/errored s)))
    (let [out (with-out-str (stats/print-liveness! s))]
      (is (str/includes? out "not-found, not zero")))))
