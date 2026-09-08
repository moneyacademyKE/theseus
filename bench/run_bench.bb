#!/usr/bin/env bb
;; Axiom A/B benchmark — does the supervision loop improve completion?
;; Arms:  single | blind (retry<=10, no observation) | goal (axiom, max-iters 10)
;; Scenes: transient (succeeds at invocation K, K in 1..5) | corrupt (invocation 1 poisons; retry can't fix)
;; Usage: bb bench/run_bench.bb [trials-per-cell]   (default 15)
;; Results: printed table + raw EDN at $AXBENCH_OUT.
(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def root (fs/abs (fs/file ".")))
(def trials (or (some-> *command-line-args* first parse-long) 15))
(def ts (System/currentTimeMillis))
(def sandbox (str (fs/path (fs/temp-dir) (str "axbench-" ts))))
(def actor-src (slurp (fs/file root "bench" "actor.sh")))

(defn run-cmd [dir cmd env]
  (try (p/shell (cond-> {:dir dir :out :string :err :string :continue true}
                  env (assoc :extra-env env))
                cmd)
       (catch Exception e {:exit 127 :err (str e)})))

(defn make-workdir [tag]
  (let [d (str (fs/path sandbox tag "work"))]
    (fs/create-dirs (fs/path sandbox tag))
    (spit (str (fs/path d "actor.sh")) actor-src)
    (spit (str (fs/path d "artifact.txt")) "PENDING")
    (run-cmd d "chmod +x actor.sh" nil)
    (run-cmd d "git init -q && git add -A && git -c user.email=b@local -c user.name=bench commit -qm init" nil)
    d))

(defn cfg-text [scene workdir lock log]
  (str "{:name \"bench-" scene "\"\n"
       " :workdir \"" workdir "\"\n"
       " :lock \"" lock "\"\n"
       " :log-dir \"" log "\"\n"
       " :observers {:artifact {:sh \"cat artifact.txt\" :parse :string}\n"
       "             :fixed    {:sh \"grep -q '^OK$' artifact.txt && echo 1 || echo 0\" :parse :int}}\n"
       " :goal {:op := :ref :artifact :value \"OK\"}\n"
       " :progress :fixed\n"
       " :act {:sh \"./actor.sh\"}\n"
       " :checkpoint {:tag-prefix \"axbench\"}\n"
       " :stall-after " (if (= scene "corrupt") 2 6) "\n"
       " :max-iters 10 :max-rollbacks 3}\n"))

(defn run-actor [dir state log mode k]
  (run-cmd dir "./actor.sh"
           {"AXIOM_BENCH_MODE" mode "AXIOM_BENCH_STATE" state
            "AXIOM_BENCH_LOG" log "AXIOM_BENCH_K" (str k)}))

(defn invocations [log]
  (if (fs/exists? log) (count (str/split-lines (slurp log))) 0))

(defn arm-single [dir state log mode k]
  (run-actor dir state log mode k))

(defn arm-blind [dir state log mode k]
  (loop [i 0]
    (when (< i 10)
      (let [{:keys [exit]} (run-actor dir state log mode k)]
        (when-not (zero? exit) (recur (inc i)))))))

(defn arm-goal [tdir scene work]
  (let [cfg (str (fs/path tdir "cfg.edn"))]
    (spit cfg (cfg-text scene work (str (fs/path tdir "run.lock")) (str (fs/path tdir "logs"))))
    (run-cmd (str root) (str "bb goal " cfg) nil)))

(defn one-trial [scene arm t]
  (let [tdir  (str (fs/path sandbox (str scene "-" arm "-" t)))
        work  (make-workdir (str scene "-" arm "-" t))
        state (str (fs/path tdir "state"))
        log   (str (fs/path tdir "invocations.log"))
        k     (inc (mod t 5))
        mode  (if (= scene "corrupt") "corrupt" "transient")
        t0    (System/nanoTime)
        _     (case arm
                "single" (arm-single work state log mode k)
                "blind"  (arm-blind work state log mode k)
                "goal"   (arm-goal tdir scene work))
        ms    (quot (- (System/nanoTime) t0) 1000000)
        art   (slurp (str (fs/path work "artifact.txt")))]
    {:scene scene :arm arm :trial t :k k :ms ms
     :invocations (invocations log) :ok (= (str/trim art) "OK")}))

(def cells (for [scene ["transient" "corrupt"] arm ["single" "blind" "goal"]] [scene arm]))
(def results (atom []))
(doseq [[scene arm] cells, t (range trials)]
  (swap! results conj (one-trial scene arm t)))

(defn agg [rows]
  (let [n (count rows) wins (filter :ok rows)]
    {:n n :successes (count wins)
     :rate (if (pos? n) (double (/ (count wins) n)) 0.0)
     :mean-inv (if (pos? n) (double (/ (reduce + (map :invocations rows)) n)) 0.0)
     :mean-ms  (if (pos? n) (double (/ (reduce + (map :ms rows)) n)) 0.0)}))

(def by-cell (group-by (juxt :scene :arm) @results))
(println "cell                  n  succ  rate   mean-inv  mean-ms")
(doseq [k (sort (keys by-cell))]
  (let [{:keys [n successes rate mean-inv mean-ms]} (agg (get by-cell k))]
    (println (format "%-18s  %2d  %4d  %3.0f%%  %8.1f  %7.0f"
                     (str k) n successes (* 100 rate) mean-inv mean-ms))))
(println (str "sandbox: " sandbox))
(when-let [out (System/getenv "AXBENCH_OUT")] (spit out (pr-str @results)))
