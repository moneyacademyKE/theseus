(ns bb-agent.rsi-canary
  "Deterministic RSI canary trials with asset pinning and fail-closed replay
   validation (ported from LoopForge evolution_canary.go + evolution_replay.go).
   Live brainfiles/skills are never mutated during a trial: candidate bodies
   are selected once at run start from hash(run-id, proposal-id), inherited by
   child subagents, and compared against control runs by pin hash."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn canary-bucket
  "Deterministic integer in [0, 99] for (run-id, proposal-id)."
  [run-id proposal-id]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str run-id ":" proposal-id) "UTF-8"))
        n (bit-or (bit-shift-left (bit-and (aget bs 0) 0xff) 8)
                  (bit-and (aget bs 1) 0xff))]
    (mod n 100)))

(defn assign-canaries
  "Return the vector of proposal IDs active for `run-id`.
   - Replays never enter live canary trials.
   - Child runs inherit their parent's canary arm verbatim."
  [run-id proposals {:keys [parent-run-id canary-ids replay?]}]
  (cond
    replay? []
    (and (string? parent-run-id) (not (str/blank? parent-run-id))) (vec (or canary-ids []))
    :else
    (->> proposals
         (filter (fn [{:keys [id percent]}]
                   (let [pct (long (or percent 0))]
                     (and (pos? pct)
                          (< (canary-bucket run-id id) pct)))))
         (mapv :id))))

(defn validate-replay
  "Fail-closed validation before replaying a historical run under a candidate
   RSI proposal. Refuses runs bound to external repositories, external
   credentials, or manual approval gates."
  [{:keys [repositories nodes]}]
  (cond
    (seq repositories)
    {:ok? false :error "Replay refused: repository-bound runs with external Git/MR side effects cannot be replayed safely."}

    (some #(seq (:credential-env %)) nodes)
    {:ok? false :error "Replay refused: workflow contains nodes with external credentials."}

    (some #(= :approval (:type %)) nodes)
    {:ok? false :error "Replay refused: workflow contains interactive approval gates."}

    :else
    {:ok? true}))

(defn score-run
  "3-point run grade: 2 clean pass, 1 passed after rework/retry, 0 failed,
   nil for non-terminal/cancelled runs."
  [{:keys [status rework-count]}]
  (case status
    :failed 0
    :succeeded (if (pos? (long (or rework-count 0))) 1 2)
    nil))

(defn- mean [xs]
  (when (seq xs)
    (/ (double (reduce + 0 xs)) (double (count xs)))))

(defn evaluate-canary
  "Evaluate canary trial scores (0..2). Early-stops at >=3 runs if candidate
   trails control by >= 1.0 point; decides :pass / :fail once both arms reach min-runs."
  [{:keys [candidate-scores control-scores min-runs] :or {min-runs 5}}]
  (let [cn (count candidate-scores)
        kn (count control-scores)
        cavg (mean candidate-scores)
        kavg (mean control-scores)]
    (cond
      (and (>= cn 3) (>= kn 3) (<= (- cavg kavg) -1.0))
      {:verdict :early-stop :candidate-avg cavg :control-avg kavg}

      (or (< cn min-runs) (< kn min-runs))
      {:verdict :collecting :candidate-runs cn :control-runs kn}

      (< cavg (- kavg 0.35))
      {:verdict :fail :candidate-avg cavg :control-avg kavg}

      :else
      {:verdict :pass :candidate-avg cavg :control-avg kavg})))

(defn evaluate-post-release-watch
  "Compare post-release run scores against pre-release baseline. Flags :degraded
   when >=3 post runs average >=0.5 points below baseline."
  [{:keys [baseline-scores post-scores min-runs] :or {min-runs 3}}]
  (let [bn (count baseline-scores)
        pn (count post-scores)
        bavg (mean baseline-scores)
        pavg (mean post-scores)]
    (cond
      (or (< bn min-runs) (< pn min-runs))
      {:verdict :insufficient-data :baseline-runs bn :post-runs pn}

      (<= (- pavg bavg) -0.5)
      {:verdict :degraded :baseline-avg bavg :post-avg pavg}

      :else
      {:verdict :healthy :baseline-avg bavg :post-avg pavg})))
