#!/usr/bin/env bb
;; Bounded watcher for parallel goal-run evidence: polls goals/active.edn
;; every poll-ms, prints a timeline line per state change, exits early when
;; two entries coexist (runner-level parallelism) or after max-seconds.
;; Usage: bb scripts/watch_parallel.bb [max-seconds] [poll-ms]
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(def home (str (System/getProperty "user.home") "/theseus"))
(def registry (io/file home "goals/active.edn"))
(def max-s (or (some-> *command-line-args* first parse-long) 480))
(def poll-ms (or (some-> *command-line-args* second parse-long) 5000))
(def t0 (System/currentTimeMillis))

(defn elapsed [] (quot (- (System/currentTimeMillis) t0) 1000))

(defn snapshot []
  (if-not (.exists registry)
    {}
    (try (edn/read-string (slurp registry)) (catch Exception _ :unreadable))))

(defn ts []
  (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")
           (java.time.LocalDateTime/now (java.time.ZoneId/of "UTC"))))

(loop [last-shape nil]
  (let [snap (snapshot)
        shape (if (map? snap) (vec (sort (keys snap))) :other)]
    (when (not= shape last-shape)
      (println (format "[%s +%3ds] registry: %s" (ts) (elapsed)
                       (if (map? snap)
                         (str (count snap) " entries " (pr-str (mapv (comp :name val) snap)))
                         (str snap)))))
    (flush)
    (cond
      (and (map? snap) (= 2 (count snap)))
      (do (println (format "[%s +%3ds] ✅ TWO RUNS COEXIST: %s"
                           (ts) (elapsed) (pr-str (mapv (comp :name val) snap))))
          (System/exit 0))
      (> (elapsed) max-s)
      (do (println (format "[%s] ⏱ %ds elapsed, no coexistence observed" (ts) max-s))
          (System/exit 1))
      :else (do (Thread/sleep poll-ms) (recur shape)))))
