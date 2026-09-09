(ns bb-agent.tool.goal
  "launch_goal — the LLM-reachable path into the supervised goal runner.
   handle-tool-request injects the turn's :telegram/send-context as
   :goal/send-context, so an ordinary prompt ('build X') and the /goal
   command land in the same bridge — the SAME bridge: dispatch-spec! is
   shared verbatim (V1a). Authoring is detached (it can take 10-45 honest
   minutes and once parked the whole poll loop from inside a turn), so the
   tool returns the immediate dispatch ack; the outcome reaches the topic
   via the progress message and the outcome queue."
  (:require [bb-agent.tool.common :as common]
            [clojure.string :as str]))

(defn- launch-goal [args]
  (let [spec (str/trim (str (or (get args "spec") (get args :spec) "")))
        ctx (get args :goal/send-context)]
    (if (str/blank? spec)
      (common/error-result "launch_goal" "spec is required" {:executed? false})
      (try
        ;; Late-bound on purpose: goal-bridge composes core (the authoring
        ;; LLM turn), so requiring it here — below core in the load graph —
        ;; would be a cyclic dependency. Resolving at call time keeps the
        ;; layering honest: tools stay below features, always.
        (let [dispatch (requiring-resolve 'bb-agent.goal-bridge/dispatch-spec!)
              outcome (dispatch spec (:chat-id ctx) (:thread-id ctx))]
          (common/ok-result "launch_goal" {:outcome outcome}))
        (catch Exception e
          (common/error-result "launch_goal"
                               (or (ex-message e) (.getName (class e)))
                               {:exception/type (str (class e))}))))))

(def handlers
  {"launch_goal" launch-goal})
