#!/usr/bin/env bb
;; Dogfood evidence collector: given a goal workspace, extract the receipts
;; that prove (1) goal methodology was followed and (2) skills were used.
;; Usage: bb scripts/dogfood_evidence.bb <workspace-name>

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def goals-root (str (System/getenv "HOME") "/theseus/goals"))
(def ws-name (or (first *command-line-args*)
                 (do (println "usage: dogfood_evidence.bb <workspace-name>") (System/exit 1)))
  )
(def ws (str goals-root "/" ws-name))

(defn sec [title] (println (str "\n== " title " ==")))

;; 1. Authored plan: stages + validators (methodology artifact)
(sec "AUTHORED PLAN")
(let [cfg-path (str ws "/project.edn")]
  (if (fs/exists? cfg-path)
    (let [cfg (edn/read-string (slurp cfg-path))]
      (println {:name (:name cfg)
                :goal-predicate (:goal cfg)
                :integrity-count (count (:integrity cfg))
                :observers (vec (keys (:observers cfg)))
                :checkpoint (:checkpoint cfg)
                :max-rollbacks (:max-rollbacks cfg)
                :stall-after (:stall-after cfg)
                :act (:act cfg)}))
    (println "NO project.edn — authoring did not complete")))

;; 2. Ledger intervals (persisted evidence standard)
(sec "LEDGER INTERVALS")
(let [logs (str ws "/logs")]
  (if (fs/exists? logs)
    (let [iters (->> (fs/list-dir logs)
                     (filter #(str/ends-with? (str %) ".edn"))
                     (sort-by str))
          stamps (for [f iters
                       :let [[_ e] (try [(slurp (str f))
                                         (edn/read-string (slurp (str f)))]
                                        (catch Exception _ nil))]
                       :when e
                       :let [ts (:ts e)]]
                   {:iter (:iteration e) :ts ts :event (:event e)
                    :duration-ms (:duration-ms e) :tokens (:tokens e)})]
      (if (seq stamps)
        (do (doseq [s (take 40 stamps)] (println s))
            (let [tss (filter some? (map :ts stamps))
                  parse (fn [t] (when t (try (java.time.Instant/parse t) (catch Exception _ nil))))
                  insts (keep parse tss)]
              (when (>= (count insts) 2)
                (println {:first-ts (str (first (sort insts)))
                          :last-ts (str (last (sort insts)))
                          :worker-window-mins (/ (- (.toEpochMilli (last (sort insts)))
                                                    (.toEpochMilli (first (sort insts)))) 60000.0)}))))
        (println "no .edn ledger events parsed"))
      (println {:iter-files (count iters)}))
    (println "no logs/ dir — runner never started")))

;; 3. Skill usage: read_file calls into skills/ across author session + ledgers
(sec "SKILL USAGE EVIDENCE")
(let [sess (str (System/getenv "HOME") "/theseus/state/sessions/goal-author-" ws-name ".edn")
      hits (atom [])]
  (when (fs/exists? sess)
    (let [raw (slurp sess)]
      (doseq [skill ["verification-witness" "frontend-design" "adr" "review" "openspeq"]
              :let [bare (count (re-seq (re-pattern skill) raw))
                    paths (count (re-seq (re-pattern (str "skills/" skill "/")) raw))]]
        (when (pos? bare)
          (swap! hits conj {:skill skill
                            :path-refs paths          ; tool-level reads of the skill file
                            :bare-mentions (- bare paths)})))))
  (let [logs (str ws "/logs")]
    (when (fs/exists? logs)
      (doseq [f (filter #(str/ends-with? (str %) ".edn") (fs/list-dir logs))
              :let [raw (try (slurp (str f)) (catch Exception _ ""))]
              skill ["verification-witness" "frontend-design"]
              :when (str/includes? raw skill)]
        (swap! hits conj {:skill skill :source (fs/file-name f)}))))
  (if (seq @hits)
    (doseq [h (distinct @hits)] (println h))
    (println "no skill-name references found in author session or ledgers")))

;; 4. Verdict + workdir evidence
(sec "VERDICT & WORKDIR")
(let [runlog (str ws "/run.log")]
  (if (fs/exists? runlog)
    (let [lines (->> (slurp runlog) str/split-lines)]
      (doseq [l lines] (when (re-find #"FULFILLED|HALT|FAILED|VERDICT|verdict" l)
                         (println l))))
    (println "no run.log (runner did not start)"))
  (let [cfg (when (fs/exists? (str ws "/config.edn"))
              (edn/read-string (slurp (str ws "/config.edn"))))
        workdir (:workdir cfg)]
    (println {:workdir workdir})
    (when (and workdir (fs/exists? workdir))
      (println "workdir files:")
      (doseq [f (take 20 (map fs/file-name (fs/list-dir workdir)))]
        (println "  " (str f))))))

;; 5. Topic outcome (delivery evidence)
(sec "TOPIC SESSION (outcome delivery)")
(let [sess-file (str (System/getenv "HOME") "/theseus/state/sessions/telegram--1003995594829-topic-236.edn")]
  (if (fs/exists? sess-file)
    (let [raw (slurp sess-file)]
      (println {:session-bytes (count raw)
                :goal-outcome-mentions (count (re-seq #"goal-outcome" raw))
                :fulfilled-mentions (count (re-seq #"fulfilled|FULFILLED" raw))}))
    (println "no topic-236 session yet (no outcome delivered)")))
