(ns bb-agent.error-classifier
  "Classify raw provider/network error strings into a failure kind.

  Ported from hermes-beam's error_classifier.gleam: pattern tables and
  precedence preserved, Gleam display sugar dropped. Retry semantics,
  per lm-eval-harness per-error-type lore:

    :auth        never retry — the key will not fix itself
    :rate-limit  back off, then advance
    :infra       retry, then advance
    :logic       do not retry — our bug (bad request or model id)
    :unknown     caller's judgement call"
  (:require [clojure.string :as str]))

(def ^:private patterns
  "First category whose pattern matches wins: auth over rate over
  infra over logic, matching hermes-beam's precedence."
  [[:auth ["401" "403" "invalid api key" "unauthorized"
           "authentication" "forbidden"]]
   [:rate-limit ["429" "402" "rate limit" "quota" "too many requests"]]
   [:infra ["502" "503" "504" "timeout" "connection refused" "econnrefused"
            "stream failed" "provider returned error" "model is unavailable"]]
   [:logic ["400" "invalid model" "not a valid model" "not found"]]])

(defn classify
  "Classify a raw error string into {:kind k :reason raw}."
  [raw]
  (let [lower (str/lower-case (str raw))]
    (or (some (fn [[kind pats]]
                (when (some #(str/includes? lower %) pats)
                  {:kind kind :reason raw}))
              patterns)
        {:kind :unknown :reason raw})))

(defn retryable?
  "True for kinds the standard policy retries (after backoff for
  rate limits). Auth and logic errors are never retried."
  [{:keys [kind]}]
  (contains? #{:rate-limit :infra} kind))
