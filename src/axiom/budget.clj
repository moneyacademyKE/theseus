(ns axiom.budget
  "Pure cost/token budget accounting.

  Harness transports may return advisory usage data alongside exit/out/err:

    {:usage {:cost-usd 0.02 :tokens 1200}}

  Axiom treats that as accounting metadata only. Truth still comes from
  observers; budget exhaustion is just another bounded halt condition. Missing
  or malformed usage is zero, so older shell/harness acts remain compatible."
  (:require [clojure.string :as str]))

(defn- numeric
  [v]
  (cond
    (number? v) v
    (string? v) (try
                  (let [s (str/trim v)]
                    (when-not (str/blank? s)
                      (Double/parseDouble s)))
                  (catch Exception _ nil))
    :else nil))

(defn usage
  "Extract normalized usage from an act result.

  Accepted shapes:
  - {:usage {:cost-usd 0.01 :tokens 100}}
  - {:cost-usd 0.01 :tokens 100}

  Returns only counters Axiom understands, with absent/non-numeric values as 0."
  [result]
  (let [u (merge (select-keys result [:cost-usd :tokens])
                 (select-keys (:usage result) [:cost-usd :tokens]))]
    {:cost-usd (double (or (numeric (:cost-usd u)) 0.0))
     :tokens (long (or (numeric (:tokens u)) 0))}))

(defn totals
  [state]
  {:cost-usd (double (or (:cost-usd state) 0.0))
   :tokens (long (or (:tokens state) 0))})

(defn add-usage
  "Return state with result usage accumulated into :cost-usd and :tokens."
  [state result]
  (let [u (usage result)]
    (-> state
        (update :cost-usd (fnil + 0.0) (:cost-usd u))
        (update :tokens (fnil + 0) (:tokens u)))))

(defn exhausted
  "Return a budget-exhaustion descriptor when configured limits are spent."
  [cfg state]
  (let [{:keys [cost-usd tokens]} (totals state)]
    (cond
      (and (:max-cost-usd cfg) (>= cost-usd (:max-cost-usd cfg)))
      {:budget :cost-usd
       :cost-usd cost-usd
       :max-cost-usd (:max-cost-usd cfg)}

      (and (:max-tokens cfg) (>= tokens (:max-tokens cfg)))
      {:budget :tokens
       :tokens tokens
       :max-tokens (:max-tokens cfg)})))