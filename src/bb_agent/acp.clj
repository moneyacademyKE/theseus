(ns bb-agent.acp
  "Agent Client Protocol (ACP v1) stdio JSON-RPC 2.0 server for Theseus.

  Exposes Theseus as a first-class local ACP provider for T3 Code, Zed,
  JetBrains, MonoCode, and any ACP-compatible client via `theseus acp`.
  Supports bidirectional JSON-RPC (interactive `session/request_permission`),
  parallel tool-call FIFO correlation, live plan/usage/thought updates,
  multimodal image attachments, local slash commands, and real compaction."
  (:require [babashka.fs :as fs]
            [bb-agent.acp-helpers :as h]
            [bb-agent.compression :as compression]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.doctor :as doctor]
            [bb-agent.model :as model]
            [bb-agent.plan :as plan]
            [bb-agent.rich :as rich]
            [bb-agent.session :as session]
            [bb-agent.usage :as usage]
            [bb-agent.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter Writer]
           [java.util UUID]))

(defn- ok-resp [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- err-resp [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- session-update-notif [session-id update-map]
  {:jsonrpc "2.0"
   :method "session/update"
   :params {:sessionId session-id
            :update update-map}})

(defn new-state
  "Create mutable ACP server state. Injectable :cfg and :run-turn! for tests."
  ([] (new-state {}))
  ([{:keys [cfg run-turn!]}]
   {:cfg-fn (if cfg (constantly cfg) config/load-config)
    :run-turn! (or run-turn! core/run-turn!)
    :sessions (atom {})
    :in-flight (atom #{})
    :next-req-id (atom 1000)
    :pending-client-reqs (atom {})}))

(defn- ensure-session! [state session-id cwd]
  (let [cfg ((:cfg-fn state))
        sid (or (not-empty (str session-id)) (str (UUID/randomUUID)))
        existing (get @(:sessions state) sid)
        persisted (session/load-metadata sid)
        resolved-cwd (or (when (and (string? cwd) (not (str/blank? cwd)) (fs/directory? cwd))
                           (str (fs/canonicalize cwd)))
                         (:cwd existing)
                         (:cwd persisted)
                         (System/getProperty "user.dir"))
        resolved-mode (or (:mode existing)
                          (:acp/mode persisted)
                          (h/mode-for-cfg cfg))]
    (when-not existing
      (swap! (:sessions state) assoc sid
             {:session/id sid
              :cwd resolved-cwd
              :mode resolved-mode
              :title (:title persisted)
              :approve-all? (atom false)
              :steer-queue (atom [])
              :cancelled? (atom false)
              :active-future (atom nil)}))
    (let [meta-cfg (assoc cfg :cwd resolved-cwd)]
      (session/touch-metadata! sid meta-cfg)
      (when (and (string? cwd) (not (str/blank? cwd)) (fs/directory? cwd))
        (try (session/set-cwd! sid resolved-cwd)
             (catch Exception _ nil))))
    (get @(:sessions state) sid)))

(defn- persist-session-fields! [sid fields]
  (let [existing (or (session/load-metadata sid) {:session/id sid})]
    (session/save-metadata! sid (merge existing fields {:updated/at (str (java.time.Instant/now))}))))

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
                                                     :image true
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
                                      :availableCommands (h/available-commands-for)}))
    (ok-resp id {:sessionId sid
                 :models (h/models-payload cfg sid)
                 :modes (h/modes-payload (:mode sess))
                 :configOptions (h/config-options-payload cfg sid)})))

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
    (when-let [entries (h/plan->acp-entries (plan/load-plan sid))]
      (emit! (session-update-notif sid {:sessionUpdate "plan" :entries entries})))
    (ok-resp id {:sessionId sid
                 :models (h/models-payload cfg sid)
                 :modes (h/modes-payload (:mode sess))
                 :configOptions (h/config-options-payload cfg sid)})))

(defn- handle-set-model [state id params emit!]
  (let [sid (:sessionId params)
        model-id (or (:modelId params) (:value params))
        cfg ((:cfg-fn state))
        [prov m] (h/parse-model-id model-id)
        effective-prov (or prov (:provider cfg) :infered-local)]
    (when (and sid (not (str/blank? (str m))))
      (model/save-session-model! sid effective-prov m)
      (emit! (session-update-notif sid {:sessionUpdate "config_option_update"
                                        :configOptions (h/config-options-payload cfg sid)})))
    (ok-resp id {})))

(defn- handle-set-mode [state id params emit!]
  (let [sid (:sessionId params)
        mode-id (or (:modeId params) (:value params) "full-access")]
    (ensure-session! state sid nil)
    (swap! (:sessions state) assoc-in [sid :mode] mode-id)
    (persist-session-fields! sid {:acp/mode mode-id})
    (emit! (session-update-notif sid {:sessionUpdate "current_mode_update"
                                      :currentModeId mode-id}))
    (ok-resp id {})))

