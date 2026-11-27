(ns bb-agent.gate-scope
  "Verification gate execution-scope validator (ported from LoopForge
   domain/verification.go). Detects git-dependent verification commands and
   validates gate modes/scopes up front before starting a goal run."
  (:require [clojure.string :as str]))

(def ^:private valid-modes #{nil :command :manual :mr})
(def ^:private valid-scopes #{nil :run :repositories})

(defn command-needs-repository?
  "True when a gate command inspects or mutates a git checkout and therefore
   requires `:execution-scope :repositories`."
  [command]
  (if (or (nil? command) (str/blank? (str command)))
    false
    (let [c (str/trim (str command))
          c (if (str/starts-with? c "set -e") (str/trim (subs c 6)) c)]
      (or (str/includes? c "git ")
          (str/includes? c "git\t")
          (str/includes? c ".git/")
          (str/includes? c "$(git")))))

(defn validate-gate
  "Validate a verification gate map {:id :mode :execution-scope :commands}.
   Returns {:ok? true} or {:ok? false :error string}."
  [{:keys [id mode execution-scope commands]}]
  (let [eff-mode (or mode (when (some #(not (str/blank? (str %))) commands) :command))]
    (cond
      (not (contains? valid-modes eff-mode))
      {:ok? false :error (str "Verification gate " id " has invalid mode: " (pr-str mode))}

      (not (contains? valid-scopes execution-scope))
      {:ok? false :error (str "Verification gate " id " has invalid execution-scope: " (pr-str execution-scope))}

      (and (= :run execution-scope)
           (some command-needs-repository? commands))
      {:ok? false :error (str "Verification gate " id " runs git commands in :run scope; set :execution-scope :repositories")}

      :else
      {:ok? true})))
