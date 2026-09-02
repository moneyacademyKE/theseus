(ns bb-agent.tool
  (:require [bb-agent.policy :as policy]
            [bb-agent.rtk :as rtk]
            [bb-agent.tool.common :as common]
            [bb-agent.tool.file :as file]
            [bb-agent.tool.process :as process]
            [bb-agent.tool.telegram :as telegram]))

(def normalize-request common/normalize-request)
(def approval-decision common/approval-decision)
(def deny-result common/deny-result)

(def ^:private handlers
  (merge file/handlers process/handlers telegram/handlers))

(defn execute-tool-request [request]
  (let [{:tool/keys [name args]} request
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

(defn handle-tool-request
  "Policy predicates (brain/rules.clj, when :policy {:enabled true}) decide
  first: :allow executes and :deny refuses, replacing the classifier verdict.
  nil (disabled, no rules, no match, broken rules) falls back to the ordinary
  approval flow unchanged."
  ([request] (handle-tool-request request {}))
  ([request cfg]
   (let [request (normalize-request request)
         request (cond-> request
                   (and (:cwd cfg)
                        (contains? #{"shell" "git_status" "telegram_send_file"}
                                   (:tool/name request))
                        (nil? (get-in request [:tool/args :cwd])))
                   (assoc-in [:tool/args :cwd] (:cwd cfg))
                   (and (map? (:telegram/send-context cfg))
                        (= "telegram_send_file" (:tool/name request)))
                   (assoc-in [:tool/args :telegram-session]
                             (select-keys (:telegram/send-context cfg)
                                          [:chat-id :thread-id])))
         approver (:approval/ask cfg)
         result (if-let [verdict (policy/verdict request cfg)]
                  (case verdict
                    :allow (execute-tool-request request)
                    :deny (deny-result request))
                  (case (approval-decision request)
                    :approved (execute-tool-request request)
                    :ask (if (and approver (= :approved (approver request)))
                           (execute-tool-request request)
                           (deny-result request))
                    :denied (deny-result request)))]
     (rtk/apply request result cfg))))
