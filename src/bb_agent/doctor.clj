(ns bb-agent.doctor
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.provider :as provider]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.usage :as usage]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private provider-required-keys
  {:openai-compatible [:base-url :api-key]
   :anthropic-compatible [:base-url :api-key]})

(defn check-provider-config [cfg]
  (let [provider (:provider cfg)
        providers (:providers cfg)
        provider-cfg (get providers provider)
        required (get provider-required-keys provider)]
    (cond
      (nil? required)
      {:status :ok :check :provider-config :message (str "Provider " provider " requires no external config")}

      (nil? provider-cfg)
      {:status :error :check :provider-config
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

(defn- check-config-parses [parse-error]
  (if parse-error
    {:status :error :check :config-parse
     :message (str "config.edn is not valid EDN: " parse-error
                   " — fix it or run: bb config restore-last-good")}
    {:status :ok :check :config-parse :message "config.edn parses"}))

(defn- check-home-dir []
  (let [home (config/home)]
    (if (fs/exists? home)
      {:status :ok :check :home-dir :message (str "Home directory exists: " home)}
      {:status :warning :check :home-dir
       :message (str "Home directory does not exist yet: " home)})))

(defn- check-home-writable []
  (let [home (config/home)]
    (if (and (fs/exists? home) (fs/writable? home))
      {:status :ok :check :home-writable :message (str "Home is writable: " home)}
      {:status :warning :check :home-writable
       :message (str "Home is missing or not writable: " home)})))

(defn- check-provider-reachable [cfg]
  (if (= :fake (:provider cfg))
    (try
      (if (= "status=ok" (:content (provider/complete :fake {:messages [{:role :user :content "status please"}]})))
        {:status :ok :check :provider-reachable :message "Fake provider responds status=ok"}
        {:status :error :check :provider-reachable :message "Fake provider gave an unexpected reply"})
      (catch Exception e
        {:status :error :check :provider-reachable :message (str "Fake provider probe failed: " (ex-message e))}))
    (let [base-url (get-in cfg [:providers (:provider cfg) :base-url])]
      (try {:status :ok :check :provider-reachable
            :message (str "Provider reachable: " base-url
                          " (status " (:status (http/get base-url {:throw false
                                                                   :connect-timeout 1500
                                                                   :request-timeout 3000})) ")")}
           (catch Exception _
             {:status :warning :check :provider-reachable
              :message (str "Provider base-url not reachable right now: " base-url)})))))

(defn- check-edn-file [check-k path]
  (let [path (str path)
        name (fs/file-name path)]
    (if-not (fs/regular-file? path)
      {:status :ok :check check-k :message (str "No " name " yet")}
      (try (edn/read-string (slurp path))
           {:status :ok :check check-k :message (str name " parses")}
           (catch Exception e
             {:status :error :check check-k
              :message (str name " is not valid EDN: " (ex-message e))})))))

(defn run-checks []
  (let [result (try {:cfg (config/load-config)}
                    (catch Exception e {:error (ex-message e)}))]
    (if-let [parse-error (:error result)]
      [(check-config-file)
       (check-config-parses parse-error)]
      (let [cfg (:cfg result)]
        [(check-config-file)
         (check-config-parses nil)
         (check-home-dir)
         (check-home-writable)
         (check-provider-reachable cfg)
         (check-provider-config cfg)
         (check-session-model-drift cfg)
         (check-edn-file :memory-store (memory/memory-file))
         (check-edn-file :semantic-store (semantic-memory/store-file))
         (check-edn-file :usage-store (usage/usage-file))]))))

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
