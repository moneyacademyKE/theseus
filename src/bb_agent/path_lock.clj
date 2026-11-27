(ns bb-agent.path-lock
  "Advisory per-path write lock (ported from OpenCrabs path_lock.rs).
   Serializes concurrent writers targeting the same file using a lockfile
   under <home>/locks/paths/<sha256-16>.lock (never littering repos).
   Deliberately weak: held for one write only, waits up to 750ms, and degrades
   gracefully to writing anyway so a crashed holder can never wedge work."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config])
  (:import [java.io RandomAccessFile]
           [java.security MessageDigest]))

(def ^:private wait-ms 750)
(def ^:private poll-ms 25)

(defn- sha256-16 [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 bs)))))

(defn lock-path
  "Resolve the lockfile path under <home>/locks/paths/ for `target`, or nil."
  [target]
  (try
    (let [abs (try (str (fs/canonicalize target))
                   (catch Exception _ (str (fs/absolutize target))))
          dir (fs/path (config/home) "locks" "paths")]
      (fs/create-dirs dir)
      (str (fs/path dir (str (sha256-16 abs) ".lock"))))
    (catch Exception _ nil)))

(defn with-path-lock
  "Acquire an advisory OS file lock for `target` (waiting up to 750ms), call
   `(f {:held? bool})`, and always release the lock before returning."
  [target f]
  (if-let [lp (lock-path target)]
    (let [raf (try (RandomAccessFile. ^String lp "rw") (catch Exception _ nil))]
      (if-not raf
        (f {:held? false})
        (let [ch (.getChannel raf)
              deadline (+ (System/currentTimeMillis) wait-ms)
              lock (loop []
                     (let [lk (try (.tryLock ch)
                                   (catch Exception _ nil))]
                       (cond
                         lk lk
                         (>= (System/currentTimeMillis) deadline) nil
                         :else (do (Thread/sleep poll-ms) (recur)))))]
          (try
            (f {:held? (boolean lock)})
            (finally
              (when lock (try (.release lock) (catch Exception _ nil)))
              (try (.close ch) (catch Exception _ nil))
              (try (.close raf) (catch Exception _ nil)))))))
    (f {:held? false})))
