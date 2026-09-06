(ns bb-agent.config
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn home []
  (or (System/getenv "OPENCRABS_HOME")
      (str (fs/path (System/getProperty "user.home") ".opencrabs-bb"))))

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

(defn load-config []
  (let [path (config-file)]
    (if (fs/regular-file? path)
      (merge default-config (edn/read-string (slurp (str path))))
      default-config)))

(defn- harden!
  "Owner-only permissions. config.edn and config.last-good.edn both
  carry secrets (token, halt HMAC) — files from spit/copy land
  umask-wide (0644), a live exposure (bk-2105)."
  [p]
  (fs/set-posix-file-permissions p "rw-------"))

(defn write-config!
  "Atomically install `candidate` as config.edn, keeping the previous
  config as config.last-good.edn. Validation is the caller's job —
  this only guarantees the swap is atomic, reversible, and 0600."
  [candidate]
  (when-not (map? candidate)
    (throw (ex-info "Config candidate must be a map" {:candidate candidate})))
  (let [path (config-file)]
    (fs/create-dirs (home))
    (when (fs/regular-file? path)
      (fs/copy path (last-good-file) {:replace-existing true})
      (harden! (last-good-file)))
    (let [tmp (fs/path (home) (str ".config.edn." (System/nanoTime)))]
      (spit (str tmp) (pr-str candidate))
      (harden! tmp)
      (fs/move tmp path {:replace-existing true}))
    candidate))

(defn restore-last-good!
  "Copy config.last-good.edn back over config.edn and return the
  recovered config. Throws when no last-good snapshot exists."
  []
  (let [last-good (last-good-file)
        path (config-file)]
    (when-not (fs/regular-file? last-good)
      (throw (ex-info "No last-good config to restore" {:path (str last-good)})))
    (fs/copy last-good path {:replace-existing true})
    (harden! path)
    (load-config)))
