#!/usr/bin/env bb
;; verify_parallel.bb — acceptance verification over PERSISTED evidence only.
;; Task bk-5e14 #2: "reports overlap in topic 196/200 worker intervals and
;; no cross-topic marker leakage."
;;
;; Evidence sources (all on disk, zero live polling):
;;   goals/<name>/watch-args.edn  — topic mapping {:chat-id :thread-id :pid}
;;   goals/<name>/logs/*.edn      — ledger :ts per iteration (worker alive proof)
;;   goals/<name>/run.log         — runner stdout timestamps + verdict
;;   state/sessions/telegram-<chat>-topic-<t>.edn — session turns
;;   [optional] timeline file (watch_parallel.bb output) — registry coexistence
;;
;; Usage: bb scripts/verify_parallel.bb <topic-a> <topic-b> [timeline-file]
;; Exit 0 iff worker-interval overlap observed AND no cross-topic leakage.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def home (or (System/getenv "OPENCRABS_HOME")
              (str (System/getProperty "user.home") "/theseus")))
(def goals-root (io/file home "goals"))
(def ta (first *command-line-args*))
(def tb (second *command-line-args*))
(def timeline-file (nth *command-line-args* 2 nil))
(def topic-a (parse-long ta))
(def topic-b (parse-long tb))

;; Test-fixture markers: the distinctive subject token of each topic's probe
;; spec. Leakage = one topic's persisted evidence mentioning the other's token.
(def markers {topic-a #{"linkcheck"} topic-b #{"wordfreq" "mdtable"}})

(defn inst-ms [ts]
  (try (.toEpochMilli (java.time.Instant/parse (str ts))) (catch Exception _ nil)))

(defn read-edn [f] (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))

(defn topic-of [ws]
  (some-> (io/file ws "watch-args.edn") read-edn :thread-id))

(defn file-tss [f]
  (when (.exists (io/file f))
    (keep inst-ms (re-seq #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z" (slurp (str f))))))

(defn interval-for [ws]
  (let [lgs  (->> (file-seq (io/file ws "logs"))
                  (filter #(str/ends-with? (str %) ".edn"))
                  (mapcat #(file-tss %)))
        rl   (file-tss (io/file ws "run.log"))
        tss  (remove nil? (concat lgs rl))]
    (when (seq tss) [(apply min tss) (apply max tss)])))

(defn overlap? [[a1 a2] [b1 b2]]
  (<= (max a1 b1) (min a2 b2)))

(defn fmt [ms]
  (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")
           (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/of "UTC"))))

;; ---- evidence assembly -------------------------------------------------
(def by-topic (group-by topic-of (map str (filter fs/directory? (fs/list-dir goals-root)))))
(def intervals (into {}
                     (for [t [topic-a topic-b]]
                       [t (into {} (for [ws (get by-topic t)]
                                      [ws (interval-for ws)]))])))

;; ---- leakage scan ------------------------------------------------------
(defn ws-files [ws]
  (->> (file-seq (io/file ws))
       (filter #(.isFile %))
       (filter #(not (str/includes? (str %) "/.git/")))))

(defn leakage [t]
  (let [others (markers (if (= t topic-a) topic-b topic-a))
        sessions (io/file home "state/sessions")
        sess-file (io/file sessions (str "telegram--1003995594829-topic-" t ".edn"))
        sess-hit (and (.exists sess-file)
                      (seq (filter (fn [m] (str/includes? (slurp sess-file) m)) others)))
        ws-hits (for [ws (get by-topic t)
                      f (ws-files ws)
                      :let [s (try (slurp (str f)) (catch Exception _ nil))]
                      :when (and s (seq (filter #(str/includes? s %) others)))]
                  [ws (str (.getName f))])]
    (when (or sess-hit (seq ws-hits))
      (cond-> {:topic t}
        sess-hit (assoc :session-hit sess-hit)
        (seq ws-hits) (assoc :workspace-hits ws-hits)))))

;; ---- registry coexistence (strongest overlap receipt) ------------------
(def coexist? (when (and timeline-file (.exists (io/file timeline-file)))
                (some #(or (str/includes? % "TWO RUNS COEXIST")
                           (re-find #"registry: 2 entries" %))
                      (str/split-lines (slurp timeline-file)))))

;; ---- report ------------------------------------------------------------
(println "=== worker intervals (UTC, from ledgers + run.log) ===")
(doseq [t [topic-a topic-b]]
  (println (str "topic-" t ":"))
  (doseq [[ws iv] (sort (get intervals t))]
    (if iv
      (println (format "  %s  [%s → %s]" ws (fmt (first iv)) (fmt (second iv))))
      (println (format "  %s  (no timestamped run evidence)" ws)))))

(def pairs (for [wa (vals (get intervals topic-a))
                 wb (vals (get intervals topic-b))
                 :when (and wa wb)]
             [wa wb]))

(def intervals-overlap (some #(apply overlap? %) pairs))
(println (str "\ninterval-overlap: " (if intervals-overlap "YES" "no")))
(doseq [[wa wb] pairs :when (overlap? wa wb)]
  (println (format "  intersect: [%s → %s]"
                   (fmt (max (first wa) (first wb)))
                   (fmt (min (second wa) (second wb))))))
(println (str "registry-coexistence receipt: " (if coexist? "YES" (if timeline-file "no" "not provided"))))

(println "\n=== cross-topic marker leakage ===")
(def leaks (keep leakage [topic-a topic-b]))
(if (seq leaks)
  (doseq [l leaks] (println " " (pr-str l)))
  (println "  clean — no topic's session/workspaces mention the other's markers"))

(def overlap-total? (or intervals-overlap coexist?))
(println (str "\nPARALLEL-VERDICT: OVERLAP=" (if overlap-total? "yes" "no")
              " LEAKAGE=" (if (seq leaks) "dirty" "clean")))
(System/exit (if (and overlap-total? (empty? leaks)) 0 1))
