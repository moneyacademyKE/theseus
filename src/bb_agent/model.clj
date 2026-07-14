(ns bb-agent.model
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]))

(defn model-file [session-id]
  (fs/path (config/home) "state" "session-models"
           (str (session/safe-session-id session-id) ".edn")))

(defn load-session-model [session-id]
  (let [path (model-file session-id)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn save-session-model! [session-id provider model]
  (let [path (model-file session-id)
        selection {:session/id session-id
                   :provider (keyword provider)
                   :model model}]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str selection))
    selection))

(defn effective-config [cfg]
  (let [session-id (:session/id cfg)]
    (merge cfg (load-session-model session-id))))
