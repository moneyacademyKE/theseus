(ns bb-agent.acp-test
  (:require [babashka.fs :as fs]
            [bb-agent.acp :as acp]
            [bb-agent.config :as config]
            [bb-agent.model :as model]
            [bb-agent.plan :as plan]
            [bb-agent.session :as session]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io BufferedReader PipedInputStream PipedOutputStream
            InputStreamReader OutputStreamWriter]))

(defn- with-temp-home [f]
  (let [dir (str (fs/create-temp-dir {:prefix "theseus-acp-test-"}))]
    (try
      (with-redefs [config/home (fn [] dir)]
        (f dir))
      (finally
        (fs/delete-tree dir)))))

(defn- sample-cfg []
  {:provider :infered-local
   :model "infered/auto-budget"
   :approval/mode :auto-all
   :security/taint-gate? false
   :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"
                               :model "infered/auto-budget"}
               :infered-router {:base-url "https://api.inferhub.ai/v1"
                                :model "grok/grok-4.7"}}
   :provider/fallbacks [{:provider :infered-router :model "grok/grok-4.7"}]})

(deftest initialize-handshake-advertises-v1-and-image-capability
  (let [state (acp/new-state {:cfg (sample-cfg)})
        emitted (atom [])
        resp (acp/handle-message!
              state
              {:jsonrpc "2.0" :id 1 :method "initialize"
               :params {:protocolVersion 1 :clientCapabilities {}}}
              #(swap! emitted conj %))]
    (is (= "2.0" (:jsonrpc resp)))
    (is (= 1 (:id resp)))
    (is (= 1 (get-in resp [:result :protocolVersion])))
    (is (= [] (get-in resp [:result :authMethods])))
    (is (true? (get-in resp [:result :agentCapabilities :loadSession])))
    (is (true? (get-in resp [:result :agentCapabilities :promptCapabilities :image])))
    (is (= "theseus" (get-in resp [:result :agentInfo :name])))
    (is (some #{"_session/steer"} (get-in resp [:result :_meta :extensions])))))

(deftest session-new-creates-session-returns-catalog-and-emits-commands-with-skills
  (with-temp-home
    (fn [dir]
      (let [skill-dir (fs/path dir "skills" "demo-skill")]
        (fs/create-dirs skill-dir)
        (spit (str (fs/path skill-dir "SKILL.md"))
              "---\nname: demo-skill\ndescription: Demo ACP skill\n---\n# Demo\n"))
      (let [state (acp/new-state {:cfg (sample-cfg)})
            emitted (atom [])
            resp (acp/handle-message!
                  state
                  {:jsonrpc "2.0" :id 2 :method "session/new"
                   :params {:cwd dir :mcpServers []}}
                  #(swap! emitted conj %))
            sid (get-in resp [:result :sessionId])
            cmd-update (first (filter #(= "available_commands_update"
                                          (get-in % [:params :update :sessionUpdate]))
                                      @emitted))
            cmds (get-in cmd-update [:params :update :availableCommands])]
        (is (string? sid))
        (is (= "infered-local/infered/auto-budget"
               (get-in resp [:result :models :currentModelId])))
        (is (= "full-access" (get-in resp [:result :modes :currentModeId])))
        (is (= 5 (count (get-in resp [:result :modes :availableModes]))))
        (is (some #(= "demo-skill" (:name %)) cmds))
        (is (some #(= "compact" (:name %)) cmds))))))

(deftest session-list-load-auto-title-and-mode-persist-across-restarts
  (with-temp-home
    (fn [dir]
      (let [fake-runner (fn [_cfg _prompt] {:assistant/final "general kenobi" :turn/ok true})
            state1 (acp/new-state {:cfg (sample-cfg) :run-turn! fake-runner})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state1
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        (acp/handle-message!
         state1
         {:jsonrpc "2.0" :id 2 :method "session/set_mode"
          :params {:sessionId sid :modeId "supervised"}}
         #(swap! emitted conj %))
        (reset! emitted [])
        (acp/handle-message!
         state1
         {:jsonrpc "2.0" :id 3 :method "session/prompt"
          :params {:sessionId sid :prompt [{:type "text" :text "Refactor the database pool"}]}}
         #(swap! emitted conj %))
        (is (some #(and (= "session_info_update" (get-in % [:params :update :sessionUpdate]))
                        (= "Refactor the database pool" (get-in % [:params :update :title])))
                  @emitted))
        ;; Fresh server state simulates process restart
        (let [state2 (acp/new-state {:cfg (sample-cfg)})
              list-resp (acp/handle-message!
                         state2
                         {:jsonrpc "2.0" :id 4 :method "session/list" :params {}}
                         #(swap! emitted conj %))
              listed (first (filter #(= sid (:sessionId %))
                                    (get-in list-resp [:result :sessions])))
              load-resp (acp/handle-message!
                         state2
                         {:jsonrpc "2.0" :id 5 :method "session/load"
                          :params {:sessionId sid :cwd dir}}
                         #(swap! emitted conj %))]
          (is (= "Refactor the database pool" (:title listed)))
          (is (= "supervised" (get-in load-resp [:result :modes :currentModeId]))))))))

(deftest session-set-model-emits-config-option-update-and-persists
  (with-temp-home
    (fn [dir]
      (let [state (acp/new-state {:cfg (sample-cfg)})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        (reset! emitted [])
        (let [resp (acp/handle-message!
                    state
                    {:jsonrpc "2.0" :id 2 :method "session/set_model"
                     :params {:sessionId sid :modelId "infered-router/grok/grok-4.7"}}
                    #(swap! emitted conj %))
              saved (model/load-session-model sid)]
          (is (nil? (:error resp)))
          (is (= :infered-router (:provider saved)))
          (is (= "grok/grok-4.7" (:model saved)))
          (is (some #(= "config_option_update"
                        (get-in % [:params :update :sessionUpdate]))
                    @emitted)))))))

(deftest parallel-tool-calls-correlate-fifo-emit-locations-usage-plan-and-retry-thought
  (with-temp-home
    (fn [dir]
      (let [fake-runner (fn [cfg _prompt]
                          (let [emit (:status/emit cfg)]
                            ;; Parallel read_file calls
                            (emit {:status :tool/call :tool "read_file" :args {:path "a.clj"}})
                            (emit {:status :tool/call :tool "read_file" :args {:path "b.clj"}})
                            (emit {:status :tool/done :tool "read_file" :args {:ok? true}})
                            (emit {:status :tool/done :tool "read_file" :args {:ok? false}})
                            ;; Retry liveness event
                            (emit {:status :retry :attempt 1 :delay-ms 500 :reason "429"})
                            ;; Active plan created during turn
                            (plan/init-plan! (:session/id cfg) "Ship ACP" ["Step A" "Step B"])
                            (emit {:status :tool/call :tool "plan" :args {:action "init"}})
                            (emit {:status :tool/done :tool "plan" :args {:ok? true}}))
                          {:assistant/final "Done!"
                           :turn/ok true
                           :usage {:input-tokens 1200 :output-tokens 300 :cost-usd 0.002}})
            state (acp/new-state {:cfg (sample-cfg) :run-turn! fake-runner})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        (reset! emitted [])
        (let [resp (acp/handle-message!
                    state
                    {:jsonrpc "2.0" :id 2 :method "session/prompt"
                     :params {:sessionId sid :prompt "run checks"}}
                    #(swap! emitted conj %))
              updates (mapv #(get-in % [:params :update]) @emitted)
              tool-calls (filterv #(= "tool_call" (:sessionUpdate %)) updates)
              tool-dones (filterv #(= "tool_call_update" (:sessionUpdate %)) updates)
              usage-upd (first (filter #(= "usage_update" (:sessionUpdate %)) updates))
              plan-upd (first (filter #(= "plan" (:sessionUpdate %)) updates))
              thought-upd (first (filter #(= "agent_thought_chunk" (:sessionUpdate %)) updates))]
          (is (= "end_turn" (get-in resp [:result :stopReason])))
          ;; FIFO correlation: call_1 completed, call_2 failed
          (is (= "call_1" (:toolCallId (nth tool-dones 0))))
          (is (= "completed" (:status (nth tool-dones 0))))
          (is (= "call_2" (:toolCallId (nth tool-dones 1))))
          (is (= "failed" (:status (nth tool-dones 1))))
          ;; Locations populated for file paths
          (is (= [{:path "a.clj"}] (:locations (nth tool-calls 0))))
          ;; Usage update emitted
          (is (= 1500 (:used usage-upd)))
          (is (pos? (:size usage-upd)))
          ;; Plan update emitted
          (is (= 2 (count (:entries plan-upd))))
          (is (= "Step A" (:content (first (:entries plan-upd)))))
          ;; Retry thought emitted
          (is (some? thought-upd)))))))

(deftest image-blocks-decode-to-user-images-and-slash-commands-run-locally
  (with-temp-home
    (fn [dir]
      (let [captured-cfg (atom nil)
            llm-called? (atom false)
            fake-runner (fn [cfg _prompt]
                          (reset! llm-called? true)
                          (reset! captured-cfg cfg)
                          {:assistant/final "Saw image" :turn/ok true})
            state (acp/new-state {:cfg (sample-cfg) :run-turn! fake-runner})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        (testing "base64 image block writes attachment and populates :user/images"
          (let [png-b64 (.encodeToString (java.util.Base64/getEncoder) (.getBytes "fakepng" "UTF-8"))]
            (acp/handle-message!
             state
             {:jsonrpc "2.0" :id 2 :method "session/prompt"
              :params {:sessionId sid
                       :prompt [{:type "text" :text "what is this?"}
                                {:type "image" :mimeType "image/png" :data png-b64}]}}
             #(swap! emitted conj %))
            (let [imgs (:user/images @captured-cfg)]
              (is (= 1 (count imgs)))
              (is (= "image/png" (:mime-type (first imgs))))
              (is (fs/regular-file? (:path (first imgs)))))))

        (testing "/usage slash command runs locally without calling LLM"
          (reset! llm-called? false)
          (reset! emitted [])
          (let [resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 3 :method "session/prompt"
                       :params {:sessionId sid :prompt "/usage"}}
                      #(swap! emitted conj %))]
            (is (false? @llm-called?))
            (is (= "end_turn" (get-in resp [:result :stopReason])))
            (is (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate]))
                      @emitted))))

        (testing "session/compact runs real compression checkpointing"
          (dotimes [i 6]
            (session/append-turn! sid {:user/input (str "question " i " " (apply str (repeat 200 "x")))
                                       :assistant/final (str "answer " i " " (apply str (repeat 200 "y")))}))
          (let [resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 4 :method "session/compact"
                       :params {:sessionId sid}}
                      #(swap! emitted conj %))]
            (is (true? (get-in resp [:result :compacted])))))))))

(deftest interactive-permission-roundtrip-over-bidirectional-stdio
  (with-temp-home
    (fn [dir]
      (let [approval-result (atom nil)
            fake-runner (fn [cfg _prompt]
                          (let [ask (:approval/ask cfg)
                                decision (ask {:tool/name "shell" :tool/args {:cmd "ls"}})]
                            (reset! approval-result decision)
                            {:assistant/final (str "decision=" (name decision)) :turn/ok true}))
            in-pipe (PipedOutputStream.)
            in-reader (BufferedReader. (InputStreamReader. (PipedInputStream. in-pipe) "UTF-8"))
            out-pipe (PipedInputStream.)
            out-writer (OutputStreamWriter. (PipedOutputStream. out-pipe) "UTF-8")
            out-reader (BufferedReader. (InputStreamReader. out-pipe "UTF-8"))
            err-w (java.io.StringWriter.)
            server-fut (future
                         (acp/serve-io! in-reader out-writer err-w
                                        {:cfg (assoc (sample-cfg) :approval/mode :ask)
                                         :run-turn! fake-runner}))
            send! (fn [m]
                    (.write in-pipe (.getBytes (str (json/generate-string m) "\n") "UTF-8"))
                    (.flush in-pipe))
            read-msg! (fn [] (json/parse-string (.readLine out-reader) true))]
        ;; 1. session/new
        (send! {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}})
        (let [_cmd-notif (read-msg!)
              new-resp (read-msg!)
              sid (get-in new-resp [:result :sessionId])]
          ;; 2. session/prompt triggers server->client session/request_permission
          (send! {:jsonrpc "2.0" :id 2 :method "session/prompt"
                  :params {:sessionId sid :prompt "run ls"}})
          (let [perm-req (loop []
                           (let [m (read-msg!)]
                             (if (= "session/request_permission" (:method m))
                               m
                               (recur))))]
            (is (some? (:id perm-req)))
            (is (= sid (get-in perm-req [:params :sessionId])))
            ;; 3. Client replies allow_once
            (send! {:jsonrpc "2.0" :id (:id perm-req)
                    :result {:outcome {:outcome "selected" :optionId "allow_once"}}})
            ;; 4. Drain until prompt response id=2
            (let [prompt-resp (loop []
                                (let [m (read-msg!)]
                                  (if (= 2 (:id m))
                                    m
                                    (recur))))]
              (is (= "end_turn" (get-in prompt-resp [:result :stopReason])))
              (is (= :approved @approval-result)))))
        (.close in-pipe)
        @server-fut))))

(deftest stdio-loop-handles-parse-errors-unknown-methods-and-stays-json-pure
  (with-temp-home
    (fn [_dir]
      (let [in-str (str "{bad json\n"
                        (json/generate-string {:jsonrpc "2.0" :id 10 :method "initialize" :params {:protocolVersion 1}}) "\n"
                        (json/generate-string {:jsonrpc "2.0" :id 11 :method "no/such/method" :params {}}) "\n")
            out-w (java.io.StringWriter.)
            err-w (java.io.StringWriter.)]
        (acp/serve-io! (java.io.BufferedReader. (java.io.StringReader. in-str))
                       out-w
                       err-w
                       {:cfg (sample-cfg)})
        (let [lines (->> (str/split-lines (str out-w))
                         (remove str/blank?)
                         (mapv #(json/parse-string % true)))]
          (is (= 3 (count lines)))
          (is (= -32700 (get-in (nth lines 0) [:error :code])))
          (is (= 10 (:id (nth lines 1))))
          (is (= 1 (get-in (nth lines 1) [:result :protocolVersion])))
          (is (= 11 (:id (nth lines 2))))
          (is (= -32601 (get-in (nth lines 2) [:error :code]))))))))
