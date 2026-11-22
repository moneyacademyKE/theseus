(ns bb-agent.tool-hints
  "Tool-boundary next-step recovery hints and shell timeout hints.
   Inspects only the first 2,000 chars of an error tool result, classifies
   deterministic failures (:rate-limited, :auth-expired, :permission-denied,
   :timeout, :not-found), and attaches a crisp 1-line :next-step directive so
   the model stops burning turns retrying unrecoverable calls."
  (:require [clojure.string :as str]))

(def ^:private max-inspect-chars 2000)

(defn- bounded [s]
  (let [t (str (or s ""))]
    (if (> (count t) max-inspect-chars)
      (subs t 0 max-inspect-chars)
      t)))

(defn classify-tool-failure
  "Classify a tool error string into one of 5 deterministic failure kinds,
   or nil when generic."
  [text]
  (let [s (str/lower-case (bounded text))]
    (cond
      (str/blank? s) nil
      (re-find #"\b429\b|rate[- ]?limit|too many requests|quota exceeded" s)
      :rate-limited

      (re-find #"\b401\b|unauthorized|token expired|invalid[_ -]?token|not authenticated|login required" s)
      :auth-expired

      (re-find #"\b403\b|permission denied|eacces|operation not permitted|protected secret|refused:" s)
      :permission-denied

      (re-find #"timed?\s*out|timeout|deadline exceeded|etimedout" s)
      :timeout

      (re-find #"\b404\b|not found|enoent|no such file|does not exist|unknown (?:tool|job|step)" s)
      :not-found

      :else nil)))

(defn next-step-hint [kind]
  (case kind
    :rate-limited
    "Next step: this endpoint is rate-limited; pause or reduce frequency instead of immediately repeating the same call."

    :auth-expired
    "Next step: the sign-in or token behind this has expired; retrying will not help — report the auth failure to the user."

    :permission-denied
    "Next step: access was explicitly refused; do not retry the same path or command — ask the user or choose an allowed target."

    :timeout
    "Next step: the call timed out; narrow the scope, add limits, or run long work detached instead of repeating identically."

    :not-found
    "Next step: nothing exists at that path/name/id; search or list first instead of repeating the same call."

    nil))

(defn shell-timeout-hint
  "Targeted recovery advice when a shell command times out."
  [cmd]
  (let [c (str/lower-case (str (or cmd "")))]
    (cond
      (re-find #"\bfind\s+/" c)
      "Next step: `find /` timed out; scope the directory and add `-maxdepth` or use `mdfind` / `search` instead."

      :else
      "Next step: command timed out; narrow its scope, increase timeout-ms, or run detached (`nohup ... > /tmp/job.log 2>&1 &`).")))

(defn attach-next-step
  "Pure post-processor: when `result` has :status :error, classifies the error
   and attaches :failure/kind + :next-step (preserving all existing keys)."
  [request result]
  (if (not= :error (:status result))
    result
    (let [err-text (str (:error/message result) " " (:stderr result) " " (:error result))
          kind (classify-tool-failure err-text)
          hint (cond
                 (and (= :timeout kind) (= "shell" (:tool/name request)))
                 (shell-timeout-hint (get-in request [:tool/args :cmd]))

                 kind (next-step-hint kind)
                 :else nil)]
      (cond-> result
        kind (assoc :failure/kind kind)
        hint (assoc :next-step hint)))))
