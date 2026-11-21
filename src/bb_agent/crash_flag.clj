(ns bb-agent.crash-flag
  "Crash-flag survival record (`state/.crash-flag.edn`).

   Written atomically (born 0600) when a daemon dies mid-turn or via
   uncaught fatal exit, then read-and-cleared once on startup so the
   owner sees what task was interrupted instead of silent amnesia."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]))

(defn flag-file []
  (fs/path (config/home) "state" ".crash-flag.edn"))

(defn write-crash-flag!
  "Persist an atomic born-0600 crash record."
  [info]
  (let [record (merge {:ts (str (java.time.Instant/now))
                       :pid (.pid (java.lang.ProcessHandle/current))}
                      info)]
    (aio/write-private-file! (flag-file) (pr-str record))
    record))

(defn read-crash-flag
  "Read the persisted crash flag map, or nil when absent/unreadable."
  []
  (let [v (aio/read-edn-safe (flag-file) nil)]
    (when (map? v) v)))

(defn clear-crash-flag! []
  (fs/delete-if-exists (flag-file)))

(defn format-notice [{:keys [reason task ts pid]}]
  (str "Previous process terminated unexpectedly"
       (when task (str " while working on: " task))
       (when reason (str " (" reason ")"))
       (when pid (str " [pid " pid "]"))
       (when ts (str " @ " ts))))

(defn consume-crash-flag!
  "Read and delete the crash flag once on startup. Returns {:record map :notice str}
   or nil when the prior shutdown was clean."
  []
  (when-let [rec (read-crash-flag)]
    (clear-crash-flag!)
    {:record rec :notice (format-notice rec)}))
