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
(def ^:private flag-rate 0.3)

(defn digest
  "Per-provider aggregate over the usage ledger. :fallback-hits counts
   events where this provider was the safety net (:fallback/served)."
  []
  (let [events (usage/load-events)
        providers (->> events
                       (group-by :provider)
                       (map (fn [[p xs]]
                              [p {:turns (count xs)
                                  :ok (count (filter :ok xs))
                                  :fail (count (remove :ok xs))
                                  :fallback-hits (count (filter #(= p (:fallback/served %)) events))}]))
                       (into {}))]
    {:events (count events)
     :providers providers}))

(defn digest-dir []
  (fs/path (config/home) "state" "rsi"))

(defn write-digest!
  "Write the digest as readable markdown; returns the path."
  []
  (let [{:keys [events providers]} (digest)
        path (fs/path (digest-dir) "digest.md")]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (str "# RSI digest — " (java.time.Instant/now) "\n\n"
               "events: " events "\n\n"
               "| provider | turns | ok | fail | fallback-hits |\n"
               "|---|---|---|---|---|\n"
               (str/join "\n"
                         (map (fn [[p s]]
                                (format "| %s | %s | %s | %s | %s |"
                                        p (:turns s) (:ok s) (:fail s) (:fallback-hits s)))
                              (sort-by key providers)))
               "\n"))
    path))

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

(defn- fallback-pressure-opportunity [events]
  (let [total (count events)
        hits (->> events (keep :fallback/served) frequencies)]
    (->> hits
         (keep (fn [[p n]]
                 (when (>= (/ (double n) total) flag-rate)
                   {:kind :fallback-pressure
                    :provider p
                    :detail (format "%s served %s/%s turns as the fallback" p n total)
                    :suggestion (format "fallback-pressure: %s keeps rescuing turns — consider promoting it or fixing the primary" p)})))
         (seq))))

(defn analyze
  "Derive opportunities from the usage ledger. Blocked below :min-events —
   small samples make liars of rates. Returns {:blocked msg} or
   {:opportunities [...]}."
  ([]
   (analyze {}))
  ([{:keys [min-events]}]
   (let [min-events* (or min-events default-min-events)
         events (usage/load-events)
         n (count events)]
     (if (< n min-events*)
       {:blocked (format "blocked: need %s more usage events (have %s of %s)"
                         (- min-events* n) n min-events*)}
       (let [opportunities (->> (map provider-failure-opportunity (:providers (digest)))
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
