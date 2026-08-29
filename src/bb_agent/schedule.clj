(ns bb-agent.schedule
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.cron :as cron]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn schedules-file []
  (fs/path (config/home) "state" "schedules.edn"))

(defn runs-file []
  (fs/path (config/home) "state" "schedule-runs.edn"))

(defn load-schedules []
  (let [path (schedules-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      [])))

(defn save-schedules! [entries]
  (let [path (schedules-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str (vec entries)))
    (vec entries)))

(defn add-schedule!
  ([schedule-id prompt] (add-schedule! schedule-id prompt nil))
  ([schedule-id prompt cron-expr]
   (let [cfg (config/load-config)
         entry (cond-> {:schedule/id schedule-id
                        :schedule/prompt prompt
                        :cwd (:cwd cfg)
                        :provider (:provider cfg)
                        :model (:model cfg)}
                 (seq cron-expr) (assoc :schedule/cron cron-expr))
         existing (->> (load-schedules)
                       (remove #(= schedule-id (:schedule/id %)))
                       vec)
         entries (conj existing entry)]
     (save-schedules! entries)
     entry)))

(defn remove-schedule! [schedule-id]
  (let [remaining (->> (load-schedules)
                       (remove #(= schedule-id (:schedule/id %)))
                       vec)]
    (save-schedules! remaining)
    remaining))

(defn find-schedule [schedule-id]
  (some #(when (= schedule-id (:schedule/id %)) %) (load-schedules)))

(defn- append-run-log! [entry]
  (let [path (runs-file)
        entries (if (fs/regular-file? path)
                  (edn/read-string (slurp (str path)))
                  [])
        updated (conj (vec entries) entry)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str updated))
    updated))

(defn last-run-times
  "schedule/id -> Instant of its most recent run. Entries are appended
  chronologically, so later entries win the reduce. Runs from before
  :at stamping existed simply carry no time and are ignored."
  []
  (let [path (runs-file)]
    (if (fs/regular-file? path)
      (->> (edn/read-string (slurp (str path)))
           (keep (fn [{:keys [schedule/id] :as e}]
                   (when (and id (:at e)) [id (:at e)])))
           (reduce (fn [acc [id at]] (assoc acc id at)) {}))
      {})))

(defn- minute-aligned [zdt]
  (.truncatedTo zdt java.time.temporal.ChronoUnit/MINUTES))

(defn- ->instant
  "Last-run times arrive as Instants (pure callers) or the :at
  strings last-run-times reads back from the log. One seam, both."
  [t]
  (cond
    (instance? java.time.Instant t) t
    (string? t) (java.time.Instant/parse t)
    :else (throw (ex-info "Not a timestamp" {:value t :type (type t)}))))

(defn cron-due?
  "Pure gating: cron `expr` is due when it matched at any minute in
  (last-run, now] — catch-up is just the window closing over missed
  slots. Never-run schedules are due on the first observed matching
  minute. `now` is a ZonedDateTime, `last-run` an Instant or nil;
  both arrive as arguments so DST stays the matcher's problem and
  gating stays a function."
  [expr last-run now]
  (if last-run
    (let [zone (.getZone now)
          from (.plusMinutes (minute-aligned (.atZone last-run zone)) 1)
          to (.plusMinutes (minute-aligned now) 1)]
      (boolean (seq (cron/matches-in-window expr from to))))
    (cron/match-cron expr now)))

(defn due-schedules
  "Schedules to fire at `now`: those without :schedule/cron always
  (unchanged pre-cron behavior), those with one only when cron-due?
  against their last run time."
  [schedules last-runs now]
  (filter (fn [{:keys [schedule/cron] :as s}]
            (or (str/blank? cron)
                (cron-due? cron
                           (some-> (get last-runs (:schedule/id s))
                                   java.time.Instant/parse)
                           now)))
          schedules))

(defn run-schedule! [cfg schedule-id]
  (if-let [schedule (find-schedule schedule-id)]
    (let [turn (core/run-turn! (merge cfg
                                      (select-keys schedule [:cwd :provider :model])
                                      {:session/id schedule-id})
                               (:schedule/prompt schedule))
          log-entry {:schedule/id schedule-id
                     :status :ok
                     :at (str (java.time.Instant/now))
                     :assistant/final (:assistant/final turn)}]
      (append-run-log! log-entry)
      turn)
    (throw (ex-info (str "Unknown schedule: " schedule-id)
                    {:schedule/id schedule-id}))))

(defn run-all-schedules!
  ([cfg] (run-all-schedules! cfg (java.time.ZonedDateTime/now)))
  ([cfg now]
   (->> (due-schedules (load-schedules) (last-run-times) now)
        (mapv #(run-schedule! cfg (:schedule/id %))))))
