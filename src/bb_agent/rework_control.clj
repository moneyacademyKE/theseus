(ns bb-agent.rework-control
  "Structured rework feedback routing, exhausted-rework escalation,
   generation/attempt write fence, and stale inline-card guard
   (ported from LoopForge engine/node_outcome.go + engine/bot_control.go)."
  (:require [clojure.string :as str]))

(defn decide-failure
  "Decide what happens when a workflow/goal node fails:
   - Within retry budget: :retry carrying concrete error into :feedback.
   - Rework requested but MaxReworks exhausted: park on :waiting-approval
     (human decision) instead of hard-failing and throwing away upstream output.
   - Rework requested within budget: :rework back to :reject-to with :feedback.
   - Otherwise: :fail."
  [{:keys [node-id reject-to attempt max-retries rework-count max-reworks error]}]
  (let [attempt (long (or attempt 1))
        max-retries (long (or max-retries 0))
        rework-count (long (or rework-count 0))
        max-reworks (max 1 (long (or max-reworks 1)))
        err-str (str (or error "node failed"))
        has-reject? (and (string? reject-to) (not (str/blank? reject-to)))
        rework-exhausted? (and has-reject? (>= rework-count max-reworks))]
    (cond
      (< attempt (inc max-retries))
      {:action :retry
       :node-id node-id
       :feedback err-str}

      rework-exhausted?
      {:action :waiting-approval
       :node-id node-id
       :reject-to reject-to
       :reason (str "Upstream node " reject-to " exhausted rework budget (MaxReworks="
                    max-reworks "); parked for human decision. Agent reason: " err-str)}

      has-reject?
      {:action :rework
       :node-id node-id
       :reject-to reject-to
       :feedback err-str}

      :else
      {:action :fail
       :node-id node-id
       :error err-str})))

(defn should-commit-outcome?
  "Exclusive-execute write fence for one node outcome.
   - Generation mismatch => stale lease, reject.
   - Node no longer :running or :pending-input => sibling was reset, reject.
   - Passing outcome may still land after attempt bump; failures stay attempt-scoped."
  [run node-run generation attempt success?]
  (boolean
   (and node-run
        (= (:generation run) generation)
        (contains? #{:running :pending-input} (:status node-run))
        (or success? (= (:attempt node-run) attempt)))))

(defn check-node-expectation
  "Verify an inline card / chat action still matches the exact node status,
   attempt, and rework count shown when the card was rendered.
   Returns nil when valid, or an error string when stale."
  [run {:keys [node-id status attempt rework-count]}]
  (let [node (get-in run [:nodes node-id])]
    (when (or (nil? node)
              (not= (:status node) status)
              (not= (:attempt node) attempt)
              (not= (long (or (:rework-count node) 0)) (long (or rework-count 0))))
      (str "Stale action for node " node-id " — state has changed; check the latest update."))))
