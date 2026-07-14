(ns bb-agent.daemon
  (:require [bb-agent.config :as config]
            [bb-agent.schedule :as schedule]))

(defn- run-cycle! [cfg]
  (count (schedule/run-all-schedules! cfg)))

(defn start! [{:keys [once? max-runs interval-ms]}]
  (let [cfg (config/load-config)
        limit (if once? 1 max-runs)
        interval (or interval-ms 60000)]
    (loop [cycles 0
           runs 0]
      (if (and limit (>= cycles limit))
        {:cycles cycles :runs runs}
        (let [ran (run-cycle! cfg)]
          (when (and (not once?) (or (nil? limit) (< (inc cycles) limit)))
            (Thread/sleep interval))
          (recur (inc cycles) (+ runs ran)))))))
