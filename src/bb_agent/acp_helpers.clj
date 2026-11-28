(ns bb-agent.acp-helpers
  "Pure payload builders and data adapters for the Theseus ACP v1 server.

  Kept separate from `bb-agent.acp` so protocol shaping, catalog construction,
  prompt/image decoding, plan mapping, and telemetry formatting stay pure,
  cohesive, and easy to reason about."
  (:require [babashka.fs :as fs]
            [bb-agent.compression :as compression]
            [bb-agent.config :as config]
            [bb-agent.model :as model]
            [bb-agent.skill :as skill]
            [clojure.string :as str])
  (:import [java.util Base64 UUID]))

(def available-modes
  [{:id "supervised" :name "Supervised"}
   {:id "auto-accept-edits" :name "Auto-accept edits"}
   {:id "auto" :name "Auto"}
   {:id "full-access" :name "Full access"}
   {:id "plan" :name "Plan (read-only)"}])

(def ^:private base-commands
  [{:name "new" :description "Start a new session"}
   {:name "sessions" :description "List persisted sessions"}
   {:name "models" :description "Switch provider/model"}
   {:name "compact" :description "Compact session history into a checkpoint"}
   {:name "usage" :description "Session usage stats"}
   {:name "doctor" :description "Run health diagnostics"}
   {:name "stop" :description "Cancel the running turn"}])

