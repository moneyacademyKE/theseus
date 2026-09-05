(ns axiom.lock
  "Exclusive file lock via pidfile with process-liveness check.

  Single-host, single-agent per run. A stale lock (holder died) is
  silently stolen; a live lock is refused -- two concurrent runs would
  fight over the same files.

  Concurrency notes (stress-tested):
  - Acquisition is (.createNewFile f): atomic on POSIX, so two
    concurrent acquirers can never both see 'missing' and both win.
  - The winner writes its pid right after creating the file; a loser
    that reads an EMPTY file (holder mid-write) retries briefly before
    judging the lock stale. That read window is the race v2 had.
  - release! only deletes the file when it still names our own pid,
    so a thief who stole our lock can't be cleaned up by us later.
  (A kernel FileChannel.tryLock was tried first -- strictly correct,
  but Babashka's sci sandbox refuses .release on sun.nio.ch classes.)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private pid-read-deadline-ms 500)
(def ^:private pid-read-poll-ms 25)

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

(defn- parse-pid
  "Long pid from lockfile content, or nil."
  [s]
  (try (-> s str/trim Long/parseLong)
       (catch Exception _ nil)))

(defn- read-holder-pid
  "Read the holder's pid, retrying while the file is still empty (the
  winner creates the file atomically, then writes its pid). Returns
  nil if unreadable or still empty after the deadline -- at which
  point the lock is treated as stale."
  [f]
  (let [deadline (+ (System/currentTimeMillis) pid-read-deadline-ms)]
    (loop []
      (let [pid (some-> f slurp parse-pid)]
        (cond
          pid pid
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep pid-read-poll-ms) (recur))
          :else nil)))))

(defn acquire!
  "Create or steal a pidfile lock. Returns true if acquired.
  Throws ex-info if a live process holds it."
  [lock-path]
  (let [f    (io/file lock-path)
        _    (some-> f .getParentFile (.mkdirs))
        won? (.createNewFile f)]           ; atomic -- exactly one winner
    (if won?
      (do (spit f (current-pid)) true)
      (let [pid (read-holder-pid f)]
        (if (and pid (process-alive? pid))
          (throw (ex-info "Lock held by a live process"
                          {:pid pid :path lock-path}))
          (do (spit f (current-pid)) true))))))

(defn release!
  "Remove the pidfile lock -- but only if it still names OUR pid (a
  thief who stole the lock owns it now). Safe to call when not held."
  [lock-path]
  (let [f (io/file lock-path)]
    (when (.exists f)
      (let [pid (parse-pid (slurp f))]
        (when (and pid (= pid (current-pid)))
          (.delete f))))))
