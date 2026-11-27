(ns bb-agent.loop-break
  "Loop-guard fallback promotion & diagnostic formatting (ported from OpenCrabs loop_break.rs).
   When a model keeps repeating the same tool call, a different model in the
   fallback chain rarely reproduces the same loop. Carries the raw dropped call
   so a loop break is never silent about what did not run."
  (:require [clojure.string :as str]))

(def ^:private pending-call-max-chars 240)

(defn- truncate-call [s]
  (if (<= (count s) pending-call-max-chars)
    s
    (str (subs s 0 (dec pending-call-max-chars)) "…")))

(defn describe-pending-calls
  "Render the raw tool call(s) a loop guard is about to drop (<=240 chars each)."
  [tool-requests]
  (->> tool-requests
       (map (fn [{:tool/keys [name args]}]
              (truncate-call (str name " " (pr-str args)))))
       (str/join "; ")))

(defn chain-exhausted-message
  "User-facing message once the loop guard has exhausted every fallback provider."
  [tool-name count pending providers-tried]
  (str "⚠️ Loop guard: `" tool-name "` repeated " count "x with identical arguments; "
       "dropped call: " pending
       (when (and providers-tried (> (long providers-tried) 1))
         (str "; every provider in the fallback chain (" providers-tried " providers tried) hit the same loop"))
       ". Nothing is queued — say the word to resume or narrow the request."))

(defn promote-next-fallback
  "Given `cfg` and a set of `tried-providers`, return `[next-cfg next-step]` for the
   first untried fallback entry in `:provider/fallbacks`, or nil when exhausted."
  [cfg tried-providers]
  (let [fbs (:provider/fallbacks cfg)
        next-fb (first (remove #(contains? tried-providers (keyword (:provider %))) fbs))]
    (when next-fb
      (let [p (keyword (:provider next-fb))
            m (or (:model next-fb) (get-in cfg [:providers p :default-model]) (:model cfg))
            remaining (vec (remove #(= (keyword (:provider %)) p) fbs))]
        [(-> cfg
             (assoc :provider p :model m :provider/fallbacks remaining))
         {:provider p :model m}]))))
