(ns bb-agent.redact
  "Unified secret redaction funnel: fixed secret-shape patterns plus
   runtime-registered literal secrets (API keys, bot tokens, HMAC secrets,
   A2A tokens) so custom credentials are scrubbed before reaching the LLM,
   session history, or error logs."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def ^:private min-registered-secret-len 8)

(def ^:private registered-secrets
  "Set of runtime-registered literal secret strings."
  (atom #{}))

(defn register-secret!
  "Register a literal secret string for scrubbing (ignores nil/short values)."
  [value]
  (when (and (string? value) (>= (count (str/trim value)) min-registered-secret-len))
    (swap! registered-secrets conj (str/trim value)))
  nil)

(defn clear-registered-secrets!
  "Clear runtime-registered secrets (for deterministic tests)."
  []
  (reset! registered-secrets #{})
  nil)

(defn register-config-secrets!
  "Extract and register literal credentials from a Theseus config map."
  [cfg]
  (when (map? cfg)
    (register-secret! (get-in cfg [:telegram :token]))
    (register-secret! (get-in cfg [:notify :hmac-secret]))
    (doseq [[_ pcfg] (:providers cfg)]
      (when (map? pcfg)
        (register-secret! (:api-key pcfg)))))
  nil)

(def ^:private private-key-re
  (re-pattern (str "-----" "BEGIN [A-Z ]*PRIVATE KEY" "-----")))

(def secret-patterns
  "Secret shapes scrubbed across tool outputs, provider errors, and sessions.
   Boundary checks tolerate pr-str escapes (\\n, \\t, \\r) ahead of tokens."
  [#"\d{8,}:AA[A-Za-z0-9_-]{20,}"
   #"(?:(?<![A-Za-z0-9_])|(?<=\\[ntr]))sk-[A-Za-z0-9][A-Za-z0-9_-]{7,}"
   #"ghp_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"xox[baprs]-[A-Za-z0-9-]{10,}"
   #"AKIA[0-9A-Z]{16}"
   #"AIza[0-9A-Za-z_-]{30,}"
   #"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"
   private-key-re
   #"(?i)(?:(?<![A-Za-z0-9_])|(?<=\\[ntr]))Bearer\s+[A-Za-z0-9._-]{20,}"
   #"(?i)(?:(?<![A-Za-z0-9_])|(?<=\\[ntr]))Basic\s+[A-Za-z0-9+/=]{16,}"])

(defn redact-secrets
  "Scrub both known secret regex patterns and runtime-registered literal
   secrets from string s."
  [s]
  (when (some? s)
    (let [step1 (reduce (fn [acc pat] (str/replace acc pat "<REDACTED-SECRET>"))
                        (str s)
                        secret-patterns)
          literals (sort-by (comp - count) @registered-secrets)]
      (reduce (fn [acc lit]
                (if (str/includes? acc lit)
                  (str/replace acc lit "<REDACTED-SECRET>")
                  acc))
              step1
              literals))))

(defn redact-deep
  "Recursively scrub every string leaf inside nested EDN data structures."
  [x]
  (walk/postwalk (fn [node]
                   (if (string? node)
                     (redact-secrets node)
                     node))
                 x))
