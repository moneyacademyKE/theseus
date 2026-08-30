(ns bb-agent.policy
  "Executable policy: <home>/brain/rules.clj predicates evaluated by sci —
  approval gates as code instead of prose.

  File shape:
    {:rules [{:name \"allow-doc-writes\"
              :pred     (fn [tool args] ...)  ; tool is a string, args the tool map
              :decision :allow}]}

  First truthy pred wins. No match, disabled policy, missing file, unparseable
  file, throwing pred, or pred running past `eval-timeout-ms` -> verdict nil ->
  the ordinary approval classifier decides. Fail-to-baseline: a broken rules
  file can make the agent neither more permissive nor more restrictive than
  the configured baseline.

  Sandbox (pinned by e2e.policy-test/sandbox-contract-pin): sci default
  context — no io, no slurp/spit, no Java interop, no threads. Rules classify;
  they cannot touch the world. This sci build ignores :timeout-ms (verified
  empirically), so both file eval and every pred call run inside a future
  bounded by a deref timeout. A timed-out pred's thread cannot be killed on
  the JVM — bounded waste (one spinning thread per runaway eval), never a
  hang."
  (:require [babashka.fs :as fs]
            [bb-agent.brain :as brain]
            [sci.core :as sci]))

(def eval-timeout-ms 500)

(defn rules-file []
  (fs/path (brain/brain-dir) "rules.clj"))

(defn- valid-rules? [rules]
  (and (seq rules)
       (every? (fn [{:keys [pred decision]}]
                 (and (fn? pred) (contains? #{:allow :deny} decision)))
               rules)))

(defn- eval-rules [code]
  (let [result (deref (future (sci/eval-string code {})) eval-timeout-ms ::timed-out)]
    (when (and (map? result) (valid-rules? (:rules result)))
      result)))

(defn- pred-verdict [pred tool args]
  (deref (future (pred tool args)) eval-timeout-ms ::timed-out))

(defn verdict
  "Returns :allow, :deny, or nil. nil means no policy verdict — the caller
  falls back to the approval classifier unchanged."
  [{:tool/keys [name args]} cfg]
  (when (get-in cfg [:policy :enabled])
    (try
      (let [f (rules-file)]
        (when (fs/exists? f)
          (when-let [{:keys [rules]} (eval-rules (slurp (str f)))]
            (loop [rules rules]
              (when-let [{:keys [pred decision]} (first rules)]
                (let [hit (pred-verdict pred name args)]
                  (cond
                    (= ::timed-out hit) nil
                    hit decision
                    :else (recur (rest rules)))))))))
      (catch Exception _ nil))))
