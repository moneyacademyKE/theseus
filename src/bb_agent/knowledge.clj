(ns bb-agent.knowledge
  "Six RSI-scout recommendations as one small namespace:
   staleness marks, a wins ledger, protection tiers, pre-write
   snapshots, a curator dry-run, and a no-regression promotion gate.
   Plain files, data in / data out. Nothing here mutates anything
   except record-win!, stamp-review! and snapshot! — and the human
   promotion gate stays outside this ns by design."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(declare snapshot!)

;; ---------------------------------------------------------------- staleness

(def ^:private review-re
  "The one-line decay mark stolen from aminuoshi-378/dsh-self-improving."
  #"last-reviewed:\s*(\d{4}-\d{2}-\d{2})")

(defn- days-old
  [^LocalDate today ^LocalDate d]
  (.until d today ChronoUnit/DAYS))

(defn page-status
  "Classify one markdown page: :fresh (marked within max-age-days),
   :stale (marked but old) or :unmarked (no last-reviewed line).
   Opts: :today (LocalDate, default now), :max-age-days (default 30)."
  ([path] (page-status path {}))
  ([path {:keys [today max-age-days] :or {today (LocalDate/now) max-age-days 30}}]
   (let [m (re-find review-re (slurp (str path)))
         reviewed (some-> m second LocalDate/parse)
         status (cond
                  (nil? reviewed) :unmarked
                  (> (days-old today reviewed) max-age-days) :stale
                  :else :fresh)]
     {:file (str path) :status status :last-reviewed reviewed})))

(defn stale-pages
  "All problem pages in a dir: :stale and :unmarked ones, sorted by name.
   Fresh pages are absent — a quiet result, not silence."
  ([dir] (stale-pages dir {}))
  ([dir opts]
   (->> (fs/glob (str dir) "*.md")
        (map #(page-status % opts))
        (remove #(= :fresh (:status %)))
        (sort-by :file))))

(defn stamp-review!
  "Set (or replace) the page's last-reviewed line to today. Snapshots
   first. Returns the page path."
  ([path] (stamp-review! path (LocalDate/now)))
  ([path ^LocalDate today]
   (snapshot! path)
   (let [p (str path)
         lines (str/split-lines (slurp p))
         line (str "last-reviewed: " today)
         fresh (if (some #(re-find review-re %) lines)
                 (mapv #(if (re-find review-re %) line %) lines)
                 (into (vec lines) [" " line]))]
     (spit p (str/join "\n" fresh))
     path)))

;; ------------------------------------------------------------- wins ledger

(defn record-win!
  "Append one block to the wins ledger — but ONLY with evidence.
   A blank evidence string throws; a win without a receipt is a claim,
   and claims don't get recorded. Returns the ledger path."
  [wins-file {:keys [what evidence date] :or {date (str (LocalDate/now))}}]
  (when (str/blank? evidence)
    (throw (ex-info "record-win! refused: no evidence"
                    {:what what :evidence evidence})))
  (let [p (str wins-file)
        block (str "\n## " date " — " what "\n- evidence: " evidence "\n")]
    (spit p block :append true)
    p))

;; --------------------------------------------------------- protection tiers

(def protection-tiers
  "Edit-rights as data, not prose (stolen from ozumpe/omnibase, minus the
   actor org). Matched as path prefixes; first hit wins, so order matters
   only in that :forbidden entries must not be shadowed by :strict."
  {:forbidden ["brain/rules.clj" "config.edn"]
   :strict ["brain/"]
   :soft ["state/"]})

(defn tier-of
  "Classify a repo-relative path: :forbidden, :strict, :soft or :unclassified."
  [path]
  (let [p (str path)]
    (or (some #(when (str/starts-with? p %) :forbidden) (:forbidden protection-tiers))
        (some #(when (str/starts-with? p %) :strict) (:strict protection-tiers))
        (some #(when (str/starts-with? p %) :soft) (:soft protection-tiers))
        :unclassified)))

(defn check-write!
  "The pre-write lookup. :forbidden always throws; :strict throws without
   a non-blank justification; everything else passes. Returns a verdict map."
  [path {:keys [justification]}]
  (let [tier (tier-of path)]
    (case tier
      :forbidden (throw (ex-info "write forbidden by protection tier"
                                 {:path (str path) :tier tier}))
      :strict (if (str/blank? justification)
                (throw (ex-info "strict write requires a written justification"
                                {:path (str path) :tier tier}))
                {:path (str path) :tier tier :allowed true})
      {:path (str path) :tier tier :allowed true})))

;; --------------------------------------------------------------- snapshots

(defn snapshot!
  "Timestamped copy beside the original — every automated write is
   reversible without git archaeology (stolen from DrDustinEdwards/capsid).
   Returns the backup path, or nil when the file doesn't exist."
  [path]
  (let [p (str path)]
    (when (fs/exists? p)
      (let [ts (.format (java.time.format.DateTimeFormatter/ofPattern
                         "yyyyMMdd-HHmmss")
                        (java.time.LocalDateTime/now))
            bak (str p ".bak-" ts)]
        (fs/copy p bak {:replace-existing true})
        bak))))

;; ----------------------------------------------------------------- curator

(defn- page-title
  "First level-1 heading, else nil."
  [path]
  (->> (str/split-lines (slurp (str path)))
       (some #(when-let [m (re-find #"^#\s+(.+)$" %)] (str/trim (second m))))))

(defn curator-candidates
  "Dry-run consolidation report for a knowledge dir — flags, never archives
   (stolen from dkedar7/langstage-hermes, minus the auto-write loop):
   :problems = stale/unmarked pages, :duplicate-titles = pages sharing a
   level-1 heading. Returns data; what to archive stays a human decision."
  ([dir] (curator-candidates dir {}))
  ([dir opts]
   (let [problems (stale-pages dir opts)
         titles (->> (fs/glob (str dir) "*.md")
                     (keep (fn [f]
                             (when-let [t (page-title f)]
                               {:title t :file (str f)})))
                     (group-by :title)
                     (filter #(> (count (val %)) 1))
                     (map (fn [[t fs]] {:title t :files (mapv :file fs)})))]
     {:problems problems :duplicate-titles (vec titles)})))

;; ---------------------------------------------------------- promotion gate

(defn promote-gate
  "No-regression promotion (half-stolen from SourceShift/mini-ork): every
   check is a named zero-arg fn returning {:ok bool :detail str}. Verdict
   is :promote only when ALL pass — new check green AND previously-passing
   checks still green. This fn records and returns the measurement;
   it never promotes anything itself. The human gate stays outside."
  [checks]
  (let [results (into (sorted-map)
                      (map (fn [[name f]]
                             [name (try (f)
                                        (catch Throwable e
                                          {:ok false :detail (str "threw: " (ex-message e))}))])
                           checks))
        verdict (if (every? :ok (vals results)) :promote :block)]
    {:verdict verdict :results results}))
