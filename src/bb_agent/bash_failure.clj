(ns bb-agent.bash-failure
  "Shell failure population classifier + recoverable-failure policy
   (ported from OpenCrabs bash_failure.rs + feedback_policy.rs).
   Separates genuine model/tool defects from environmental misses so RSI
   never penalizes or disables working tools over missing files or dead ports."
  (:require [clojure.string :as str]))

(def ^:private model-error-markers
  ["syntax error"
   "unexpected token"
   "unexpected end of file"
   "parse error"
   "unbound variable"
   "bad substitution"
   "command not found"
   "no such command"
   "invalid option"
   "illegal option"
   "unknown option"
   "unrecognized option"
   "missing operand"
   "too many arguments"])

(def ^:private environmental-markers
  ["no such file or directory"
   "cannot access"
   "does not exist"
   "permission denied"
   "operation not permitted"
   "read-only file system"
   "no space left on device"
   "resource temporarily unavailable"
   "device or resource busy"
   "connection refused"
   "connection reset"
   "connection timed out"
   "network is unreachable"
   "host is unreachable"
   "no route to host"
   "could not resolve host"
   "name or service not known"
   "temporary failure in name resolution"
   "address already in use"
   "timed out"
   "timeout"
   "broken pipe"
   "could not connect"
   "service unavailable"
   "is not running"
   "communications error"])

(defn- stderr-section [snippet]
  (when (string? snippet)
    (let [idx (.indexOf ^String snippet "STDERR:\n")]
      (when (>= idx 0)
        (subs snippet (+ idx 8))))))

(defn classify
  "Classify a shell failure snippet into :model-error, :environmental, or :unknown.
   Scans STDERR only when present (so STDOUT file contents cannot launder a real
   defect into :environmental), and gives :model-error precedence."
  [snippet]
  (if (or (nil? snippet) (str/blank? (str snippet)))
    :unknown
    (let [scanned (str/lower-case (or (stderr-section (str snippet)) (str snippet)))]
      (cond
        (some #(str/includes? scanned %) model-error-markers) :model-error
        (some #(str/includes? scanned %) environmental-markers) :environmental
        :else :unknown))))

(defn recoverable-tool-failure?
  "True when a failed tool call is an expected/environmental/recoverable outcome
   that should stay out of the RSI tool-defect denominator."
  [tool-name error-msg]
  (if (or (nil? error-msg) (str/blank? (str error-msg)))
    false
    (let [e (str/lower-case (str error-msg))
          t (str tool-name)]
      (cond
        (= t "shell")
        (= :environmental (classify error-msg))

        (= t "edit_file")
        (or (str/includes? e "not found")
            (str/includes? e "ambiguous")
            (str/includes? e "may have changed"))

        (or (str/ends-with? t "_send")
            (str/ends-with? t "_send_file")
            (str/ends-with? t "_connect"))
        (or (str/includes? e "not connected")
            (str/includes? e "connection refused")
            (str/includes? e "timed out"))

        :else false))))
