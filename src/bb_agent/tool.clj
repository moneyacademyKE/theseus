(ns bb-agent.tool
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.datalog :as datalog]
            [bb-agent.plan :as plan]
            [bb-agent.redact :as redact]
            [bb-agent.rtk :as rtk]
            [bb-agent.security :as sec]
            [bb-agent.skill :as skill]
            [bb-agent.subagent :as subagent]
            [bb-agent.a2a-tool :as a2a-tool]
            [bb-agent.net-search :as net-search]
            [bb-agent.browser-tool :as browser-tool]
            [bb-agent.tools :as tools]
            [bb-agent.tool.common :as common]
            [bb-agent.tool-hints :as hints]
            [bb-agent.tool.file :as file]
            [bb-agent.tool.goal :as goal]
            [bb-agent.tool.process :as process]
            [bb-agent.tool.telegram :as telegram]
            [bb-agent.tool.web :as web]
            [bb-agent.usage :as usage]
            [clojure.string]))

(def normalize-request common/normalize-request)
(def approval-decision common/approval-decision)
(def deny-result common/deny-result)

(def ^:private handlers
  (merge file/handlers process/handlers telegram/handlers goal/handlers
         web/handlers net-search/handlers browser-tool/handlers
         a2a-tool/handlers datalog/handlers))

(defn execute-tool-request
  "Run one tool request. The argument heal happens HERE as well as in
   normalize-request, on purpose: this is the execution seam, so any caller that
   reaches a handler without going through handle-tool-request — tests, the goal
   runner, a future dispatch path — still cannot hand a handler a truthy string
   where the schema declares a boolean. TYSA states the rule as: no tool can
   ship unwrapped. The second pass is harmless because heals are idempotent.

   Measured before this existed: write_file with :append? as the string false
   APPENDED (any non-nil string is truthy in Clojure), so a model that asked to
   overwrite got the old bytes kept plus the new ones — on the seam that writes
   brain/."
  [request]
  (let [{:tool/keys [name args]} request
        args (common/heal-args (str name) args)
        handler (get handlers name)]
    (if handler
      (try
        (handler args)
        (catch Exception e
          (common/error-result name
                               (or (ex-message e) (.getName (class e)))
                               {:exception/type (str (class e))
                                :executed? false})))
      (common/error-result name (str "Unknown tool: " name) {}))))

