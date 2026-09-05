(ns axiom.lock
  "Exclusive file lock via pidfile with process-liveness check.

  Single-host, single-agent per run. A stale lock (holder died) is
  silently stolen; a live lock is refused -- two concurrent runs would
  fight over the same files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- current-pid
  "Best-effort current process id (Java 9+ ProcessHandle)."
  []
  (try (.pid (java.lang.ProcessHandle/current))
       (catch Exception _ (int (rand-int 100000)))))

(defn- process-alive?
  "True if a process with pid is currently running (kill -0)."
  [pid]
  (try
    (-> (ProcessBuilder. ["kill" "-0" (str pid)])
        .start .waitFor zero?)
    (catch Exception _ false)))

(defn acquire!
  "Create or steal a pidfile lock. Returns true if acquired.
  Throws ex-info if a live process holds it."
  [lock-path]
  (let [f (io/file lock-path)]
    (if (.exists f)
      (let [pid (try (-> f slurp str/trim Long/parseLong) (catch Exception _ nil))]
        (if (and pid (process-alive? pid))
          (throw (ex-info "Lock held by a live process"
                          {:pid pid :path lock-path}))
          (do (spit f (current-pid)) true)))
      (do (some-> f .getParentFile (.mkdirs))
          (spit f (current-pid)) true))))

(defn release!
  "Remove the pidfile lock. Safe to call when not held."
  [lock-path]
  (let [f (io/file lock-path)]
    (when (.exists f)
      (.delete f))))