(defn- handle-set-config-option [state id params emit!]
  (let [sid (:sessionId params)
        cfg-id (:configId params)
        val (:value params)
        cfg ((:cfg-fn state))]
    (case cfg-id
      "model" (handle-set-model state id {:sessionId sid :modelId val} emit!)
      "mode" (handle-set-mode state id {:sessionId sid :modeId val} emit!)
      nil)
    (ok-resp id {:configOptions (h/config-options-payload cfg sid)})))

(defn- handle-compact [state id params]
  (let [sid (:sessionId params)
        cfg ((:cfg-fn state))
        turns (->> (session/load-turns sid)
                   (map-indexed (fn [idx t] (assoc t :turn/index idx)))
                   vec)]
    (if (< (count turns) 2)
      (ok-resp id {:compacted false :messagesCount (count turns)})
      (let [budget (max 400 (quot (compression/history-budget-chars cfg 0) 4))
            msgs (compression/compress-session-turns! sid turns {:tail-char-budget budget})]
        (ok-resp id {:compacted true :messagesCount (count msgs)})))))

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

(defn- request-client-permission!
  "Send a blocking server->client `session/request_permission` JSON-RPC request
  and wait for the client's response (resolved by `serve-io!`)."
  [state emit! sid sess tool-call-id req]
  (if @(:approve-all? sess)
    :approved
    (let [req-id (swap! (:next-req-id state) inc)
          p (promise)
          tname (str (:tool/name req))]
      (swap! (:pending-client-reqs state) assoc req-id p)
      (emit! {:jsonrpc "2.0"
              :id req-id
              :method "session/request_permission"
              :params {:sessionId sid
                       :toolCall {:toolCallId (or tool-call-id (str "perm_" req-id))
                                  :title (str "Allow " tname)
                                  :kind (h/tool-kind tname)
                                  :rawInput (or (:tool/args req) {})}
                       :options [{:optionId "allow_once" :name "Allow once" :kind "allow_once"}
                                 {:optionId "allow_always" :name "Always allow" :kind "allow_always"}
                                 {:optionId "reject_once" :name "Reject" :kind "reject_once"}]}})
      (let [resp (deref p 600000 nil)]
        (swap! (:pending-client-reqs state) dissoc req-id)
        (let [opt-id (or (get-in resp [:result :outcome :optionId])
                         (get-in resp [:result :optionId]))]
          (case opt-id
            "allow_always" (do (reset! (:approve-all? sess) true) :approved)
            "allow_once" :approved
            :denied))))))

