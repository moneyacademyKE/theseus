(ns bb-agent.policy
  "Executable policy: <home>/brain/rules.clj predicates evaluated by sci —
  approval gates as code instead of prose.

  File shape:
    {:rules [{:name \"allow-doc-writes\"
              :pred     (fn [tool args] ...)  ; tool is a string, args the tool map
              :decision :allow}]}

  Every matching predicate is measured. A matching :deny wins over every
  matching :allow, so a grant cannot shadow a safety floor when rules are
  reordered. The returned decision also names the rule and category, which
  makes a refusal recoverable instead of a mystery.

  An enabled policy that is missing, invalid, throwing, or timed out always
  denies: without the policy even a normally safe read could reveal a secret.

  Sandbox (pinned by e2e.policy-test/sandbox-contract-pin): sci default
  context — no io, no slurp/spit, no Java interop, no threads. Rules classify;
  they cannot touch the world. This sci build ignores :timeout-ms (verified
  empirically), so file eval and every predicate run inside a future bounded
  by a deref timeout. A timed-out predicate's thread cannot be killed on the
  JVM — bounded waste (one spinning thread per runaway eval), never a hang."
  (:require [babashka.fs :as fs]
            [bb-agent.brain :as brain]
            [sci.core :as sci]))

(def eval-timeout-ms 2000)

(defn rules-file []
  (fs/path (brain/brain-dir) "rules.clj"))

(defn- valid-rules? [rules]
  (and (seq rules)
       (every? (fn [{:keys [pred decision]}]
                 (and (fn? pred) (contains? #{:allow :deny} decision)))
               rules)))

(defn- eval-rules [code]
  (try
    (let [result (deref (future (sci/eval-string code {}))
                        eval-timeout-ms
                        ::timed-out)]
      (cond
        (= ::timed-out result) {:status :error :reason :timeout}
        (and (map? result) (valid-rules? (:rules result)))
        {:status :ok :rules (:rules result)}
        :else {:status :error :reason :invalid}))
    (catch Exception _
      {:status :error :reason :exception})))

(defn- pred-verdict [pred tool args]
  (try
    (let [result (deref (future (pred tool args))
                        eval-timeout-ms
                        ::timed-out)]
      (if (= ::timed-out result)
        {:status :error :reason :timeout}
        {:status :ok :hit (boolean result)}))
    (catch Exception _
      {:status :error :reason :exception})))

(defn- policy-failure [reason]
  {:decision :deny
   :rule "policy-integrity"
   :category :policy-integrity
   :reason reason})

(defn- rule-category [{:keys [category decision]}]
  (or category
      (if (= :deny decision) :safety-floor :grant)))

(defn- decision-for [rule]
  {:decision (:decision rule)
   :rule (if (nil? (:name rule)) "<unnamed>" (str (:name rule)))
   :category (rule-category rule)
   :reason :matched})

(defn- evaluate-rules [rules tool args]
  (loop [remaining rules
         matches []]
    (if (empty? remaining)
      (if-let [winner (or (some #(when (= :deny (:decision %)) %) matches)
                          (some #(when (= :allow (:decision %)) %) matches))]
        (decision-for winner)
        {:decision nil :reason :no-match})
      (let [rule (first remaining)
            result (pred-verdict (:pred rule) tool args)]
        (if (= :error (:status result))
          {:status :error :reason (:reason result)}
          (recur (rest remaining)
                 (if (:hit result) (conj matches rule) matches)))))))

(defn evaluate
  "Returns a data decision: :decision is :allow, :deny, or nil; :rule and
  :category explain a match. An enabled policy-integrity failure denies."
  [{:tool/keys [name args]} cfg]
  (if-not (get-in cfg [:policy :enabled])
    {:decision nil :reason :disabled}
    (try
      (let [f (rules-file)]
        (if-not (fs/exists? f)
          (policy-failure :missing)
          (let [{:keys [status rules reason]} (eval-rules (slurp (str f)))]
            (if (= :ok status)
              (let [result (evaluate-rules rules name args)]
                (if (= :error (:status result))
                  (policy-failure (:reason result))
                  result))
              (policy-failure reason)))))
      (catch Exception _
        (policy-failure :exception)))))

(defn verdict
  "Compatibility projection of evaluate: returns :allow, :deny, or nil."
  [request cfg]
  (:decision (evaluate request cfg)))
