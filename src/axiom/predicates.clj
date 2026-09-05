(ns axiom.predicates
  "The interpreted predicate DSL and progress signature calculation.
  Provides evaluation, structural validation, and signature hashing.")

(defn valid?
  "Static structural check: is `pred` a well-formed predicate? Does NOT
  evaluate it (no world needed). Used by `validate-config` to reject
  malformed goals/axioms at load time."
  [pred]
  (cond
    (keyword? pred) true
    (map? pred)
    (let [op (:op pred)]
      (case op
        (:>= :<= := :not= :> :<) (and (:ref pred) (contains? pred :value))
        :exists  (boolean (:ref pred))
        :not     (valid? (:expr pred))
        :and     (and (coll? (:exprs pred)) (seq (:exprs pred))
                      (every? valid? (:exprs pred)))
        :or      (and (coll? (:exprs pred)) (seq (:exprs pred))
                      (every? valid? (:exprs pred)))
        false))
    :else false))

(defn- num-cmp
  "Fail-safe numeric comparison: a missing/non-numeric ref is treated as
  'not satisfied' (false) rather than throwing. Observers yield nil on
  failure, so a predicate must never crash the loop on a broken observer."
  [f cur value]
  (and (number? cur) (f cur value)))

(defn eval-pred
  "Pure interpreter over a world map. Returns boolean.
  Grammar: bare keyword, comparisons (:>= :<= := :not= :> :<), :exists, :not, :and, :or."
  [world pred]
  (cond
    (keyword? pred)
    (boolean (get world pred))

    (map? pred)
    (let [op     (:op pred)
          ref    (:ref pred)
          cur    (get world ref)]
      (case op
        :>=     (num-cmp >=  cur (:value pred))
        :<=     (num-cmp <=  cur (:value pred))
        :=      (=   cur (:value pred))
        :not=   (not= cur (:value pred))
        :>      (num-cmp >   cur (:value pred))
        :<      (num-cmp <   cur (:value pred))
        :exists (some? cur)
        :not    (not  (eval-pred world (:expr pred)))
        :and    (every? #(eval-pred world %) (:exprs pred))
        :or     (some   #(eval-pred world %) (:exprs pred))
        (throw (ex-info (str "Unknown predicate op: " op)
                        {:op op :pred pred}))))
    :else
    (throw (ex-info (str "Bad predicate: " (pr-str pred)) {:pred pred}))))

(defn integrity-violations
  "Returns a seq of violated integrity checks. Empty = all axioms hold."
  [world integrity-spec]
  (keep (fn [check]
          (when-not (eval-pred world check)
            {:check check}))
        (or integrity-spec [])))

(defn goal-met?
  "Does the world satisfy the goal predicate?"
  [world goal-spec]
  (eval-pred world goal-spec))

(defn progress-sig
  "Compute the monotonic progress signature from a world map.
  Unchanged signature across iterations = no forward progress."
  [world progress-spec]
  (cond
    (keyword? progress-spec) (get world progress-spec)
    (coll?    progress-spec) (hash (mapv #(get world %) progress-spec))
    (nil?     progress-spec) (hash world)
    :else                    (hash world)))
