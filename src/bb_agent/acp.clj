(ns bb-agent.acp
  "Agent Client Protocol (ACP v1) stdio JSON-RPC 2.0 server for Theseus.

  Exposes Theseus as a first-class local ACP provider for T3 Code, Zed,
  JetBrains, MonoCode, and any ACP-compatible client via `theseus acp`.
  Pure handler dispatch + thin stdio transport with strict stdout purity."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.model :as model]
            [bb-agent.rich :as rich]
            [bb-agent.session :as session]
            [bb-agent.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter Writer]
           [java.util UUID]))

(def ^:private available-modes
  [{:id "supervised" :name "Supervised"}
   {:id "auto-accept-edits" :name "Auto-accept edits"}
   {:id "auto" :name "Auto"}
   {:id "full-access" :name "Full access"}
   {:id "plan" :name "Plan (read-only)"}])

(def ^:private available-commands
  [{:name "new" :description "Start a new session"}
   {:name "sessions" :description "List persisted sessions"}
   {:name "models" :description "Switch provider/model"}
   {:name "usage" :description "Session usage stats"}
   {:name "doctor" :description "Run health diagnostics"}
   {:name "stop" :description "Cancel the running turn"}])

(defn- mode-for-cfg [cfg]
  (case (:approval/mode cfg)
    :auto-all (if (false? (:security/taint-gate? cfg)) "full-access" "auto")
    :auto-edits "auto-accept-edits"
    :plan "plan"
    "supervised"))

(defn- cfg-overrides-for-mode [mode-id]
  (case mode-id
    "full-access" {:approval/mode :auto-all :security/taint-gate? false}
    "auto" {:approval/mode :auto-all :security/taint-gate? true}
    "auto-accept-edits" {:approval/mode :auto-edits :security/taint-gate? true}
    "plan" {:approval/mode :plan :security/taint-gate? true}
    {:approval/mode :ask :security/taint-gate? true}))

(defn- model-id-str [provider model-name]
  (str (name (or provider :default)) "/" (or model-name "default")))

