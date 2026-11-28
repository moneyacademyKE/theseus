(ns bb-agent.acp-test
  (:require [babashka.fs :as fs]
            [bb-agent.acp :as acp]
            [bb-agent.config :as config]
            [bb-agent.model :as model]
            [bb-agent.session :as session]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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

(deftest initialize-handshake-advertises-v1-and-capabilities
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
    (is (= "theseus" (get-in resp [:result :agentInfo :name])))
    (is (some #{"_session/steer"} (get-in resp [:result :_meta :extensions])))))

(deftest session-new-creates-session-returns-catalog-and-emits-commands
  (with-temp-home
    (fn [dir]
      (let [state (acp/new-state {:cfg (sample-cfg)})
            emitted (atom [])
            resp (acp/handle-message!
                  state
                  {:jsonrpc "2.0" :id 2 :method "session/new"
                   :params {:cwd dir :mcpServers []}}
                  #(swap! emitted conj %))
            sid (get-in resp [:result :sessionId])]
        (is (string? sid))
        (is (not (str/blank? sid)))
        (is (= "infered-local/infered/auto-budget"
               (get-in resp [:result :models :currentModelId])))
        (is (>= (count (get-in resp [:result :models :availableModels])) 2))
        (is (= "full-access" (get-in resp [:result :modes :currentModeId])))
        (is (= 5 (count (get-in resp [:result :modes :availableModes]))))
        (is (some #(= "model" (:id %)) (get-in resp [:result :configOptions])))
        ;; available_commands_update notification emitted
        (is (some #(= "available_commands_update"
                      (get-in % [:params :update :sessionUpdate]))
                  @emitted))
        ;; metadata persisted on disk
        (let [meta (session/load-metadata sid)]
          (is (= sid (:session/id meta)))
          (is (= (str (fs/canonicalize dir)) (:cwd meta))))))))

(deftest session-list-and-load-replay-history-and-survive-stale-id
  (with-temp-home
    (fn [dir]
      (let [state (acp/new-state {:cfg (sample-cfg)})
            emitted (atom [])
            new-resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 1 :method "session/new"
                       :params {:cwd dir}}
                      #(swap! emitted conj %))
            sid (get-in new-resp [:result :sessionId])]
        (session/append-turn! sid {:user/input "hello there"
                                   :assistant/final "general kenobi"})
        (let [list-resp (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 2 :method "session/list" :params {}}
                         #(swap! emitted conj %))]
          (is (some #(= sid (:sessionId %)) (get-in list-resp [:result :sessions]))))
        (reset! emitted [])
        (let [load-resp (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 3 :method "session/load"
                          :params {:sessionId sid :cwd dir}}
                         #(swap! emitted conj %))
              updates (map #(get-in % [:params :update]) @emitted)]
          (is (= 3 (:id load-resp)))
          (is (some #(and (= "user_message_chunk" (:sessionUpdate %))
                          (= "hello there" (get-in % [:content :text])))
                    updates))
          (is (some #(and (= "agent_message_chunk" (:sessionUpdate %))
                          (= "general kenobi" (get-in % [:content :text])))
                    updates)))
        ;; Stale/unknown sessionId must never fatal
        (let [stale-resp (acp/handle-message!
                          state
                          {:jsonrpc "2.0" :id 4 :method "session/load"
                           :params {:sessionId "nonexistent-session-xyz" :cwd dir}}
                          #(swap! emitted conj %))]
          (is (nil? (:error stale-resp)))
          (is (some? (get-in stale-resp [:result :models :currentModelId]))))))))

(deftest session-set-model-config-option-and-mode-persist-correctly
  (with-temp-home
    (fn [dir]
      (let [state (acp/new-state {:cfg (sample-cfg)})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        (testing "session/set_model persists provider/model pair"
          (let [resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 2 :method "session/set_model"
                       :params {:sessionId sid :modelId "infered-router/grok/grok-4.7"}}
                      #(swap! emitted conj %))
                saved (model/load-session-model sid)]
            (is (nil? (:error resp)))
            (is (= :infered-router (:provider saved)))
            (is (= "grok/grok-4.7" (:model saved)))))

        (testing "session/set_config_option updates model"
          (let [resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 3 :method "session/set_config_option"
                       :params {:sessionId sid :configId "model" :value "infered-local/infered/auto-budget"}}
                      #(swap! emitted conj %))
                saved (model/load-session-model sid)]
            (is (nil? (:error resp)))
            (is (= :infered-local (:provider saved)))
            (is (= "infered/auto-budget" (:model saved)))
            (is (seq (get-in resp [:result :configOptions])))))

        (testing "session/set_mode maps policy and emits current_mode_update"
          (reset! emitted [])
          (let [resp (acp/handle-message!
                      state
                      {:jsonrpc "2.0" :id 4 :method "session/set_mode"
                       :params {:sessionId sid :modeId "supervised"}}
                      #(swap! emitted conj %))]
            (is (nil? (:error resp)))
            (is (= "supervised" (get-in @(:sessions state) [sid :mode])))
            (is (some #(= "current_mode_update" (get-in % [:params :update :sessionUpdate]))
                      @emitted))))))))

(deftest session-prompt-streams-tools-and-answer-and-supports-steer
  (with-temp-home
    (fn [dir]
      (let [captured-cfg (atom nil)
            captured-prompt (atom nil)
            captured-steer (atom nil)
            fake-runner (fn [cfg prompt]
                          (reset! captured-cfg cfg)
                          (reset! captured-prompt prompt)
                          ((:status/emit cfg) {:status :tool/call :tool "read_file" :args {:path "README.md"}})
                          ((:status/emit cfg) {:status :tool/done :tool "read_file" :args {:ok? true}})
                          (reset! captured-steer ((:turn/drain-steer cfg)))
                          {:assistant/final "All done." :turn/ok true})
            state (acp/new-state {:cfg (sample-cfg) :run-turn! fake-runner})
            emitted (atom [])
            sid (get-in (acp/handle-message!
                         state
                         {:jsonrpc "2.0" :id 1 :method "session/new" :params {:cwd dir}}
                         #(swap! emitted conj %))
                        [:result :sessionId])]
        ;; Queue a mid-turn steer before running
        (acp/handle-message!
         state
         {:jsonrpc "2.0" :id 2 :method "_session/steer"
          :params {:sessionId sid :text "focus on tests"}}
         #(swap! emitted conj %))
        (reset! emitted [])
        (let [resp (acp/handle-message!
                    state
                    {:jsonrpc "2.0" :id 3 :method "session/prompt"
                     :params {:sessionId sid
                              :prompt [{:type "text" :text "inspect readme"}]}}
                    #(swap! emitted conj %))
              updates (map #(get-in % [:params :update]) @emitted)]
          (is (= "end_turn" (get-in resp [:result :stopReason])))
          (is (= "inspect readme" @captured-prompt))
          (is (= "focus on tests" @captured-steer))
          (is (= sid (:session/id @captured-cfg)))
          (is (some #(= "tool_call" (:sessionUpdate %)) updates))
          (is (some #(= "tool_call_update" (:sessionUpdate %)) updates))
          (is (some #(and (= "agent_message_chunk" (:sessionUpdate %))
                          (= "All done." (get-in % [:content :text])))
                    updates)))))))

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
