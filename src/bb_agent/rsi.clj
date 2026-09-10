(ns bb-agent.rsi
  "RSI v1 — ported from OpenCrabs' recursive self-improvement loop, minus
   the autonomy. OpenCrabs' ten Rust modules around this loop are almost
   entirely burn scars: a backoff ladder because hourly cycles with zero
   improvements burned quota, a headless default-off because unattended
   runs looked like hangs, a 50-entry minimum because small samples lied.

   Theseus keeps the lessons as functions, not daemons: `digest` aggregates
   the usage ledger, `analyze` derives opportunities with an honest minimum
   sample, and `propose!` appends deduplicated suggestions to
   brain/improvements.md for the OWNER to review and apply. No LLM writes
   brain files unattended in v1 — the proposal ledger earns that autonomy."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.usage :as usage]
            [clojure.string :as str]))

(def ^:private default-min-events 50)
(def ^:private min-provider-turns 2)
(def flag-rate 0.3)

(defn excluded-providers
  "Providers kept out of RSI signal entirely — test doubles like :fake,
   whose failures are e2e fixtures rather than a fact about the world. A
   noisy provider is a proposal generator that never ships anything real:
   the ledger holds 6 synthetic :fake failures, already 27% against a 30%
   flag. Exclusion is config, not a hardcode, so :rsi/exclude-providers
   can silence any future stand-in. Event counts stay honest — only the
   per-provider table and the signals derived from it are filtered."
  []
  (set (or (:rsi/exclude-providers (config/load-config)) #{:fake})))

(defn provider-signal
  "The per-provider aggregate that RSI is allowed to reason about. One
   filter, one place: digest builds its table from this, so analyze,
   nearest-signals, and every opportunity derived downstream inherit the
   same exclusion with no second implementation to drift out of sync."
  [providers]
  (let [excluded (excluded-providers)]
    (reduce-kv (fn [acc p s] (if (excluded p) acc (assoc acc p s)))
               {} providers)))

(defn verified-rescue?
  "A rescue requires a failure behind it. :fallback/served on its own is
   what try-chain used to stamp on a first-step success — the chain's
   head IS the primary, so every healthy turn looked rescued. The live
   ledger held 63 such phantoms against 1 real failover, and the
   pressure signal built on them read 31% instead of 0.5%. Requiring
   :fallback/tried keeps old entries honest without rewriting them."
  [event]
  (boolean (and (:fallback/served event) (seq (:fallback/tried event)))))

(defn digest
  "Per-provider aggregate over the usage ledger. :fallback-hits counts
   verified rescues only (see verified-rescue?). :unverified-fallback-tags
   reports the legacy tags that were excluded, so the discarded noise is
   visible rather than silently dropped."
  ([]
   (digest (usage/load-events)))
  ([events]
   (let [rescues (filter verified-rescue? events)
         providers (->> events
                        (group-by :provider)
                        (map (fn [[p xs]]
                               [p {:turns (count xs)
                                   :ok (count (filter :ok xs))
                                   :fail (count (remove :ok xs))
                                   :fallback-hits (count (filter #(= p (:fallback/served %)) rescues))}]))
                        (into {})
                        provider-signal)]
     {:events (count events)
      :unverified-fallback-tags (- (count (filter :fallback/served events))
                                   (count rescues))
      :providers providers})))

(defn digest-dir []
  (fs/path (config/home) "state" "rsi"))

(defn write-digest!
  "Write the digest as readable markdown; returns the path."
  ([] (write-digest! (digest)))
  ([{:keys [events providers unverified-fallback-tags]}]
  (let [unverified (or unverified-fallback-tags 0)
        path (fs/path (digest-dir) "digest.md")]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (str "# RSI digest — " (java.time.Instant/now) "\n\n"
               "events: " events "\n\n"
               "unverified fallback tags excluded: " unverified "\n\n"
               "| provider | turns | ok | fail | fallback-hits |\n"
               "|---|---|---|---|---|\n"
               (str/join "\n"
                         (map (fn [[p s]]
                                (format "| %s | %s | %s | %s | %s |"
                                        p (:turns s) (:ok s) (:fail s) (:fallback-hits s)))
                              (sort-by key providers)))
               "\n"))
    path)))

(defn- provider-failure-opportunity [[p s]]
  (when (and (>= (:turns s) min-provider-turns)
             (pos? (:fail s))
             (>= (/ (double (:fail s)) (:turns s)) flag-rate))
    {:kind :provider-failures
     :provider p
     :detail (format "%s failed %s/%s turns (%.0f%%)"
                     p (:fail s) (:turns s)
                     (* 100.0 (/ (double (:fail s)) (:turns s))))
     :suggestion (format "provider-failures: %s fails often — check its breaker threshold and its position in :provider/fallbacks" p)}))

(defn- fallback-pressure-opportunity
  "Grouped by provider AND model. The live chain is model-level — every
   step is :openai-compatible with a different model on the tail — so a
   provider-granular signal produced 'consider promoting
   :openai-compatible' while :openai-compatible was already the primary.
   A recommendation has to name something that can actually move."
  [events]
  (let [total (count events)
        hits (->> events
                  (filter verified-rescue?)
                  (map (juxt :fallback/served :fallback/model))
                  frequencies)]
    (->> hits
         (keep (fn [[[p model] n]]
                 (when (>= (/ (double n) total) flag-rate)
                   (let [who (if model (str p "/" model) (str p))]
                     {:kind :fallback-pressure
                      :provider p
                      :model model
                      :detail (format "%s served %s/%s turns as the fallback" who n total)
                      :suggestion (format "fallback-pressure: %s keeps rescuing turns — consider promoting it to primary or fixing the model it replaces" who)}))))
         (seq))))

(defn analyze
  "Derive opportunities from the usage ledger. Blocked below :min-events —
   small samples make liars of rates. Returns {:blocked msg} or
   {:opportunities [...]}."
  ([]
   (analyze {}))
  ([{:keys [min-events events]}]
   (let [min-events* (or min-events default-min-events)
         events (or events (usage/load-events))
         n (count events)]
     (if (< n min-events*)
       {:blocked (format "blocked: need %s more usage events (have %s of %s)"
                         (- min-events* n) n min-events*)}
       (let [opportunities (->> (map provider-failure-opportunity (:providers (digest events)))
                                (remove nil?)
                                (concat (fallback-pressure-opportunity events))
                                (vec))]
         {:opportunities opportunities})))))

(defn- improvements-path []
  (fs/path (config/home) "brain" "improvements.md"))

(defn- existing-lines [path]
  (if (fs/regular-file? path)
    (->> (str/split-lines (slurp (str path)))
         (map #(str/replace % #"^- " ""))
         set)
    #{}))

(defn propose!
  "Append deduplicated suggestions to brain/improvements.md. Suggestions
   already present (by exact line) are skipped — repetition is the signal
   that a proposal was reviewed and refused, not that it should nag."
  ([]
   (propose! {}))
  ([opts]
   (let [analysis (analyze opts)]
     (if (:blocked analysis)
       analysis
       (let [path (improvements-path)
             known (existing-lines path)
             suggestions (map :suggestion (:opportunities analysis))
             fresh (remove known suggestions)
             skipped (- (count suggestions) (count fresh))]
         (when (seq fresh)
           (fs/create-dirs (fs/parent path))
           (spit (str path)
                 (str (if (fs/regular-file? path) "\n" "")
                      (str/join "\n"
                                (map (fn [s]
                                       (str "## " (java.time.Instant/now) "\n- " s))
                                     fresh))
                      "\n")
                 :append (fs/regular-file? path)))
         {:added (count fresh)
          :skipped skipped})))))

(defn nearest-signals
  "The strongest below-threshold signals, so a quiet cycle can show its
   work instead of going mute. Sorted by the max rate per provider."
  ([] (nearest-signals (digest)))
  ([{:keys [events providers]}]
    (->> (for [[p s] providers
               :let [fail-rate (if (pos? (:turns s))
                                 (/ (double (:fail s)) (:turns s)) 0.0)
                     fallback-rate (if (pos? events)
                                     (/ (double (:fallback-hits s)) events) 0.0)]]
           {:provider p :fail-rate fail-rate :fallback-rate fallback-rate})
         (sort-by (fn [s] (max (:fail-rate s) (:fallback-rate s))) >))))

(defn cycle!
  "One full RSI pass — digest, analyze, propose — data in, ledger out.
   A quiet cycle is a result, not a failure: when nothing crosses a
   threshold the summary carries :nearest-signals instead of silence."
  [{:keys [min-events propose?] :or {propose? true}}]
  (let [events (usage/load-events)
        summary (digest events)
        digest-path (str (write-digest! summary))
        analysis (analyze {:min-events min-events :events events})]
    (if (:blocked analysis)
      {:digest-path digest-path :blocked (:blocked analysis)}
      (let [{:keys [added skipped]}
            (if propose?
              (propose! {:min-events min-events :events events})
              {:added 0 :skipped 0})
            ops (:opportunities analysis)]
        (cond-> {:digest-path digest-path
                 :events (:events summary)
                 :opportunities (count ops)
                 :added added :skipped skipped}
          (pos? (:unverified-fallback-tags summary))
          (assoc :unverified-fallback-tags (:unverified-fallback-tags summary))
          (zero? (count ops))
          (assoc :nearest-signals (nearest-signals summary)))))))
