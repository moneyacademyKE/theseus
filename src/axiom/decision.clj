(ns axiom.decision
  "Pure decision-making logic for the Axiom run loop.
  Determines the next action based on world state, configurations, and loop budgets."
  (:require [axiom.predicates :as predicates]
            [axiom.budget :as budget]))

(defn- escalation-action
  "Return the next deterministic Phase 2 ladder action, or final halt.
  The ladder is data so configs can trim or reorder it later."
  [cfg world state]
  (let [ladder     (:escalations cfg [:rollback :reseed :reframe :escalate-model])
        index      (:escalation-index state 0)
        rung       (nth ladder index nil)
        iterations (:thrash state)]
    (if rung
      {:type :escalate
       :rung rung
       :escalation-index index
       :next-escalation-index (inc index)
       :iterations iterations
       :world world}
      {:type :halt
       :reason :no-convergence
       :iterations iterations
       :escalation-index index
       :world world})))

(defn decide
  "Pure: given config, world, and run-state, return the next action.

  state: {:stall <n> :thrash <n> :attempt <n> :rollbacks <n> :perturb-index <n>}

  Returns:
    {:type :done}                                goal met
    {:type :halt :reason :integrity ...}         axiom violated (halt NOW)
    {:type :rollback ...}                        stall exhausted, rollbacks remain
    {:type :escalate :rung :reseed ...}          no-convergence ladder rung
    {:type :halt :reason :stall ...}             stall exhausted, no rollbacks left
    {:type :halt :reason :no-convergence ...}    ladder exhausted
    {:type :act}                                 perform the act

  Order matters: integrity is checked BEFORE goal -- a broken system halts
  even if the goal is coincidentally met (mission axiom #1: integrity is a
  halt condition, not a retry condition). Rollback-as-recovery (Phase 1):
  when the stall budget is exhausted but rollbacks remain, we reset to the
  last-known-good checkpoint and retry instead of dying."
  [cfg world state]
  (let [violations    (predicates/integrity-violations world (:integrity cfg))
        stall-after   (:stall-after cfg 3)
        max-rollbacks (:max-rollbacks cfg 2)
        rollbacks     (:rollbacks state 0)
        thrash-after  (:thrash-after cfg Long/MAX_VALUE)]
    (cond
      (seq violations)
      {:type :halt :reason :integrity :violations violations :world world}

      (predicates/goal-met? world (:goal cfg))
      {:type :done :world world}

      (budget/exhausted cfg state)
      (merge {:type :halt :reason :budget-exhausted :world world}
             (budget/exhausted cfg state))

      (and (:max-attempts cfg) (>= (or (:attempt state) 0) (:max-attempts cfg)))
      {:type :halt :reason :budget-exhausted :budget :attempts :attempts (:attempt state 0) :world world}

      (let [started (:started-at-ms state) now (:now-ms state) max-ms (:max-wall-ms cfg)]
        (and max-ms started now (>= (- now started) max-ms)))
      {:type :halt :reason :budget-exhausted :budget :wall-clock :elapsed-ms (- (:now-ms state) (:started-at-ms state)) :world world}

      (>= (or (:stall state) 0) stall-after)
      (if (< rollbacks max-rollbacks)
        {:type :rollback :rollbacks rollbacks :max-rollbacks max-rollbacks
         :world world}
        {:type :halt :reason :stall :iterations (:stall state)
         :rollbacks rollbacks :world world})

      (>= (or (:thrash state) 0) thrash-after)
      (escalation-action cfg world state)

      :else
      {:type :act})))
