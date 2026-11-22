(ns bb-agent.warm-compaction
  "Idle-gap warm compaction scheduler.
   After a turn leaves context >= min-tokens (default 100k), schedules
   compaction during the idle gap (default 180s, inside Anthropic's 5-min
   prompt cache TTL) so compaction pays cached-read pricing and 0s user
   latency. When the user returns early: aborts if compaction isn't needed
   yet, or awaits the in-flight compaction if the turn would compact anyway.")

(def default-min-tokens 100000)
(def default-idle-ms (* 3 60 1000))

(defn create-scheduler
  ([] (create-scheduler {}))
  ([{:keys [min-tokens idle-ms]
     :or {min-tokens default-min-tokens
          idle-ms default-idle-ms}}]
   {:min-tokens (long min-tokens)
    :idle-ms (long idle-ms)
    :jobs (atom {})}))

(defn cancel!
  "Cancel any pending/in-flight warm compaction for `session-id`."
  [sched session-id]
  (when-let [{:keys [cancelled? fut]} (get @(:jobs sched) session-id)]
    (reset! cancelled? true)
    (future-cancel fut)
    (swap! (:jobs sched) dissoc session-id)
    true))

(defn schedule-warm!
  "Schedule background warm compaction for `session-id` when `token-estimate`
   meets `:min-tokens`. Replaces any prior pending job for that session."
  [sched session-id token-estimate compact-fn]
  (when (and session-id
             (number? token-estimate)
             (>= (long token-estimate) (:min-tokens sched)))
    (cancel! sched session-id)
    (let [cancelled? (atom false)
          idle-ms (:idle-ms sched)
          fut (future
                (try
                  (Thread/sleep idle-ms)
                  (when-not @cancelled?
                    (compact-fn))
                  (catch InterruptedException _ nil)))]
      (swap! (:jobs sched) assoc session-id {:cancelled? cancelled?
                                             :fut fut
                                             :scheduled-at (System/currentTimeMillis)})
      fut)))

(defn before-turn!
  "Call at the start of a user turn for `session-id`.
   - If `:needs-compaction?` is true and a warm job exists, awaits and returns its result.
   - Otherwise cancels any pending warm job and returns nil."
  [sched session-id {:keys [needs-compaction? timeout-ms] :or {timeout-ms 30000}}]
  (if-let [{:keys [fut]} (get @(:jobs sched) session-id)]
    (if needs-compaction?
      (try
        (let [res (deref fut timeout-ms ::timeout)]
          (swap! (:jobs sched) dissoc session-id)
          (when-not (= ::timeout res) res))
        (catch Exception _
          (swap! (:jobs sched) dissoc session-id)
          nil))
      (do (cancel! sched session-id)
          nil))
    nil))
