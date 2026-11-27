(ns bb-agent.rsi-triage
  "Empirical-Bayes + 7-day half-life decay RSI triage & tiered optimization
   preferences (ported from LoopForge evolution/triage.go + sop_metrics.go).
   Prevents small-sample alarms and automatically decays old fixed outages
   out of RSI without manual ledger surgery."
  (:require [clojure.string :as str]))

(def default-prior-strength 10.0)
(def half-life-ms (* 7.0 24.0 3600.0 1000.0))

(defn decay-weight
  "Exponential decay weight in (0, 1] with a 7-day half-life."
  [now-ms event-ms]
  (let [age (- (double now-ms) (double event-ms))]
    (if (<= age 0.0)
      1.0
      (Math/pow 0.5 (/ age half-life-ms)))))

(defn posterior-rate
  "Empirical-Bayes Beta-Binomial posterior mean shrunk toward `:baseline`."
  [{:keys [failures trials baseline prior-strength]
    :or {baseline 0.05 prior-strength default-prior-strength}}]
  (let [alpha0 (* (double prior-strength) (double baseline))
        beta0 (* (double prior-strength) (- 1.0 (double baseline)))
        f (max 0.0 (double (or failures 0.0)))
        n (max f (double (or trials 0.0)))]
    (/ (+ alpha0 f) (+ alpha0 beta0 n))))

(def ^:private pref-labels
  {:clean "maximize first-pass correctness"
   :tokens "reduce token consumption"
   :tools "reduce unnecessary tool calls"
   :duration "shorten execution duration"})

(defn preference-priority-text
  "Format ordered preference keywords as `P1 ... > P2 ...`."
  [prefs]
  (->> prefs
       (keep pref-labels)
       (map-indexed (fn [i label] (str "P" (inc i) " " label)))
       (str/join " > ")))

(defn preference-guidance
  "Build tiered conflict-resolution instructions for RSI/improver prompts."
  [prefs]
  (let [chain (preference-priority-text prefs)]
    (when (seq chain)
      (str "Optimization priority (strictly preserve order on trade-offs): " chain
           ". Protect P1 first; secondary goals apply only without regressing higher-priority "
           "metrics or verification gates."))))
