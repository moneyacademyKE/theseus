(ns bb-agent.turn-diagnostics
  "Evidence-only turn diagnostic assessment (ported from LoopForge
   assistant_diagnostics.go + assistant_diagnostic_analysis.go).
   Produces bounded, secret-free failure/interruption assessments that record
   observed evidence and explicit unknowns — never inventing a provider root
   cause and never leaking raw prompts or tool arguments."
  (:require [clojure.string :as str]))

(defn assess-failure
  "Build an evidence-only diagnostic map {:outcome :summary :evidence :unknowns :next-steps}
   from a turn failure or termination event."
  [{:keys [session-id exception tool-count termination]}]
  (let [msg (when exception (or (ex-message exception) (.getName (class exception))))
        data (when exception (ex-data exception))
        tried (:fallback/tried data)]
    (cond
      (= :user-cancelled termination)
      {:version 1
       :session-id session-id
       :outcome :cancelled-by-user
       :summary "Turn cancelled by user stop signal."
       :evidence (cond-> ["Recorded termination: USER_CANCELLED"]
                   (pos? (long (or tool-count 0)))
                   (conj (str tool-count " tool call(s) completed before cancellation.")))
       :unknowns []
       :next-steps ["Resume or re-issue the request when ready."]}

      (= :user-steer termination)
      {:version 1
       :session-id session-id
       :outcome :redirected-by-user
       :summary "Turn redirected mid-flight by user steer input."
       :evidence ["Recorded termination: USER_STEER"]
       :unknowns []
       :next-steps ["Continue under the updated user direction."]}

      (seq tried)
      (let [ev (into [(str "All " (count tried) " provider(s) in fallback chain failed.")]
                     (map (fn [{:fallback/keys [provider kind reason]}]
                            (str "Provider " (some-> provider name)
                                 " (" (some-> kind name) "): " reason))
                          tried))]
        {:version 1
         :session-id session-id
         :outcome :provider-exhausted
         :summary (or msg "All providers in fallback chain failed")
         :evidence (cond-> ev
                     (pos? (long (or tool-count 0)))
                     (conj (str tool-count " tool call(s) completed earlier in this turn.")))
         :unknowns ["Upstream HTTP status alone does not distinguish transient provider capacity exhaustion from account quota/policy changes without inspecting the provider dashboard."]
         :next-steps ["Verify provider health/balance or switch active model via /model."]})

      :else
      {:version 1
       :session-id session-id
       :outcome :turn-error
       :summary (or msg "Unknown turn error")
       :evidence (cond-> []
                   msg (conj (str "Observed error: " msg))
                   (pos? (long (or tool-count 0)))
                   (conj (str tool-count " tool call(s) completed before failure.")))
       :unknowns ["Observed exception does not by itself prove host, network, or upstream root cause."]
       :next-steps ["Check logs for full stack context and retry."]})))

(defn format-compact
  "One-paragraph compact summary suitable for logs or Telegram diagnostics."
  [{:keys [outcome summary evidence unknowns]}]
  (str "[" (name (or outcome :unknown)) "] " summary
       (when (seq evidence) (str " | evidence: " (str/join "; " (take 3 evidence))))
       (when (seq unknowns) (str " | unknown: " (first unknowns)))))
