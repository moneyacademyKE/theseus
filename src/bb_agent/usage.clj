(ns bb-agent.usage
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn usage-file []
  (fs/path (config/home) "state" "usage.edn"))

(defn load-events []
  (let [path (usage-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      [])))

(defn- token-estimate [text]
  (long (Math/ceil (/ (count (or text "")) 4.0))))

(defn- pricing-file []
  (fs/path (config/home) "usage_pricing.edn"))

(def default-pricing
  {:openai [{:prefix "gpt-5" :input_per_m 1.25 :output_per_m 10.0}
            {:prefix "gpt-4" :input_per_m 30.0 :output_per_m 60.0}]
   :anthropic [{:prefix "claude-opus" :input_per_m 5.0 :output_per_m 25.0}
               {:prefix "claude-sonnet" :input_per_m 3.0 :output_per_m 15.0}
               {:prefix "claude-haiku" :input_per_m 1.0 :output_per_m 5.0}]
   :qwen [{:prefix "qwen3.6-plus" :input_per_m 0.50 :output_per_m 3.0}]
   :opencode [{:prefix "mimo-v2-pro" :input_per_m 1.0 :output_per_m 3.0}
              {:prefix "mimo-v2-omni" :input_per_m 0.40 :output_per_m 2.0}]})

(defn pricing []
  (let [path (pricing-file)]
    (if (fs/regular-file? path)
      (merge default-pricing (edn/read-string (slurp (str path))))
      default-pricing)))

(defn normalize-model-name [model]
  (let [lower (-> (or model "")
                  (str/split #"/")
                  last
                  str/lower-case)
        base (reduce (fn [s suffix]
                       (if (str/ends-with? s suffix)
                         (subs s 0 (- (count s) (count suffix)))
                         s))
                     lower [":free" "-free" "-thinking"])
        base (or (second (re-matches #"claude-(.*)" base)) base)]
    (case base
      ("opus" "opus-4-6") "opus-4-6"
      ("sonnet" "sonnet-4-6") "sonnet-4-6"
      ("haiku" "haiku-4-5" "haiku-4-5-20251001") "haiku-4-5"
      ("qwen-3.6-plus" "qwen3.6-plus" "coder-model") "qwen3.6-plus"
      base)))

(defn price-entry
  "The catalog entry that prices one provider+model, or nil.

   Public on purpose: nil here is the measured difference between a dollar
   figure and the word `unpriced`, so any caller that sums costs must be able
   to ask. Live before this existed (2026-11-03): every event in
   state/usage.edn carried provider `:openai-compatible` across 7 models,
   `default-pricing` has keys :openai :anthropic :qwen :opencode, and
   $THESEUS_HOME/usage_pricing.edn does not exist — so this returned nil for
   all 773 events and the report printed `$0.0000 est` over 34,499,877 tokens:
   a confident zero for a number nobody could compute.

   Source of the rule: Akas333/TYSA
   https://github.com/Akas333/TYSA/blob/main/docs/compaction-accounting.md —
   missing models or required usage stay unpriced; no catalog limit means no
   invented denominator; records whose detail is lost are not reconstructed
   from totals."
  [provider model]
  (let [provider* (keyword (name provider))
        model* (str/lower-case (or model ""))]
    (some #(when (str/includes? model* (str/lower-case (:prefix %))) %)
          (get (pricing) provider*))))

(defn- cost-estimate [provider model input output cache-read cache-write]
  (let [{:keys [input_per_m output_per_m cache_read_per_m cache_write_per_m]} (price-entry provider model)]
    (+ (* (/ input 1000000.0) (or input_per_m 0.0))
       (* (/ output 1000000.0) (or output_per_m 0.0))
       (* (/ cache-read 1000000.0) (or cache_read_per_m 0.0))
       (* (/ cache-write 1000000.0) (or cache_write_per_m input_per_m 0.0)))))

(defn event [{:keys [session-id provider model prompt final usage
                     fallback-tried fallback-served fallback-model ok]}]
  (let [input (or (:tokens/input usage) (token-estimate prompt))
        output (or (:tokens/output usage) (token-estimate final))
        cache-read (or (:tokens/cache-read usage) 0)
        cache-write (or (:tokens/cache-write usage) 0)]
    (cond-> {:usage/event :turn
             :session/id session-id
             :provider provider
             :model (normalize-model-name model)
             :tokens/input input
             :tokens/output output
             :tokens/cache-read cache-read
             :tokens/cache-write cache-write
             :tokens/total (+ input output cache-read cache-write)
             :cost/estimate-usd (cost-estimate provider model input output cache-read cache-write)
             :created/at (str (java.time.Instant/now))}
      fallback-tried (assoc :fallback/tried fallback-tried)
      fallback-served (assoc :fallback/served fallback-served)
      fallback-model (assoc :fallback/model fallback-model)
      (some? ok) (assoc :ok ok))))

(defn append-event! [entry]
  (let [path (usage-file)
        events (conj (load-events) entry)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str events))
    events))

(defn- turn-events
  "Usage events that are LLM turns. The ledger also carries :tool funnel
  events (see tool-event!) — telemetry about the tool surface, not usage.
  Aggregations that speak about providers or tokens filter through this."
  [events]
  (filter #(= :turn (:usage/event %)) events))

(defn tool-event!
  "Append one tool outcome to the usage ledger. This is THE single
  recording site for tool traffic — tool.clj/handle-tool-request is its
  only caller (the funnel; goal make-rsi-do-this pins exactly one
  non-usage src reference). Concurrency decision, made deliberately:
  append is a read-modify-write spit, so concurrent writers can race
  and lose an event; that is accepted — usage is telemetry, not the
  ledger-of-record, and a file lock serializing every tool call would
  tax the hot path to protect data nobody reconciles. Attribution is
  the price of admission: no session id, no event — an outcome that
  cannot be attributed is a fixture, not signal (this is also what
  keeps contextless unit-test calls out of the live ledger)."
  [{:keys [tool session-id status ok denial-source]}]
  (when session-id
    (append-event! {:usage/event :tool
                    :tool/name tool
                    :ok (boolean ok)
                    :tool/status status
                    :denial/source denial-source
                    :session/id session-id
                    :created/at (str (java.time.Instant/now))})))

(defn- fallback-stats [events]
  (let [hits (filter :fallback/served events)
        total (count events)]
    {:hits (count hits)
     :rate (if (pos? total) (double (/ (count hits) total)) 0.0)
     :by-served (frequencies (map :fallback/served hits))}))

(defn cost-coverage
  "Split turn events into priced and unpriced by whether the catalog has a rate.

   Two rules, both from TYSA's compaction-accounting.md:
   * an unpriced event contributes NOTHING to the total — never 0.0, because a
     sum of zeros over unexamined events reads as a measured zero (the live
     defect: `$0.0000 est` over 773 events, 34.5M tokens);
   * nil for the total when nothing could be priced at all — not-found is not
     zero. Tokens and event counts still report; only the dollar figure is
     withheld."
  [events]
  (let [priceable? #(price-entry (:provider %) (:model %))
        priced (filterv priceable? events)
        unpriced (filterv (complement priceable?) events)]
    {:priced priced
     :unpriced unpriced
     :estimate (when (seq priced)
                 (reduce + 0.0 (map :cost/estimate-usd priced)))
     :unpriced-providers (->> unpriced
                              (map (comp str name :provider))
                              distinct sort vec)}))

(defn report []
  (let [events (turn-events (load-events))
        total (reduce + 0 (map :tokens/total events))
        cov (cost-coverage events)]
    {:usage/events (count events)
     :tokens/total total
     :cost/estimate-usd (:estimate cov)
     :cost/priced-events (count (:priced cov))
     :cost/unpriced-events (count (:unpriced cov))
     :cost/unpriced-providers (:unpriced-providers cov)
     :by-provider (->> events
                       (group-by :provider)
                       (map (fn [[provider xs]]
                              [provider {:usage/events (count xs)
                                         :tokens/total (reduce + 0 (map :tokens/total xs))}]))
                       (into {}))
     :fallback (fallback-stats events)}))
