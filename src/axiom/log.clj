(ns axiom.log
  "Structured EDN per-iteration logging + human-readable terminal events.

  Per decision-log D2: logs are EDN. clojure.data.json is NOT bundled in
  Babashka (verified empirically); EDN is native, zero-dep, and round-trips
  into Clojure tooling trivially. Each iteration writes one EDN record plus
  a line to a run-wide events.log."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn- now [] (str (Instant/now)))
(defn- ensure-dir [d] (some-> d io/file (.mkdirs)))

(defn event!
  "Append a human-readable event to the run-wide events.log and print it.
  level: :info :warn :error :halt. Pass nil log-dir for print-only."
  ([log-dir level msg] (event! log-dir level msg {}))
  ([log-dir level msg data]
   (let [line (format "[%s] %-6s %s%s"
                      (now) (-> level name str/upper-case) msg
                      (if (seq data) (str " " (pr-str data)) ""))]
     (when log-dir
       (ensure-dir log-dir)
       (spit (io/file log-dir "events.log") (str line "\n") :append true))
     (println line))))

(defn iteration!
  "Write one structured EDN record for an iteration. Side-effect only."
  [log-dir iteration record]
  (when log-dir
    (ensure-dir log-dir)
    (let [path  (io/file log-dir (format "iter-%03d.edn" iteration))
          enriched (assoc record :ts (now) :iteration iteration)]
      (spit path (with-out-str (pp/pprint enriched))))))

;; ---------- Phase 3: diagnostic bundle ----------
;; Per ROADMAP Phase 3: from a halt bundle alone, a human can name the
;; blocker in under 2 minutes. The bundle is self-contained -- the halt
;; action, the last N iteration records, and the reason -- written as one
;; EDN file that round-trips into any Clojure tooling.

(def ^:private iter-file-re #"^iter-(\d+)\.edn$")

(defn- iter-number
  "Parse the zero-padded iteration number out of an `iter-NNN.edn` filename."
  [name]
  (some-> (re-find iter-file-re name) second Integer/parseInt))

(defn read-iterations
  "Read the last N iteration EDN records from `log-dir`, oldest first.
  Returns a vector of parsed maps. Empty vector if log-dir is nil/absent
  or has no iteration files. Zero-padded names sort correctly lexically
  up to 999 iterations (the default max-iters ceiling)."
  [log-dir n]
  (when (and log-dir (.exists (io/file log-dir)))
    (let [dir   (io/file log-dir)
          files (->> (.listFiles dir)
                     (filter #(re-find iter-file-re (.getName %)))
                     (sort-by #(or (iter-number (.getName %)) 0))
                     vec)
          picked (if (> (count files) n)
                   (subvec files (- (count files) n))
                   files)]
      (mapv (fn [f] (edn/read-string (slurp f))) picked))))

(defn halt-bundle!
  "Phase 3: write a self-contained diagnostic bundle to
  `<log-dir>/halt-bundle.edn`. Contains the halt action, the last N (default
  20) iteration records, and the reason -- enough to name the blocker from
  the bundle alone (ROADMAP Phase 3 done-when). Returns the bundle map.
  No-op on the filesystem when log-dir is nil (still returns the bundle)."
  ([log-dir action] (halt-bundle! log-dir action 20))
  ([log-dir action n]
   (let [bundle {:ts         (now)
                 :reason     (:reason action)
                 :halt       (dissoc action :type)
                 :iterations (or (read-iterations log-dir n) [])}]
     (when log-dir
       (ensure-dir log-dir)
       (spit (io/file log-dir "halt-bundle.edn")
             (with-out-str (pp/pprint bundle))))
     bundle)))