(defn- run-discover-tools [args cfg]
  (let [q (or (:query args) (get args "query") "")
        matches (tools/search-deferred-tools cfg q)
        names (mapv #(get-in % [:function :name]) matches)
        summary (if (seq matches)
                  (str "Discovered tools (now unlocked for subsequent rounds): "
                       (clojure.string/join ", " names))
                  (str "No deferred tools matched query: " q))]
    (common/ok-result "discover_tools"
                      {:query q
                       :discovered names
                       :output summary})))

(defn handle-tool-request
  "Route one request through optional-capability and approval gates.
  Dormant optional tools are denied before any approval runs; approved tools
  execute once through the wrapped handler."
  ([request] (handle-tool-request request {}))
  ([request cfg]
   (let [started-at (System/nanoTime)
         requested-policy (:approval/policy request)
         request (normalize-request request)
         request (cond-> request
                   (and (:cwd cfg)
                        (contains? #{"shell" "git_status" "telegram_send_file"
                                     "web_send_file"}
                                   (:tool/name request))
                        (nil? (get-in request [:tool/args :cwd])))
                   ;; A stale/missing session cwd (e.g. a pre-migration
                   ;; path that no longer exists) must not poison every
                   ;; shell call — fall back to the home root instead.
                   ;; (2026-09-08: session metadata carried the dead Desktop
                   ;; path for a week; every cwd-less shell errored.)
                   (assoc-in [:tool/args :cwd]
                             (let [c (:cwd cfg)]
                               (if (and (string? c) (fs/directory? c))
                                 c
                                 (str (config/home)))))
                   (and (map? (:telegram/send-context cfg))
                        (= "telegram_send_file" (:tool/name request)))
                   (assoc-in [:tool/args :telegram-session]
                             (select-keys (:telegram/send-context cfg)
                                          [:chat-id :thread-id]))
                   (and (string? (get cfg :web/job-id))
                        (= "web_send_file" (:tool/name request)))
                   (assoc-in [:tool/args :web-job-id] (get cfg :web/job-id))
                   (= "read_session_turns" (:tool/name request))
                   (assoc-in [:tool/args :session/id] (:session/id cfg))
                   (and (map? (:telegram/send-context cfg))
                        (= "launch_goal" (:tool/name request)))
                   (assoc-in [:tool/args :goal/send-context]
                             (cond-> (select-keys (:telegram/send-context cfg)
                                                  [:chat-id :thread-id])
                               (:status/emit cfg) (assoc :goal/emit (:status/emit cfg)))))
         approver (:approval/ask cfg)
         ;; Only an omitted request policy may inherit a configured mode.
         decision-request (if (and (nil? requested-policy)
                                   (:approval/mode cfg))
                            (assoc request :approval/policy (:approval/mode cfg))
                            request)
         approval-decision* (approval-decision decision-request)
         taint-decision (when-let [exp (:security/exposure cfg)]
                          (sec/taint-gate-decision exp (:tool/name request) (:tool/args request) cfg))
         skill-gate-hit (when-let [skills (or (:skill/skills cfg)
                                              (when (:skill/gate-enabled cfg)
                                                (skill/discover-all-skills)))]
                          (skill/check-skill-gate skills
                                                  (:skill/seen cfg)
                                                  (:tool/name request)
                                                  (get-in request [:tool/args :path])))
         plan-gate-hit? (plan/plan-gate-blocked? cfg (:tool/name request))
         run-request! (fn [req]
                        (case (:tool/name req)
                          "plan" (plan/run-plan-tool (:tool/args req) cfg)
                          "spawn_subagent" (subagent/run-subagent! (:tool/args req) cfg)
                          (execute-tool-request req)))
         raw-result (cond
                      (= "discover_tools" (:tool/name request))
                      (case approval-decision*
                        :approved (run-discover-tools (:tool/args request) cfg)
                        :ask (if (and approver (= :approved (approver request)))
                               (run-discover-tools (:tool/args request) cfg)
                               (deny-result request))
                        :denied (deny-result request))

                      (tools/dormant? cfg (:tool/name request))
                      ;; Optional capability the owner never opted into: refuse
                      ;; BEFORE execution. A tool absent from the prompt must not
                      ;; run just because a model hallucinated its name.
                      (deny-result request :dormant)

                      plan-gate-hit?
                      (deny-result request :plan-gate
                                   {:reason (str "Plan gate: " (:turn/tool-calls cfg)
                                                 " tool calls reached without an active session plan. "
                                                 "Call plan(action=\"init\", title=..., steps=[...]) before further edits.")})

                      skill-gate-hit
                      (assoc (deny-result request :skill-gate
                                          {:reason (str "Skill gate: read and follow skill '"
                                                        (:skill/name skill-gate-hit)
                                                        "' before editing "
                                                        (get-in request [:tool/args :path])
                                                        ". Retry the edit once reviewed.")})
                             :skill/name (:skill/name skill-gate-hit)
                             :skill/body (:skill/body skill-gate-hit))

                      (and (map? taint-decision) (:require-approval? taint-decision))
                      (if (and approver (= :approved (approver request)))
                        (run-request! request)
                        (deny-result request :taint {:reason (:reason taint-decision)}))

                      :else
                      (case approval-decision*
                        :approved (run-request! request)
                        :ask (if (and approver (= :approved (approver request)))
                               (run-request! request)
                               (deny-result request))
                        :denied (deny-result request)))
         redacted-result (redact/redact-deep raw-result)
         [_exp scan] (sec/note-exposure (:security/exposure cfg) (:tool/name request) redacted-result)
         result (cond-> redacted-result
                  (:suspicious? scan)
                  (assoc :security/spotlight (sec/spotlight-note scan)
                         :security/signals (:signals scan)))]
     ;; THE funnel: one recording site for every tool outcome — ok, error,
     ;; or denied with its denial source (approval vs constitution). The
     ;; RSI digest builds its tool-failure table from these events; a
     ;; tool that errors on 20%+ of ≥5 calls becomes a named opportunity.
     ;; Telemetry is best-effort: a ledger failure must never fail the
     ;; tool call it measures. Attribution is required (see usage/tool-event!).
     (try
       (usage/tool-event! {:tool (:tool/name request)
                           :session-id (or (:session/id cfg) (:session/id request))
                           :status (:status result)
                           :executed? (boolean (:executed? result))
                           :ms (quot (- (System/nanoTime) started-at) 1000000)
                           :ok (boolean (and (:executed? result)
                                             (= :ok (:status result))))
                           :denial-source (:denial/source result)
                           :denial-rule (:denial/rule result)
                           :denial-category (:denial/category result)
                           :denial-reason (:denial/reason result)})
       (catch Exception _ nil))
     (hints/attach-next-step request (rtk/apply request result cfg)))))
