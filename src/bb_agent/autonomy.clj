(ns bb-agent.autonomy
  "Autonomy is earned by evidence and granted by the owner — never the
   reverse. RSI v1 (rsi.clj) is propose-only: the LLM appends suggestions
   to brain/improvements.md and the owner reviews, applies, refuses.

   This namespace is the bridge that lets the ledger EARN autonomy
   without letting any LLM GRANT it:

   - outcomes: the owner records what happened to applied proposals
     (`bb autonomy applied|reverted <file> [note]`) — the owner is the
     source of truth; the ledger is just data (state/rsi/outcomes.edn).
   - earned tier: pure evaluation of that ledger against published bars.
     Rates are refused below :min-applied — small samples make liars of
     rates (the honesty gate rsi/analyze carries, scaled to proposals).
   - granted ceiling: :autonomy/max-tier in config.edn, owner-edited.
     Absent or unknown means :propose — the v1 constitution, unchanged.
   - effective tier: min(granted, earned). The owner buys the ceiling
     once; evidence moves the floor. A revert spike pulls the floor back
     down with no demotion machinery — retention is just arithmetic.

   Nothing here writes a brain file. Applying a proposal remains an
   owner act; when tiers rise, the ALLOWED surfaces widen (tier-allows?)
   so existing call sites can ask before they act."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def tiers
  "Low to high. :propose — suggestions only (rsi.clj, unchanged).
   :apply-knowledge — may write brain/knowledge/* only (index-noted,
   never always-loaded: mistakes are cheap). :apply-all — any brain file."
  [:propose :apply-knowledge :apply-all])

(def promotion-bar
  "Evidence bars per tier. :min-applied is the honesty gate — below it
   the rate is not computed and the tier is not earned. Deliberately
   correlation-free in v1: retention is (applied - reverted)/applied.
   Proposal identity joins the ledger only when the ledger earns it."
  {:apply-knowledge {:min-applied 8 :min-retention 0.8 :max-recent-reverts 0}
   :apply-all {:min-applied 20 :min-retention 0.9 :max-recent-reverts 1}})

(def ^:private rate-min-applied
  (get-in promotion-bar [:apply-knowledge :min-applied]))

(def ^:private recent-window 5)

(def ^:private usage-str
  "Usage: bb autonomy                        | status report
         bb autonomy applied <file> [note]  | record an applied proposal
         bb autonomy reverted <file> [note] | record a revert")

;; ---------- pure core ----------

(defn- tier-index [t] (.indexOf tiers t))

(defn- next-tier [t]
  (let [i (inc (tier-index t))]
    (when (< i (count tiers)) (nth tiers i))))

(defn- sanitize-tier [t]
  (if (some #{t} tiers) t :propose))

(defn stats
  "Pure: ledger map → outcome statistics. :retention is nil and
   :sufficient false below rate-min-applied applications — rates on
   small samples are lies, so we refuse to compute them."
  [ledger]
  (let [applied (count (:applied ledger))
        reverted (count (:reverted ledger))
        events (sort-by :at (concat (:applied ledger) (:reverted ledger)))
        recent-reverts (->> (take-last recent-window events)
                            (filter #(= :reverted (:kind %)))
                            count)
        sufficient (>= applied rate-min-applied)]
    {:sufficient sufficient
     :applied applied
     :reverted reverted
     :survived (max 0 (- applied reverted))
     :retention (when sufficient
                  (max 0.0 (/ (double (max 0 (- applied reverted))) applied)))
     :recent-reverts recent-reverts}))

(defn meets-bar?
  "Pure: does the evidence clear one tier's bar?"
  [stats bar]
  (and (:sufficient stats)
       (>= (:applied stats) (:min-applied bar))
       (>= (:retention stats) (:min-retention bar))
       (<= (:recent-reverts stats) (:max-recent-reverts bar))))

(defn earned-tier
  "Pure: highest tier whose bar the ledger currently clears. Reverts
   decay retention, so this falls as well as rises — no demotion code."
  ([ledger] (earned-tier ledger promotion-bar))
  ([ledger bar]
   (let [s (stats ledger)]
     (loop [tier :propose]
       (let [n (next-tier tier)]
         (if (and n (meets-bar? s (get bar n)))
           (recur n)
           tier))))))

(defn granted-tier
  "The owner's ceiling. Reads :autonomy/max-tier from config.edn;
   absent, nil, or unknown means :propose — the constitution's default."
  ([cfg] (sanitize-tier (get-in cfg [:autonomy :max-tier])))
  ([] (granted-tier (config/load-config))))

(defn effective-tier
  "min(granted, earned): the owner sets what is allowed at most, the
   ledger says what is deserved so far."
  ([ledger] (effective-tier (granted-tier) ledger))
  ([granted ledger]
   (nth tiers (min (tier-index (sanitize-tier granted))
                   (tier-index (earned-tier ledger))))))

(defn tier-allows?
  "Would this tier permit a write to this path? :propose — nothing
   (propose! stays the only surface); :apply-knowledge — brain/knowledge/*
   only; :apply-all — anything under brain/. Pure; call sites ask."
  [tier path]
  (let [p (str path)]
    (case tier
      :propose false
      :apply-knowledge (boolean (re-find #"brain/knowledge/" p))
      :apply-all (boolean (re-find #"brain/" p))
      false)))

(defn report
  "Pure: human-readable status — earned vs granted vs effective, the
   evidence, and exactly what the next tier still needs."
  [granted ledger]
  (let [g (sanitize-tier granted)
        e (earned-tier ledger)
        s (stats ledger)
        n (next-tier e)
        bar (when n (get promotion-bar n))
        pct (fn [v] (if v (format "%.0f%%" (* 100 v)) "n/a"))
        gaps (when bar
               (str/join ", "
                 (cond-> []
                   (< (:applied s) (:min-applied bar))
                   (conj (str "applied>=" (:min-applied bar)
                              " (have " (:applied s) ")"))
                   (and (:sufficient s) (< (:retention s) (:min-retention bar)))
                   (conj (str "retention>=" (:min-retention bar)
                              " (have " (pct (:retention s)) ")"))
                   (> (:recent-reverts s) (:max-recent-reverts bar))
                   (conj (str "recent-reverts<=" (:max-recent-reverts bar)
                              " (have " (:recent-reverts s) ")")))))]
    (str/join "\n"
      (concat
        [(str "earned: " e "   granted: " g "   effective: "
              (effective-tier g ledger))]
        [(if (:sufficient s)
           (str "evidence: " (:survived s) "/" (:applied s)
                " survived (" (pct (:retention s)) "), reverts "
                (:reverted s) ", recent (last " recent-window "): "
                (:recent-reverts s))
           (str "insufficient evidence: " (:applied s) " of "
                rate-min-applied " applications — rates lie on small samples"))]
        (when n [(str "next tier " n " needs: " gaps)])
        ["the ledger earns; the owner grants — set :autonomy/max-tier in config.edn"]))))

;; ---------- effects (thin) ----------

(defn ledger-path []
  (fs/path (config/home) "state" "rsi" "outcomes.edn"))

(defn load-ledger []
  (let [p (ledger-path)]
    (if (fs/regular-file? p)
      (edn/read-string (slurp (str p)))
      {:applied [] :reverted []})))

(defn record!
  "Append one outcome event and atomically install the ledger
   (tmp + move — the write-config! pattern)."
  [kind file note]
  (let [p (ledger-path)
        event {:at (str (java.time.Instant/now))
               :kind kind
               :file (str file)
               :note (when-not (str/blank? (str note)) (str note))}
        ledger' (update (load-ledger)
                        (if (= :reverted kind) :reverted :applied)
                        (fnil conj [])
                        event)
        tmp (fs/path (fs/parent p) (str ".outcomes." (System/nanoTime)))]
    (fs/create-dirs (fs/parent p))
    (spit (str tmp) (pr-str ledger'))
    (fs/move tmp p {:replace-existing true})
    ledger'))

;; ---------- CLI ----------

(defn -main
  "bb autonomy | bb autonomy applied|reverted <file> [note]"
  [& args]
  (let [[cmd file & more] (vec args)
        note (str/join " " more)]
    (cond
      (nil? cmd)
      (println (report (granted-tier) (load-ledger)))

      (= "applied" cmd)
      (if (str/blank? (str file))
        (do (println usage-str) (System/exit 2))
        (do (record! :applied file note)
            (println (str "recorded: applied " file))))

      (= "reverted" cmd)
      (if (str/blank? (str file))
        (do (println usage-str) (System/exit 2))
        (do (record! :reverted file note)
            (println (str "recorded: reverted " file))))

      :else
      (do (println usage-str) (System/exit 2)))))