(defn- parse-model-id [model-id]
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

(defn- models-payload [cfg session-id]
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

(defn- config-options-payload [cfg session-id]
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

(defn- modes-payload [mode-id]
  {:availableModes available-modes
   :currentModeId (or mode-id "full-access")})

(defn- ok-resp [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- err-resp [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- session-update-notif [session-id update-map]
  {:jsonrpc "2.0"
   :method "session/update"
   :params {:sessionId session-id
            :update update-map}})

(defn- tool-kind [tool-name]
  (let [n (str/lower-case (str tool-name))]
    (cond
      (re-find #"read|list|glob|find|search|grep|stat" n) "read"
      (re-find #"write|edit|patch|replace|create" n) "edit"
      (re-find #"delete|remove|rm" n) "delete"
      (re-find #"shell|bash|exec|run|repl|cmd" n) "execute"
      :else "other")))

(defn- extract-prompt-text [prompt]
  (cond
    (string? prompt) prompt
    (sequential? prompt)
    (->> prompt
         (keep (fn [blk]
                 (cond
                   (string? blk) blk
                   (map? blk)
                   (or (:text blk)
                       (get-in blk [:resource :text])
                       (when-let [uri (get-in blk [:resource :uri])]
                         (str "[resource: " uri "]"))))))
         (str/join "\n"))
    (map? prompt) (or (:text prompt) (pr-str prompt))
    :else (str prompt)))

(defn new-state
  "Create mutable ACP server state. Injectable :cfg and :run-turn! for tests."
  ([] (new-state {}))
  ([{:keys [cfg run-turn!]}]
   {:cfg-fn (if cfg (constantly cfg) config/load-config)
    :run-turn! (or run-turn! core/run-turn!)
    :sessions (atom {})
    :in-flight (atom #{})}))

(defn- ensure-session! [state session-id cwd]
  (let [cfg ((:cfg-fn state))
        sid (or (not-empty (str session-id)) (str (UUID/randomUUID)))
        existing (get @(:sessions state) sid)
        resolved-cwd (or (when (and (string? cwd) (not (str/blank? cwd)) (fs/directory? cwd))
                           (str (fs/canonicalize cwd)))
                         (:cwd existing)
                         (:cwd (session/load-metadata sid))
                         (System/getProperty "user.dir"))]
    (when-not existing
      (swap! (:sessions state) assoc sid
             {:session/id sid
              :cwd resolved-cwd
              :mode (mode-for-cfg cfg)
              :steer-queue (atom [])
              :cancelled? (atom false)
              :active-future (atom nil)}))
    (let [meta-cfg (assoc cfg :cwd resolved-cwd)]
      (session/touch-metadata! sid meta-cfg)
      (when (and (string? cwd) (not (str/blank? cwd)) (fs/directory? cwd))
        (try (session/set-cwd! sid resolved-cwd)
             (catch Exception _ nil))))
    (get @(:sessions state) sid)))

(defn- handle-initialize [id]
  (ok-resp id
           {:protocolVersion 1
            :authMethods []
            :agentCapabilities {:loadSession true
                                :sessionCapabilities {:list {}
                                                      :resume {}
                                                      :close {}
                                                      :delete {}}
                                :promptCapabilities {:text true
                                                     :image false
                                                     :embeddedContext true}}
            :agentInfo {:name "theseus"
                        :version (str/replace version/v #"^v" "")}
            :_meta {:extensions ["_theseus/set_model"
                                 "_theseus/compact"
                                 "_session/steer"]}}))

(defn- handle-session-new [state id params emit!]
  (let [cfg ((:cfg-fn state))
        sess (ensure-session! state nil (:cwd params))
        sid (:session/id sess)]
    (emit! (session-update-notif sid {:sessionUpdate "available_commands_update"
                                      :availableCommands available-commands}))
    (ok-resp id {:sessionId sid
                 :models (models-payload cfg sid)
                 :modes (modes-payload (:mode sess))
                 :configOptions (config-options-payload cfg sid)})))

(defn- handle-session-list [id]
  (let [metas (session/list-metadata)
        sessions (mapv (fn [m]
                         {:sessionId (or (:session/id m) "default")
                          :cwd (or (:cwd m) (config/home))
                          :title (or (:title m) (:session/id m) "Theseus")
                          :updatedAt (or (:updated/at m) (str (java.time.Instant/now)))})
                       metas)]
    (ok-resp id {:sessions sessions :nextCursor nil})))

(defn- handle-session-load [state id params emit!]
  (let [cfg ((:cfg-fn state))
        sid (or (:sessionId params) (str (UUID/randomUUID)))
        sess (ensure-session! state sid (:cwd params))
        turns (session/load-turns sid)]
    (doseq [t turns]
      (when-let [u (:user/input t)]
        (when-not (str/blank? (str u))
          (emit! (session-update-notif sid {:sessionUpdate "user_message_chunk"
                                            :content {:type "text" :text (str u)}}))))
      (when-let [a (:assistant/final t)]
        (when-not (str/blank? (str a))
          (emit! (session-update-notif sid {:sessionUpdate "agent_message_chunk"
                                            :content {:type "text" :text (str a)}})))))
    (ok-resp id {:sessionId sid
                 :models (models-payload cfg sid)
                 :modes (modes-payload (:mode sess))
                 :configOptions (config-options-payload cfg sid)})))

(defn- handle-set-model [state id params]
  (let [sid (:sessionId params)
        model-id (or (:modelId params) (:value params))
        cfg ((:cfg-fn state))
        [prov m] (parse-model-id model-id)
        effective-prov (or prov (:provider cfg) :infered-local)]
    (when (and sid (not (str/blank? (str m))))
      (model/save-session-model! sid effective-prov m))
    (ok-resp id {})))

(defn- handle-set-mode [state id params emit!]
  (let [sid (:sessionId params)
        mode-id (or (:modeId params) (:value params) "full-access")]
    (ensure-session! state sid nil)
    (swap! (:sessions state) assoc-in [sid :mode] mode-id)
    (emit! (session-update-notif sid {:sessionUpdate "current_mode_update"
                                      :currentModeId mode-id}))
    (ok-resp id {})))

(defn- handle-set-config-option [state id params emit!]
  (let [sid (:sessionId params)
        cfg-id (:configId params)
        val (:value params)
        cfg ((:cfg-fn state))]
    (case cfg-id
      "model" (handle-set-model state id {:sessionId sid :modelId val})
      "mode" (handle-set-mode state id {:sessionId sid :modeId val} emit!)
      nil)
    (ok-resp id {:configOptions (config-options-payload cfg sid)})))

(defn- handle-steer [state id params]
  (let [sid (:sessionId params)
        text (or (:text params) (:prompt params) "")]
    (when-let [sess (get @(:sessions state) sid)]
      (when-not (str/blank? (str text))
        (swap! (:steer-queue sess) conj (str text))))
    (when id (ok-resp id {:ok true}))))

(defn- handle-cancel [state id params]
  (let [sid (:sessionId params)]
    (when-let [sess (get @(:sessions state) sid)]
      (reset! (:cancelled? sess) true)
      (when-let [fut @(:active-future sess)]
        (future-cancel fut)))
    (when id (ok-resp id {}))))

(defn- handle-close-or-delete [state id params delete?]
  (let [sid (:sessionId params)]
    (when sid
      (handle-cancel state nil {:sessionId sid})
      (swap! (:sessions state) dissoc sid)
      (when delete?
        (try (session/reset! sid) (catch Exception _ nil))))
    (ok-resp id {})))

(defn- handle-prompt [state id params emit!]
  (let [sid (:sessionId params)
        sess (ensure-session! state sid (:cwd params))
        prompt-text (extract-prompt-text (:prompt params))
        base-cfg ((:cfg-fn state))
        mode-overrides (cfg-overrides-for-mode (:mode sess))
        call-counter (atom 0)
        active-call-id (atom nil)
        status-emit
        (fn [evt]
          (case (:status evt)
            :tool/call
            (let [cid (str "call_" (swap! call-counter inc))
                  tname (str (:tool evt))]
              (reset! active-call-id cid)
              (emit! (session-update-notif sid
                                           {:sessionUpdate "tool_call"
                                            :toolCallId cid
                                            :title tname
                                            :kind (tool-kind tname)
                                            :status "in_progress"
                                            :rawInput (or (:args evt) {})})))
            :tool/done
            (let [cid (or @active-call-id (str "call_" @call-counter))
                  ok? (not (false? (get-in evt [:args :ok?])))]
              (emit! (session-update-notif sid
                                           {:sessionUpdate "tool_call_update"
                                            :toolCallId cid
                                            :status (if ok? "completed" "failed")})))
            nil))
        drain-steer
        (fn []
          (let [q (:steer-queue sess)
                items (first (swap-vals! q (constantly [])))]
            (when (seq items)
              (str/join "\n" items))))
        turn-cfg (-> base-cfg
                     (assoc :session/id sid
                            :cwd (:cwd sess)
                            :status/emit status-emit
                            :turn/drain-steer drain-steer)
                     (merge mode-overrides)
                     model/effective-config)]
    (reset! (:cancelled? sess) false)
    (try
      (let [turn ((:run-turn! state) turn-cfg prompt-text)
            final-raw (:assistant/final turn)
            final-text (if (string? final-raw)
                         (rich/terminal (rich/final-message final-raw))
                         (str (or final-raw "")))]
        (when-not (str/blank? final-text)
          (emit! (session-update-notif sid
                                       {:sessionUpdate "agent_message_chunk"
                                        :content {:type "text" :text final-text}})))
        (ok-resp id {:stopReason (if @(:cancelled? sess) "cancelled" "end_turn")}))
      (catch InterruptedException _
        (ok-resp id {:stopReason "cancelled"}))
      (catch Exception e
        (if @(:cancelled? sess)
          (ok-resp id {:stopReason "cancelled"})
          (err-resp id -32603 (or (ex-message e) (.getName (class e)))))))))

(defn handle-message!
  "Dispatch one parsed JSON-RPC 2.0 ACP message. Returns a response map (or nil
  for notifications without :id)."
  [state msg emit!]
  (let [id (:id msg)
        method (:method msg)
        params (or (:params msg) {})]
    (case method
      "initialize" (handle-initialize id)
      "authenticate" (ok-resp id {})
      "session/new" (handle-session-new state id params emit!)
      "session/list" (handle-session-list id)
      "session/load" (handle-session-load state id params emit!)
      "session/resume" (handle-session-load state id params emit!)
      "session/set_model" (handle-set-model state id params)
      "_theseus/set_model" (handle-set-model state id params)
      "session/set_mode" (handle-set-mode state id params emit!)
      "session/set_config_option" (handle-set-config-option state id params emit!)
      "session/prompt" (handle-prompt state id params emit!)
      "session/cancel" (handle-cancel state id params)
      "session/close" (handle-close-or-delete state id params false)
      "session/delete" (handle-close-or-delete state id params true)
      "_session/steer" (handle-steer state id params)
      ("_theseus/compact" "session/compact") (ok-resp id {})
      "notifications/initialized" nil
      (when (some? id)
        (err-resp id -32601 (str "Method not found: " method))))))

(defn- write-json-line! [^Writer out-writer write-lock msg]
  (when msg
    (let [line (str (json/generate-string msg) "\n")]
      (locking write-lock
        (.write out-writer line)
        (.flush out-writer)))))

(defn serve-io!
  "Run the line-delimited JSON-RPC 2.0 ACP loop over reader/out-writer/err-writer.
  Binds *out* to err-writer during execution so tool/log prints never corrupt
  the JSON-RPC stdout stream."
  ([^BufferedReader reader ^Writer out-writer ^Writer err-writer]
   (serve-io! reader out-writer err-writer {}))
  ([^BufferedReader reader ^Writer out-writer ^Writer err-writer opts]
   (let [state (new-state opts)
         write-lock (Object.)
         emit! #(write-json-line! out-writer write-lock %)]
     (binding [*out* err-writer]
       (loop []
         (when-let [line (.readLine reader)]
           (when-not (str/blank? line)
             (let [parsed (try {:ok (json/parse-string line true)}
                               (catch Exception e {:err (ex-message e)}))]
               (if-let [err (:err parsed)]
                 (emit! (err-resp nil -32700 (str "Parse error: " err)))
                 (let [msg (:ok parsed)]
                   (if (= "session/prompt" (:method msg))
                     (let [sid (get-in msg [:params :sessionId])
                           fut (future
                                 (binding [*out* err-writer]
                                   (try
                                     (when-let [resp (handle-message! state msg emit!)]
                                       (emit! resp))
                                     (catch Exception e
                                       (when (some? (:id msg))
                                         (emit! (err-resp (:id msg) -32603
                                                          (or (ex-message e) "Internal error"))))))))]
                       (when-let [sess (get @(:sessions state) sid)]
                         (reset! (:active-future sess) fut))
                       (swap! (:in-flight state) conj fut))
                     (when-let [resp (handle-message! state msg emit!)]
                       (emit! resp)))))))
           (recur)))
       ;; Drain any in-flight prompts cleanly on EOF (supports piped probes)
       (doseq [fut @(:in-flight state)]
         (try @fut (catch Exception _ nil)))))))

(defn -main [& _args]
  (let [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        out-writer (OutputStreamWriter. System/out "UTF-8")
        err-writer (OutputStreamWriter. System/err "UTF-8")]
    (serve-io! reader out-writer err-writer)))
