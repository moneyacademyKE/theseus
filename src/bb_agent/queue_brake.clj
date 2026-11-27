(ns bb-agent.queue-brake
  "Queue-depth brake for scheduled/cron jobs (ported from LoopForge
   schedule_execution.go). Caps concurrent/queued executions per queue key so
   upstream outages never pile up runaway cron backlogs or burn API credits.")

(defn create-brake
  ([] (create-brake {}))
  ([{:keys [max-depth] :or {max-depth 2}}]
   {:max-depth (max 1 (long max-depth))
    :counts (atom {})}))

(defn current-depth [brake queue-key]
  (long (get @(:counts brake) queue-key 0)))

(defn try-acquire!
  "Attempt to claim a slot for `queue-key`. Returns true if acquired, false if
   at/above `:max-depth`."
  [brake queue-key]
  (let [limit (:max-depth brake)
        acquired? (atom false)]
    (swap! (:counts brake)
           (fn [m]
             (let [c (long (get m queue-key 0))]
               (if (< c limit)
                 (do (reset! acquired? true)
                     (assoc m queue-key (inc c)))
                 (do (reset! acquired? false)
                     m)))))
    @acquired?))

(defn release!
  "Release one slot for `queue-key`."
  [brake queue-key]
  (swap! (:counts brake)
         (fn [m]
           (let [c (long (get m queue-key 0))]
             (if (<= c 1)
               (dissoc m queue-key)
               (assoc m queue-key (dec c))))))
  nil)
