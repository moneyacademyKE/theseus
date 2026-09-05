(ns axiom.status
  "Phase 3 -- live status view.

  Gives a human a quick read of what Axiom is doing or did, straight from
  the structured logs the loop already writes. Two surfaces:

    `format-summary`  -- PURE: (cfg, bundle, iterations) -> string. No
                          disk, no I/O. Tested directly.
    `summarize!`      -- reads <log-dir>/halt-bundle.edn if present (the
                          run halted), else tail events.log + the last N
                          iteration records (the run is running / stale).
                          Prints the formatted summary and returns it.

  Per ROADMAP Phase 3: 'tail the structured log'. This is the read-only
  companion to the per-iteration EDN records + halt bundle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.log :as log]
            [axiom.taxonomy :as taxonomy]
            [axiom.control :as control]))

(defn- safe-slurp
  "Read a file's contents, nil if absent."
  [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

(defn- tail-lines
  "Return the last `n` non-blank lines of `text`, as a vector."
  [text n]
  (->> (str/split-lines text)
       (remove str/blank?)
       reverse
       (take n)
       reverse
       vec))

(defn operator-facts
  "Pure extraction of the fields an operator needs most. The function is
  intentionally tolerant of older iteration records: absent fields stay nil
  rather than making status fail during an incident."
  [cfg bundle iterations]
  (let [iters     (or iterations [])
        last-iter (when (seq iters) (last iters))
        halt      (when bundle (:halt bundle))
        class     (when bundle (taxonomy/classify-halt bundle))]
    (cond-> {:config (:name cfg "<unnamed>")
             :state (if bundle :halted :running)
             :iteration (or (:iteration last-iter) (when bundle (count iters)) 0)
             :last-event (:event last-iter)
             :prompt-index (:prompt-index last-iter)
             :model-index (:model-index last-iter)
             :attempt (:attempt last-iter)
             :rung (:rung last-iter)
             :stall (:stall last-iter)
             :thrash (:thrash last-iter)
             :config-path (:config-path cfg)
             :hot-reload? (boolean (:hot-reload cfg))
             :control-state (:state (control/state cfg))}
      halt (assoc :halt-reason (:reason halt)
                  :halt-iterations (:iterations halt))
      class (assoc :failure-class (:class class)
                   :severity (:severity class)
                   :retryable? (:retryable? class)))))

(defn format-summary
  "PURE: build a human-readable status string from `cfg`, an optional halt
  `bundle` map (nil when the run is not halted), and a vector of `iterations`
  (the last N iteration records, oldest first). No disk reads.

  The summary always names: the config name, the run state (halted reason
  or 'running'), the iteration count, and the last world snapshot when
  available. When `bundle` is non-nil the run halted; the reason and halt
  details come from the bundle."
  [{:keys [name] :or {name "<unnamed>"} :as cfg} bundle iterations]
  (let [halted?     (some? bundle)
        reason      (when halted? (:reason bundle))
        halt        (when halted? (:halt bundle))
        iters       (or iterations [])
        last-iter   (when (seq iters) (last iters))
        facts       (operator-facts cfg bundle iters)
        iter-count  (:iteration facts)
        world       (or (:world halt) (:world last-iter))
        state-label (if halted?
                      (str "HALTED: " (clojure.core/name (or reason :unknown)))
                      "RUNNING")
        recent      (when (seq iters)
                      (->> (rseq iters)
                           (take 5)
                           (map (fn [it]
                                  (str "  ["
                                       (or (:iteration it) "?")
                                       "] "
                                       (clojure.core/name (or (:event it) :act))
                                       (when (:rung it) (str " rung=" (clojure.core/name (:rung it))))
                                       (when (:progressed? it) " ↑")
                                       (when (:exit it) (str " exit=" (:exit it))))))
                           (str/join "\n")))
        lines (cond-> []
                 true            (conj (str "─── Axiom Status ───"))
                 true            (conj (str "Config: " name))
                 (:config-path facts)
                 (conj (str "Config path: " (:config-path facts)))
                 true            (conj (str "State:  " state-label))
                 (:failure-class facts)
                 (conj (str "Failure class: " (clojure.core/name (:failure-class facts))
                            " severity=" (clojure.core/name (:severity facts))
                            " retryable=" (:retryable? facts)))
                 true            (conj (str "Iterations: " iter-count))
                 (:control-state facts) (conj (str "Control: " (clojure.core/name (:control-state facts))))
                 (:hot-reload? facts)
                 (conj "Hot reload: on")
                 (and halted? (:iterations halt))
                 (conj (str "Halt iterations: " (:iterations halt)))
                 (:last-event facts)
                 (conj (str "Last event: " (clojure.core/name (:last-event facts))))
                 (:rung facts)
                 (conj (str "Current rung: " (clojure.core/name (:rung facts))))
                 (some? (:attempt facts))
                 (conj (str "Attempt: " (:attempt facts)))
                 (or (some? (:prompt-index facts)) (some? (:model-index facts)))
                 (conj (str "Prompt/model index: " (or (:prompt-index facts) 0) "/" (or (:model-index facts) 0)))
                 (or (some? (:stall facts)) (some? (:thrash facts)))
                 (conj (str "Stall/thrash: " (or (:stall facts) 0) "/" (or (:thrash facts) 0)))
                 (or (some? (:cost-usd last-iter)) (some? (:tokens last-iter)))
                 (conj (str "Cost/tokens: " (or (:cost-usd last-iter) 0.0) "/" (or (:tokens last-iter) 0)))
                 world           (conj (str "Last world: " (pr-str world)))
                 (seq iters)     (conj "Recent events:")
                 recent          (conj recent))]
    (str/join "\n" lines)))

(defn summarize!
  "Read Axiom's structured logs for `cfg` and print + return a formatted
  status string. opts: {:last N} (default 20).

  Precedence:
    1. <log-dir>/halt-bundle.edn exists -> the run halted; format from the
       bundle (which already carries the last N iteration records).
    2. No bundle -> read events.log tail + log/read-iterations last N and
       format a 'running / no-bundle' summary.

  Returns nil when there is nothing to report (no log-dir, no logs)."
  ([cfg] (summarize! cfg {}))
  ([cfg opts]
   (let [log-dir (:log-dir cfg)
         n       (:last opts 20)]
     (if (str/blank? log-dir)
       (println "No :log-dir in config; nothing to report.")
       (let [bundle-path (io/file log-dir "halt-bundle.edn")
             bundle      (when (.exists bundle-path)
                           (edn/read-string (slurp bundle-path)))
             iterations (cond
                           bundle      (:iterations bundle)
                           :else       (or (log/read-iterations log-dir n) []))
             summary     (format-summary cfg bundle iterations)]
         (println summary)
         summary)))))