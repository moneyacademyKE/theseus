(ns bb-agent.config
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(defn home []
  (or (System/getenv "OPENCRABS_HOME")
      (str (fs/path (System/getProperty "user.home") ".opencrabs-bb"))))

(defn config-file []
  (fs/path (home) "config.edn"))

(def default-config
  {:provider :fake
   :model "fake-deterministic"
   :session/id "default"})

(defn load-config []
  (let [path (config-file)]
    (if (fs/regular-file? path)
      (merge default-config (edn/read-string (slurp (str path))))
      default-config)))
