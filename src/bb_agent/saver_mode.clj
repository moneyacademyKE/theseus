(ns bb-agent.saver-mode
  "Auto-engaging Token Saver Mode: soft daily-budget degradation for
   interactive turns.

   Modes (:saver/mode in cfg):
   - :off  (default) — never engages
   - :on   — always engaged
   - :auto — engages when today's usage tokens reach :saver/auto-threshold
             (default 0.75) of :saver/daily-token-budget."
  (:require [bb-agent.usage :as usage]
            [clojure.string :as str]))

(def default-auto-threshold 0.75)
(def output-token-ratio 0.6)
(def step-ratio 0.6)
(def history-ratio 0.5)

(def system-hint
  "Token Saver Mode is active: keep replies concise, skip filler, and prefer targeted tool reads over broad scans.")

(defn today-prefix []
  (str (java.time.LocalDate/now)))

(defn today-tokens
  "Sum total tokens from today's turn events in usage.edn."
  ([] (today-tokens (usage/load-events) (today-prefix)))
  ([events day-prefix]
   (reduce (fn [acc e]
             (if (and (not= :tool (:usage/event e))
                      (not= :tool (:event/kind e))
                      (str/starts-with? (str (or (:created/at e) (:timestamp e) "")) day-prefix))
               (+ acc (long (or (:tokens/total e)
                                (:total/tokens e)
                                (+ (long (or (:tokens/input e) (:input/tokens e) 0))
                                   (long (or (:tokens/output e) (:output/tokens e) 0))))))
               acc))
           0
           events)))

(defn evaluate
  "Pure policy decision -> {:active? bool :mode kw :today-tokens n :budget n :ratio double}."
  ([cfg] (evaluate cfg (when (= :auto (:saver/mode cfg)) (today-tokens))))
  ([cfg used-tokens]
   (let [mode (or (:saver/mode cfg) :off)
         budget (some-> (:saver/daily-token-budget cfg) long)
         threshold (double (or (:saver/auto-threshold cfg) default-auto-threshold))
         used (long (or used-tokens 0))
         ratio (if (and budget (pos? budget)) (/ (double used) budget) 0.0)
         active? (case mode
                   :on true
                   :auto (boolean (and budget (pos? budget) (>= ratio threshold)))
                   false)]
     {:active? active?
      :mode mode
      :today-tokens used
      :budget budget
      :ratio ratio})))

(defn apply-cfg
  "When saver is active, scale down :max-tool-rounds, :history-budget-chars,
   and provider :max-tokens, and tag :saver/active? true."
  [cfg {:keys [active?]}]
  (if-not active?
    cfg
    (let [prov (:provider cfg)
          rounds (long (or (:max-tool-rounds cfg) 8))
          scaled-rounds (max 2 (long (Math/floor (* rounds step-ratio))))
          hist (:history-budget-chars cfg)
          scaled-hist (when hist (max 400 (long (Math/floor (* (long hist) history-ratio)))))
          p-max (get-in cfg [:providers prov :max-tokens])
          scaled-p-max (when p-max (max 256 (long (Math/floor (* (long p-max) output-token-ratio)))))]
      (cond-> (assoc cfg
                     :saver/active? true
                     :max-tool-rounds scaled-rounds)
        scaled-hist (assoc :history-budget-chars scaled-hist)
        scaled-p-max (assoc-in [:providers prov :max-tokens] scaled-p-max)))))