(defn available-commands-for
  "Combine built-in ACP slash commands with locally discovered Theseus skills."
  []
  (let [skills-dir (fs/path (config/home) "skills")
        discovered (try
                     (when (fs/directory? skills-dir)
                       (skill/discover-skills skills-dir))
                     (catch Exception _ []))
        seen (set (map :name base-commands))
        skill-cmds (->> discovered
                        (remove #(contains? seen (:name %)))
                        (mapv (fn [{:keys [name description]}]
                                {:name (str name)
                                 :description (or (not-empty (str description))
                                                  (str "Run skill " name))})))]
    (into base-commands skill-cmds)))

(defn mode-for-cfg [cfg]
  (case (:approval/mode cfg)
    :auto-all (if (false? (:security/taint-gate? cfg)) "full-access" "auto")
    :auto-edits "auto-accept-edits"
    :plan "plan"
    "supervised"))

(defn cfg-overrides-for-mode [mode-id]
  (case mode-id
    "full-access" {:approval/mode :auto-all :security/taint-gate? false}
    "auto" {:approval/mode :auto-all :security/taint-gate? true}
    "auto-accept-edits" {:approval/mode :auto-edits :security/taint-gate? true}
    "plan" {:approval/mode :plan :security/taint-gate? true}
    {:approval/mode :ask :security/taint-gate? true}))

(defn model-id-str [provider model-name]
  (str (name (or provider :default)) "/" (or model-name "default")))

(defn parse-model-id [model-id]
  (let [s (str/trim (str model-id))
        idx (str/index-of s "/")]
    (if (and idx (pos? idx) (< (inc idx) (count s)))
      [(keyword (subs s 0 idx)) (subs s (inc idx))]
      [nil s])))

(defn- catalog-entries [cfg]
  (let [primary [(or (:provider cfg) :infered-local) (or (:model cfg) "default")]
        from-providers (for [[p pcfg] (:providers cfg)
                             :let [m (:model pcfg)]
                             :when (and p (string? m) (not (str/blank? m)))]
                         [p m])
        from-fallbacks (for [{:keys [provider model]} (:provider/fallbacks cfg)
                             :when (and provider (string? model) (not (str/blank? model)))]
                         [(keyword provider) model])
        pairs (distinct (concat [primary] from-providers from-fallbacks))]
    (vec (for [[p m] pairs]
           {:provider p
            :model m
            :modelId (model-id-str p m)
            :name (str (name p) " / " m)}))))

(defn models-payload [cfg session-id]
  (let [eff (if session-id
              (model/effective-config (assoc cfg :session/id session-id))
              cfg)
        current-id (model-id-str (:provider eff) (:model eff))
        entries (catalog-entries eff)
        entries* (if (some #(= current-id (:modelId %)) entries)
                   entries
                   (into [{:provider (:provider eff)
                           :model (:model eff)
                           :modelId current-id
                           :name (str (name (:provider eff)) " / " (:model eff))}]
                         entries))]
    {:availableModels (mapv #(select-keys % [:modelId :name]) entries*)
     :currentModelId current-id}))

(defn config-options-payload [cfg session-id]
  (let [{:keys [availableModels currentModelId]} (models-payload cfg session-id)
        grouped (group-by #(first (str/split (:modelId %) #"/" 2)) availableModels)]
    [{:id "model"
      :name "Model"
      :description "Provider and model used for this session"
      :category "model"
      :type "select"
      :currentValue currentModelId
      :options (vec (for [[grp items] grouped]
                      {:group grp
                       :name grp
                       :options (mapv (fn [{:keys [modelId]}]
                                        (let [[_ m] (parse-model-id modelId)]
                                          {:value modelId :name m}))
                                      items)}))}]))

(defn modes-payload [mode-id]
  {:availableModes available-modes
   :currentModeId (or mode-id "full-access")})

(defn tool-kind [tool-name]
  (let [n (str/lower-case (str tool-name))]
    (cond
      (re-find #"read|list|glob|find|search|grep|stat" n) "read"
      (re-find #"write|edit|patch|replace|create" n) "edit"
      (re-find #"delete|remove|rm" n) "delete"
      (re-find #"shell|bash|exec|run|repl|cmd" n) "execute"
      :else "other")))

(defn tool-locations [args]
  (when (map? args)
    (let [p (or (:path args) (:file args) (:file_path args))]
      (when (and (string? p) (not (str/blank? p)))
        [{:path p}]))))

(defn extract-prompt-text [prompt]
  (cond
    (string? prompt) prompt
    (sequential? prompt)
    (->> prompt
         (keep (fn [blk]
                 (cond
                   (string? blk) blk
                   (map? blk)
                   (when-not (= "image" (:type blk))
                     (or (:text blk)
                         (get-in blk [:resource :text])
                         (when-let [uri (get-in blk [:resource :uri])]
                           (str "[resource: " uri "]")))))))
         (str/join "\n"))
    (map? prompt) (or (:text prompt) (pr-str prompt))
    :else (str prompt)))

(defn- mime->ext [mime]
  (case (str/lower-case (str (or mime "")))
    "image/jpeg" "jpg"
    "image/gif" "gif"
    "image/webp" "webp"
    "png"))

(defn decode-image-blocks!
  "Decode ACP base64 image blocks into files under state/acp-attachments and
  return :user/images descriptors compatible with bb-agent.vision."
  [prompt]
  (if-not (sequential? prompt)
    []
    (let [img-blocks (filter #(and (map? %)
                                   (= "image" (:type %))
                                   (string? (:data %))
                                   (not (str/blank? (:data %))))
                             prompt)]
      (if (empty? img-blocks)
        []
        (let [dir (fs/path (config/home) "state" "acp-attachments")]
          (fs/create-dirs dir)
          (vec (keep (fn [blk]
                       (try
                         (let [mime (or (:mimeType blk) (:mime_type blk) "image/png")
                               ext (mime->ext mime)
                               target (fs/path dir (str "acp-img-" (UUID/randomUUID) "." ext))
                               raw (.decode (Base64/getDecoder) ^String (:data blk))]
                           (java.nio.file.Files/write target raw (make-array java.nio.file.OpenOption 0))
                           {:path (str target) :mime-type mime :kind :photo})
                         (catch Exception _ nil)))
                     img-blocks)))))))

(defn derive-title [prompt-text]
  (let [cleaned (-> (str (or prompt-text ""))
                    (str/replace #"\s+" " ")
                    str/trim)]
    (when-not (str/blank? cleaned)
      (if (> (count cleaned) 72)
        (str (subs cleaned 0 69) "...")
        cleaned))))

(defn plan->acp-entries [plan-map]
  (when (and (map? plan-map) (= :active (:status plan-map)) (seq (:steps plan-map)))
    (mapv (fn [{:keys [title status]}]
            {:content (str title)
             :priority "medium"
             :status (case status
                       (:done :completed) "completed"
                       (:in-progress :in_progress) "in_progress"
                       "pending")})
          (:steps plan-map))))

(defn usage->acp-update [usage model-name]
  (when (map? usage)
    (let [in-t (long (or (:input-tokens usage) (:prompt-tokens usage) 0))
          out-t (long (or (:output-tokens usage) (:completion-tokens usage) 0))
          used (long (or (:total-tokens usage) (+ in-t out-t)))
          size (long (compression/model-context-tokens model-name))
          cost (:cost-usd usage)]
      (when (pos? used)
        (cond-> {:sessionUpdate "usage_update"
                 :used used
                 :size size}
          (number? cost) (assoc :cost {:amount (double cost) :currency "USD"}))))))
