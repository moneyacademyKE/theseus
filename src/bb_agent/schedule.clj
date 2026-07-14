(ns bb-agent.schedule
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [clojure.edn :as edn]))

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

(defn add-schedule! [schedule-id prompt]
  (let [cfg (config/load-config)
        entry {:schedule/id schedule-id
               :schedule/prompt prompt
               :cwd (:cwd cfg)
               :provider (:provider cfg)
               :model (:model cfg)}
        existing (->> (load-schedules)
                      (remove #(= schedule-id (:schedule/id %)))
                      vec)
        entries (conj existing entry)]
    (save-schedules! entries)
    entry))

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

(defn run-schedule! [cfg schedule-id]
  (if-let [schedule (find-schedule schedule-id)]
    (let [turn (core/run-turn! (merge cfg
                                      (select-keys schedule [:cwd :provider :model])
                                      {:session/id schedule-id})
                               (:schedule/prompt schedule))
          log-entry {:schedule/id schedule-id
                     :status :ok
                     :assistant/final (:assistant/final turn)}]
      (append-run-log! log-entry)
      turn)
    (throw (ex-info (str "Unknown schedule: " schedule-id)
                    {:schedule/id schedule-id}))))

(defn run-all-schedules! [cfg]
  (mapv #(run-schedule! cfg (:schedule/id %)) (load-schedules)))
