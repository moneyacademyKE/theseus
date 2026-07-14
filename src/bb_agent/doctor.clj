(ns bb-agent.doctor
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.model :as model]
            [clojure.string :as str]))

(def ^:private provider-required-keys
  {:openai-compatible [:base-url :api-key]
   :anthropic-compatible [:base-url :api-key]})

(defn- check-provider-config [cfg]
  (let [provider (:provider cfg)
        providers (:providers cfg)
        provider-cfg (get providers provider)
        required (get provider-required-keys provider)]
    (cond
      (nil? required)
      {:status :ok :check :provider-config :message (str "Provider " provider " requires no external config")}

      (nil? provider-cfg)
      {:status :error
       :check :provider-config
       :message (str "Provider " provider " is configured but has no :providers entry")
       :provider provider}

      :else
      (let [missing (remove #(get provider-cfg %) required)]
        (if (seq missing)
          {:status :error
           :check :provider-config
           :message (str "Provider " provider " missing config keys: " (str/join ", " (map name missing)))
           :provider provider
           :missing missing}
          {:status :ok :check :provider-config :message (str "Provider " provider " config complete")})))))

(defn- check-session-model-drift [cfg]
  (let [session-id (:session/id cfg)
        session-model (model/load-session-model session-id)]
    (if session-model
      (let [cfg-provider (:provider cfg)
            cfg-model (:model cfg)
            session-provider (:provider session-model)
            session-model-id (:model session-model)]
        (if (or (not= cfg-provider session-provider)
                (not= cfg-model session-model-id))
          {:status :warning
           :check :session-model-drift
           :message (str "Session " session-id " uses " session-provider "/" session-model-id
                         " but config defaults to " cfg-provider "/" cfg-model)
           :session/id session-id
           :session/provider session-provider
           :session/model session-model-id}
          {:status :ok :check :session-model-drift :message (str "Session " session-id " model matches config")}))
      {:status :ok :check :session-model-drift :message (str "Session " session-id " has no model override")})))

(defn- check-config-file []
  (let [path (config/config-file)]
    (if (fs/regular-file? path)
      {:status :ok :check :config-file :message (str "Config file exists: " path)}
      {:status :warning
       :check :config-file
       :message (str "No config file found at " path ", using defaults")})))

(defn- check-home-dir []
  (let [home (config/home)]
    (if (fs/exists? home)
      {:status :ok :check :home-dir :message (str "Home directory exists: " home)}
      {:status :warning
       :check :home-dir
       :message (str "Home directory does not exist yet: " home)})))

(defn run-checks []
  (let [cfg (config/load-config)]
    [(check-config-file)
     (check-home-dir)
     (check-provider-config cfg)
     (check-session-model-drift cfg)]))

(defn format-check [check]
  (let [status (:status check)
        icon (case status
               :ok "[OK]"
               :warning "[WARN]"
               :error "[ERROR]")]
    (str icon " " (:check check) ": " (:message check))))

(defn has-errors? [checks]
  (some #(= :error (:status %)) checks))

(defn has-warnings? [checks]
  (some #(= :warning (:status %)) checks))