(defn- try-slash-command!
  "Intercept built-in /commands locally without burning LLM tokens.
  Returns response text if handled, or nil to proceed to `run-turn!`."
  [state sid prompt-text]
  (let [trimmed (str/trim (str prompt-text))]
    (when (str/starts-with? trimmed "/")
      (let [[cmd] (str/split trimmed #"\s+" 2)]
        (case cmd
          "/usage" (str "Usage report:\n```edn\n" (pr-str (usage/report)) "\n```")
          "/doctor" (let [checks (doctor/run-checks)]
                      (str/join "\n" (map (fn [{:keys [status id message]}]
                                            (str "[" (str/upper-case (name status)) "] " id ": " message))
                                          checks)))
          "/compact" (let [res (:result (handle-compact state nil {:sessionId sid}))]
                       (str "Compacted: " (:compacted res) " (" (:messagesCount res) " messages)"))
          "/sessions" (let [metas (take 10 (session/list-metadata))]
                        (str/join "\n" (map #(str "- " (:session/id %) " (" (or (:title %) "untitled") ")") metas)))
          nil)))))

(defn- handle-prompt [state id params emit!]
  (let [sid (:sessionId params)
        sess (ensure-session! state sid (:cwd params))
        prompt-text (h/extract-prompt-text (:prompt params))
        user-images (h/decode-image-blocks! (:prompt params))]
    (when-not (:title sess)
      (when-let [title (h/derive-title prompt-text)]
        (swap! (:sessions state) assoc-in [sid :title] title)
        (persist-session-fields! sid {:title title})
        (emit! (session-update-notif sid {:sessionUpdate "session_info_update"
                                          :title title}))))
    (if-let [local-reply (try-slash-command! state sid prompt-text)]
      (do
        (emit! (session-update-notif sid {:sessionUpdate "agent_message_chunk"
                                          :content {:type "text" :text local-reply}}))
        (ok-resp id {:stopReason "end_turn"}))
      (let [base-cfg ((:cfg-fn state))
            mode-overrides (h/cfg-overrides-for-mode (:mode sess))
            call-counter (atom 0)
            call-queues (atom {})
            last-call-id (atom nil)
            enqueue-call! (fn [tname]
                            (let [cid (str "call_" (swap! call-counter inc))]
                              (reset! last-call-id cid)
                              (swap! call-queues update tname
                                     #(conj (or % clojure.lang.PersistentQueue/EMPTY) cid))
                              cid))
            dequeue-call! (fn [tname]
                            (let [cid (atom nil)]
                              (swap! call-queues
                                     (fn [qs]
                                       (let [q (get qs tname clojure.lang.PersistentQueue/EMPTY)]
                                         (if (seq q)
                                           (do (reset! cid (peek q))
                                               (assoc qs tname (pop q)))
                                           qs))))
                              (or @cid @last-call-id (str "call_" @call-counter))))
            status-emit
            (fn [evt]
              (case (:status evt)
                :tool/call
                (let [tname (str (:tool evt))
                      cid (enqueue-call! tname)
                      locs (h/tool-locations (:args evt))]
                  (emit! (session-update-notif
                          sid
                          (cond-> {:sessionUpdate "tool_call"
                                   :toolCallId cid
                                   :title tname
                                   :kind (h/tool-kind tname)
                                   :status "in_progress"
                                   :rawInput (or (:args evt) {})}
                            (seq locs) (assoc :locations locs)))))
                :tool/done
                (let [tname (str (:tool evt))
                      cid (dequeue-call! tname)
                      ok? (not (false? (get-in evt [:args :ok?])))]
                  (emit! (session-update-notif sid
                                               {:sessionUpdate "tool_call_update"
                                                :toolCallId cid
                                                :status (if ok? "completed" "failed")}))
                  (when (= "plan" tname)
                    (when-let [entries (h/plan->acp-entries (plan/load-plan sid))]
                      (emit! (session-update-notif sid {:sessionUpdate "plan"
                                                        :entries entries})))))
                :retry
                (emit! (session-update-notif sid
                                             {:sessionUpdate "agent_thought_chunk"
                                              :content {:type "text"
                                                        :text (str "Retrying provider (attempt "
                                                                   (or (:attempt evt) 1)
                                                                   (when-let [r (:reason evt)]
                                                                     (str ", " r))
                                                                   ")...")}}))
                nil))
            drain-steer
            (fn []
              (let [q (:steer-queue sess)
                    items (first (swap-vals! q (constantly [])))]
                (when (seq items)
                  (str/join "\n" items))))
            approver (when-not (= "full-access" (:mode sess))
                       (fn [req]
                         (request-client-permission! state emit! sid sess @last-call-id req)))
            turn-cfg (-> base-cfg
                         (assoc :session/id sid
                                :cwd (:cwd sess)
                                :status/emit status-emit
                                :turn/drain-steer drain-steer)
                         (cond-> (seq user-images) (assoc :user/images user-images)
                                 approver (assoc :approval/ask approver))
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
            (when-let [u (h/usage->acp-update (:usage turn) (:model turn-cfg))]
              (emit! (session-update-notif sid u)))
            (ok-resp id {:stopReason (if @(:cancelled? sess) "cancelled" "end_turn")}))
          (catch InterruptedException _
            (ok-resp id {:stopReason "cancelled"}))
          (catch Exception e
            (if @(:cancelled? sess)
              (ok-resp id {:stopReason "cancelled"})
              (err-resp id -32603 (or (ex-message e) (.getName (class e)))))))))))

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
      "session/set_model" (handle-set-model state id params emit!)
      "_theseus/set_model" (handle-set-model state id params emit!)
      "session/set_mode" (handle-set-mode state id params emit!)
      "session/set_config_option" (handle-set-config-option state id params emit!)
      "session/prompt" (handle-prompt state id params emit!)
      "session/cancel" (handle-cancel state id params)
      "session/close" (handle-close-or-delete state id params false)
      "session/delete" (handle-close-or-delete state id params true)
      "_session/steer" (handle-steer state id params)
      ("_theseus/compact" "session/compact") (handle-compact state id params)
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
  Supports bidirectional JSON-RPC (resolving client responses to server-initiated
  `session/request_permission` requests) and strict stdout purity."
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
                   (cond
                     ;; Client response to a server-initiated request (e.g. session/request_permission)
                     (and (some? (:id msg)) (nil? (:method msg)))
                     (when-let [p (get @(:pending-client-reqs state) (:id msg))]
                       (deliver p msg))

                     (= "session/prompt" (:method msg))
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

                     :else
                     (when-let [resp (handle-message! state msg emit!)]
                       (emit! resp)))))))
           (recur)))
       (doseq [fut @(:in-flight state)]
         (try @fut (catch Exception _ nil)))))))

(defn -main [& _args]
  (let [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        out-writer (OutputStreamWriter. System/out "UTF-8")
        err-writer (OutputStreamWriter. System/err "UTF-8")]
    (serve-io! reader out-writer err-writer)))
