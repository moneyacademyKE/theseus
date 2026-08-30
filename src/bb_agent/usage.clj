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

(defn- price-entry [provider model]
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
                     fallback-tried fallback-served]}]
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
      fallback-served (assoc :fallback/served fallback-served))))

(defn append-event! [entry]
  (let [path (usage-file)
        events (conj (load-events) entry)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str events))
    events))

(defn- fallback-stats [events]
  (let [hits (filter :fallback/served events)
        total (count events)]
    {:hits (count hits)
     :rate (if (pos? total) (double (/ (count hits) total)) 0.0)
     :by-served (frequencies (map :fallback/served hits))}))

(defn report []
  (let [events (load-events)
        total (reduce + 0 (map :tokens/total events))]
    {:usage/events (count events)
     :tokens/total total
     :cost/estimate-usd (reduce + 0.0 (map :cost/estimate-usd events))
     :by-provider (->> events
                       (group-by :provider)
                       (map (fn [[provider xs]]
                              [provider {:usage/events (count xs)
                                         :tokens/total (reduce + 0 (map :tokens/total xs))}]))
                       (into {}))
     :fallback (fallback-stats events)}))
