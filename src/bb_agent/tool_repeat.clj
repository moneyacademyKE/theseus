(ns bb-agent.tool-repeat
  "Consecutive identical tool-call detector + poisoned-history pruner
   (ported from OpenCrabs tool_repeat.rs + repetition.rs).
   1. Nudges once at 4 consecutive identical (tool, canonical-args) calls
      before provider server-side loop guardrails (HTTP 500) brick the session.
   2. Resets on retry/fallback so replayed calls don't double-count.
   3. Collapses consecutive duplicate pure-tool rounds (tool_use + paired
      tool_result) if a provider repetitive-tool 500 still lands."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.string :as str]))

(def repeat-nudge-at 4)

(defn- canonicalize [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [(str k) (canonicalize v)]))
                   x)
    (vector? x) (mapv canonicalize x)
    (sequential? x) (mapv canonicalize x)
    :else x))

(defn signature
  "Canonical order-independent identity for a tool call."
  [tool-name args]
  (str tool-name "|" (pr-str (canonicalize args))))

(defn repeat-nudge
  "Mechanism-based correction with an explicit non-tool exit path."
  [tool-name count]
  (str "[System: you have now called `" tool-name "` " count " times in a row with "
       "identical arguments. The result above is the same one you already have; "
       "calling it again with the same arguments cannot return anything different. "
       "Change the arguments, use a different approach, or if you are blocked, "
       "say so and explain what you need instead of repeating the call.]"))

(defn create-tracker
  ([] (create-tracker repeat-nudge-at))
  ([threshold]
   (atom {:last nil :consecutive 0 :nudged? false :threshold (long threshold)})))

(defn reset!
  "Forget the current run (call on turn retry or provider fallback)."
  [tracker]
  (clojure.core/swap! tracker assoc :last nil :consecutive 0 :nudged? false)
  nil)

(defn observe!
  "Record a requested tool call and return {:verdict :fresh|:repeating|:nudge-now :count N ...}."
  [tracker tool-name args]
  (let [sig (signature tool-name args)
        out (atom nil)]
    (clojure.core/swap!
     tracker
     (fn [{:keys [last consecutive nudged? threshold] :as state}]
       (if (not= last sig)
         (do (clojure.core/reset! out {:verdict :fresh :count 1})
             (assoc state :last sig :consecutive 1 :nudged? false))
         (let [c (inc consecutive)]
           (if (and (>= c threshold) (not nudged?))
             (do (clojure.core/reset! out {:verdict :nudge-now
                                           :count c
                                           :nudge (repeat-nudge tool-name c)})
                 (assoc state :consecutive c :nudged? true))
             (do (clojure.core/reset! out {:verdict :repeating :count c})
                 (assoc state :consecutive c)))))))
    @out))

(defn repetitive-tool-error?
  "True when a provider error message is a server-side repetitive-tool-call guardrail."
  [msg]
  (boolean
   (when (string? msg)
     (let [s (str/lower-case msg)]
       (or (str/includes? s "repetitive tool calls")
           (str/includes? s "repetitive tool call")
           (str/includes? s "duplicate tool calls detected"))))))

(defn- pure-tool-use-signature
  "Signature for an assistant message that is PURELY tool requests (no prose).
   Returns nil if the message carries non-blank text or has no tool requests."
  [msg]
  (when (and (= :assistant (:role msg))
             (seq (:tool/requests msg))
             (str/blank? (str (or (:content msg) ""))))
    (str/join "\n" (map #(signature (:tool/name %) (:tool/args %))
                        (:tool/requests msg)))))

(defn- tool-result-message? [msg]
  (or (= :tool (:role msg))
      (boolean (seq (:tool/results msg)))))

(defn prune-repetitive-tool-calls
  "Collapse consecutive identical pure-tool rounds (dropping duplicate tool_use
   and its immediately-following tool_result while keeping the first occurrence).
   Returns {:messages [...] :removed N}."
  [messages]
  (let [msgs (vec messages)
        n (count msgs)]
    (loop [i 0
           last-sig nil
           out []
           removed 0]
      (if (>= i n)
        {:messages out :removed removed}
        (let [m (nth msgs i)
              sig (pure-tool-use-signature m)]
          (if sig
            (if (= sig last-sig)
              (let [next-is-res? (and (< (inc i) n)
                                      (tool-result-message? (nth msgs (inc i))))]
                (recur (if next-is-res? (+ i 2) (inc i))
                       last-sig
                       out
                       (+ removed (if next-is-res? 2 1))))
              (recur (inc i) sig (conj out m) removed))
            (recur (inc i)
                   (if (tool-result-message? m) last-sig nil)
                   (conj out m)
                   removed)))))))
