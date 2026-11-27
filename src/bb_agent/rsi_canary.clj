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
