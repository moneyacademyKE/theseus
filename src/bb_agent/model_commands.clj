(ns bb-agent.model-commands
  "Telegram model configuration as data.

   The command lane owns parsing and validation; config/write-config! owns the
   atomic durable write. No LLM is involved in changing provider routing."
  (:require [babashka.http-client :as http]
            [bb-agent.config :as config]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- model-name?
  [model]
  (and (string? model)
       (some? (re-matches #"[^\s]+" model))))

(defn- provider-key
  [provider]
  (when (and (string? provider) (not (str/blank? provider)))
    (keyword provider)))

(defn- provider-configured?
  [cfg provider]
  (or (= :fake provider)
      (contains? (:providers cfg) provider)))

(defn- validation-error
  [message]
  {:ok? false :reply (str "🚫 " message)})

(defn- advertised-models
  [cfg provider]
  (let [pcfg (get-in cfg [:providers provider])
        base-url (:base-url pcfg)
        api-key (:api-key pcfg)]
    (when (and base-url (not= :fake provider))
      (try
        (let [headers (cond-> {}
                        (and (string? api-key) (not (str/blank? api-key)))
                        (assoc "authorization" (str "Bearer " api-key)))
              response (http/get (str (str/replace base-url #"/+$" "") "/models")
                                 (cond-> {:throw false :connect-timeout 1500 :timeout 3000}
                                   (seq headers) (assoc :headers headers)))]
          (when (= 200 (:status response))
            (let [body (json/parse-string (:body response) true)]
              (set (keep :id (:data body))))))
        (catch Exception _ nil)))))

(defn- model-advertised?
  [cfg provider model]
  (or (= :fake provider)
      (let [models (advertised-models cfg provider)]
        (and (set? models) (contains? models model)))))

(defn parse
  "Parse `/models` arguments into a bounded operation map.
   The first argument is one of status, default, fallback, or clear-fallback."
  [text]
  (let [parts (-> text str/trim (str/split #"\s+"))
        command (first parts)
        operation (second parts)
        args (vec (drop 2 parts))]
    (if (= "/models" command)
      (case operation
        nil {:op :status}
        "default" {:op :default :args args}
        "fallback" {:op :fallback :args args}
        "clear-fallback" {:op :clear-fallback}
        {:op :help})
      {:op :help})))

(defn- validate-provider-model
  [cfg args label]
  (let [[provider-name model & extra] args
        provider (provider-key provider-name)]
    (cond
      (or (nil? provider) (nil? model) (seq extra))
      (validation-error (str "Usage: /models " label
                            " <provider> <provider/model>"))

      (not (provider-configured? cfg provider))
      (validation-error (str "Provider is not configured: " provider-name
                            ". Add it under :providers first."))

      (not (model-name? model))
      (validation-error "Model must use provider/name form, for example infered/auto.")

      (not (model-advertised? cfg provider model))
      (validation-error (str "Provider does not advertise model: " model
                            ". Check the endpoint, then retry."))

      :else
      {:ok? true :provider provider :model model})))

(defn- provider-label
  [cfg provider]
  (str (name provider) " (configured="
       (provider-configured? cfg provider) ")"))

(defn status
  "Render the current routing and configured provider names without secrets."
  [cfg]
  (let [fallbacks (:provider/fallbacks cfg)]
    (str "🤖 Model routing\n"
         "Default: " (:provider cfg) " / " (:model cfg) "\n"
         "Fallbacks: "
         (if (seq fallbacks)
           (str/join ", " (map #(str (:provider %) " / " (:model %)) fallbacks))
           "none")
         "\nProviders: "
         (if (seq (:providers cfg))
           (str/join ", " (map #(provider-label cfg (key %)) (:providers cfg)))
           "none")
         "\n\nCommands:\n"
         "/models default <provider> <provider/model>\n"
         "/models fallback <provider> <provider/model>\n"
         "/models clear-fallback")))

(defn apply!
  "Apply one parsed operation. Returns a user-facing reply and writes only
   the routing keys that operation owns."
  [cfg {:keys [op args]}]
  (case op
    :status {:ok? true :reply (status cfg)}
    :help {:ok? false :reply (str "Usage: /models [default|fallback|clear-fallback]\n\n"
                                  "Use /models to inspect current routing.")}
    :clear-fallback
    (do
      (config/write-config! (dissoc cfg :provider/fallbacks))
      {:ok? true :reply "✅ Fallback cleared. The next turn uses the default provider."})
    :default
    (let [result (validate-provider-model cfg args "default")]
      (if-not (:ok? result)
        result
        (let [{:keys [provider model]} result]
          (config/write-config! (assoc cfg :provider provider :model model))
          {:ok? true
           :reply (str "✅ Default set: " provider " / " model)})))
    :fallback
    (let [result (validate-provider-model cfg args "fallback")]
      (if-not (:ok? result)
        result
        (let [{:keys [provider model]} result
              fallback {:provider (name provider) :model model}]
          (config/write-config! (assoc cfg :provider/fallbacks [fallback]))
          {:ok? true
           :reply (str "✅ Fallback set: " provider " / " model)})))
    (validation-error "Unknown /models operation.")))
