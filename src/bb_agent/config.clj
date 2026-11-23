(ns bb-agent.config
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [clojure.string :as str]))

(defn home
  "Theseus data home: $THESEUS_HOME or ~/theseus.
   Renamed 2026-09-27 (bk-6121, daemon decoupling): the OPENCRABS_HOME name
   was pure inheritance from the tree Theseus grew out of, and the old
   default pointed at that tree's graveyard. The default now names the
   real home, so the env var is an override, not a lifeline."
  []
  (or (System/getenv "THESEUS_HOME")
      (str (fs/path (System/getProperty "user.home") "theseus"))))

(defn config-file []
  (fs/path (home) "config.edn"))

(defn last-good-file []
  (fs/path (home) "config.last-good.edn"))

(def default-config
  {:provider :fake
   :model "fake-deterministic"
   :session/id "default"})

(defn validate-telegram
  "Pure: problems as data for the :telegram section, empty when usable.
   Consolidates the per-site :token checks (H5, Hickey audit 2026-09-06)."
  [tg]
  (cond-> []
    (not (map? tg))
    (conj {:key :telegram :problem "missing :telegram map"})

    (and (map? tg) (str/blank? (:token tg)))
    (conj {:key :telegram :problem ":token missing or blank"})))

(defn- read-config-map [path]
  (when (fs/regular-file? path)
    (let [parsed (aio/read-edn-safe path nil)]
      (when (map? parsed) parsed))))

(defn load-config []
  (let [cfg (or (read-config-map (config-file))
                (read-config-map (last-good-file)))]
    (if cfg
      (merge default-config cfg)
      default-config)))

(defn write-config!
  "Atomically install `candidate` as config.edn, keeping the previous
  config as config.last-good.edn. Validation is the caller's job —
  this only guarantees the swap is atomic, reversible, and born 0600."
  [candidate]
  (when-not (map? candidate)
    (throw (ex-info "Config candidate must be a map" {:candidate candidate})))
  (let [path (config-file)]
    (fs/create-dirs (home))
    (when (fs/regular-file? path)
      (aio/write-private-file! (last-good-file) (slurp (str path))))
    (aio/write-private-file! path (pr-str candidate))
    candidate))

(defn restore-last-good!
  "Atomically copy config.last-good.edn back over config.edn (born 0600) and
  return the recovered config. Throws when no last-good snapshot exists."
  []
  (let [last-good (last-good-file)
        path (config-file)]
    (when-not (fs/regular-file? last-good)
      (throw (ex-info "No last-good config to restore" {:path (str last-good)})))
    (aio/write-private-file! path (slurp (str last-good)))
    (load-config)))
