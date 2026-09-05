(ns axiom.taxonomy
  "Failure classification helpers for dogfood hardening.

  Axiom records concrete halt reasons, but operators need a stable taxonomy
  across phases. This namespace keeps that mapping pure and data-first."
  (:require [clojure.string :as str]))

(def failure-classes
  "Stable operator-facing halt classes. Values are data, not prose scraped from
  log strings, so status/reporting can depend on them without parsing text."
  {:integrity       {:class :dirty-tree-unsafe
                     :severity :critical
                     :retryable? false}
   :stall           {:class :no-progress
                     :severity :warning
                     :retryable? true}
   :no-convergence  {:class :repeated-identical-actions
                     :severity :warning
                     :retryable? false}
   :max-iters       {:class :budget-exhausted
                     :severity :warning
                     :retryable? true}
   :budget-exhausted {:class :budget-exhausted
                      :severity :warning
                      :retryable? false}
   :operator-paused {:class :operator-paused
                     :severity :info
                     :retryable? true}
   :operator-stop   {:class :operator-stopped
                     :severity :info
                     :retryable? false}
   :config-invalid  {:class :config-invalid-after-hot-reload
                     :severity :critical
                     :retryable? false}
   :model           {:class :model-or-tool-failure
                     :severity :warning
                     :retryable? true}
   :rollback-limit  {:class :rollback-limit-hit
                     :severity :critical
                     :retryable? false}})

(defn classify-halt
  "Return a stable failure descriptor for a halt action or halt bundle.
  Unknown reasons are preserved but classified as :unknown so new reasons are
  fail-visible instead of being silently misreported."
  [halt]
  (let [reason (or (:reason halt) (get-in halt [:halt :reason]) :unknown)
        base   (get failure-classes reason
                    {:class :unknown :severity :warning :retryable? false})]
    (assoc base :reason reason)))

(defn artifact-summary
  "Pure summary of the artifacts a run leaves behind. Used by dogfood tests and
  reports to ensure every run can answer: what happened, why, and where."
  [cfg]
  (let [log-dir (:log-dir cfg)]
    (cond-> {:name (:name cfg)
             :log-dir log-dir
             :config-path (:config-path cfg)
             :hot-reload? (boolean (:hot-reload cfg))}
      (seq (str log-dir)) (assoc :events-log (str/replace (str log-dir "/events.log") #"//+" "/")
                                 :halt-bundle (str/replace (str log-dir "/halt-bundle.edn") #"//+" "/")))))
