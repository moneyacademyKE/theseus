(ns bb-agent.session
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn safe-session-id [session-id]
  (-> (or session-id "default")
      (str/replace #"[^A-Za-z0-9._-]" "_")))

(defn session-file [session-id]
  (fs/path (config/home) "state" "sessions"
           (str (safe-session-id session-id) ".edn")))

(defn metadata-file [session-id]
  (fs/path (config/home) "state" "session-metadata"
           (str (safe-session-id session-id) ".edn")))

(defn metadata-dir []
  (fs/path (config/home) "state" "session-metadata"))

(defn load-turns [session-id]
  (let [path (session-file session-id)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      [])))

(defn append-turn! [session-id turn]
  (let [path (session-file session-id)
        turns (conj (load-turns session-id) turn)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str turns))
    turns))

(defn load-metadata [session-id]
  (let [path (metadata-file session-id)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn save-metadata! [session-id metadata]
  (let [path (metadata-file session-id)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str metadata))
    metadata))

(defn touch-metadata! [session-id cfg]
  (let [now (str (java.time.Instant/now))
        existing (load-metadata session-id)
        metadata (merge {:session/id session-id
                         :created/at now}
                        existing
                        {:cwd (or (:cwd existing) (:cwd cfg) (config/home))
                         :provider (:provider cfg)
                         :model (:model cfg)
                         :updated/at now})]
    (save-metadata! session-id metadata)))

(defn list-metadata []
  (let [dir (metadata-dir)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/regular-file?)
           (map #(edn/read-string (slurp (str %))))
           (sort-by (comp str :updated/at))
           reverse
           vec)
      [])))

(defn set-cwd! [session-id cwd]
  (let [path (fs/canonicalize cwd)]
    (when-not (fs/directory? path)
      (throw (ex-info (str "Not a directory: " cwd) {:cwd cwd})))
    (save-metadata! session-id
                    (merge {:session/id session-id
                            :created/at (str (java.time.Instant/now))}
                           (load-metadata session-id)
                           {:cwd (str path)
                            :updated/at (str (java.time.Instant/now))}))))
