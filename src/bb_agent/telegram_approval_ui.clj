(ns bb-agent.telegram-approval-ui
  "The inline-button approval interaction over Telegram.

   Approval prompts carry buttons whose callback_data is bound to one
   specific pending approval id; callback answers authorize the clicker
   through the same guard policy as message senders and resolve by id, not
   FIFO. Text commands (/approve, /deny) stay valid alongside."
  (:require [bb-agent.approval :as approval]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-guard :as guard]
            [clojure.string :as str]))

(defn parse-callback-data
  "Parse approval-button callback data into a bounded action. Anything
   else - garbage, blank ids, foreign namespaces - is not an approval."
  [data]
  (when (string? data)
    (let [[action approval-id] (str/split data #":" 2)]
      (when (and (seq approval-id)
                 (contains? #{"appr" "deny" "apprrest"} action))
        {:action (case action
                   "appr" :approve
                   "deny" :deny
                   :approve-rest)
         :approval-id approval-id}))))

(defn send-approval-request!
  "Send the tool-approval prompt with inline buttons bound to this pending
   approval's id. Text commands stay valid alongside the buttons."
  [cfg chat-id thread-id pending]
  (let [id (:approval/id pending)]
    (delivery/send-message!
     cfg chat-id
     (str "Tool approval requested\n"
          "id=" id "\n"
          "tool=" (:tool/name pending) "\n"
          (pr-str (:tool/args pending)) "\n"
          "Reply /approve, /deny, or /approve-rest - or use the buttons.")
     {:thread-id thread-id
      :reply-markup
      {:inline_keyboard
       [[{:text "✅ Approve" :callback_data (str "appr:" id)}
         {:text "❌ Deny" :callback_data (str "deny:" id)}]
        [{:text "✅ Approve rest" :callback_data (str "apprrest:" id)}]]}})))

(defn expire-keyboard!
  "Replace an expired approval prompt's text, which drops its inline
   keyboard. Cosmetic: a failed edit never aborts the poll cycle."
  [cfg chat-id message-id approval-id]
  (try
    (delivery/edit-message-text!
     cfg chat-id message-id
     (str "Approval expired\ntool request id=" approval-id))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "telegram approval expiry edit failed: "
                      (.getMessage e)))))))

(defn handle-callback!
  [cfg callback]
  (let [telegram-cfg (:telegram cfg)
        query (:callback_query callback)
        message (:message query)
        data (parse-callback-data (:data query))]
    (cond
      (or (nil? message) (nil? data))
      (delivery/answer-callback-query!
       telegram-cfg (:id query) "Unsupported action")

      (not (guard/callback-allowed? cfg query))
      (delivery/answer-callback-query!
       telegram-cfg (:id query) "Not authorized")

      :else
      (let [decision (:action data)
            resolved (approval/resolve-by-id!
                      (:approval-id data)
                      (group/session-id message)
                      decision)
            receipt (approval/approval-reply-text resolved decision)]
        (delivery/answer-callback-query! telegram-cfg (:id query) receipt)
        (when resolved
          ;; The decision is durable; a failed cosmetic edit must not
          ;; abort the poll cycle.
          (try
            (delivery/edit-message-text!
             telegram-cfg
             (get-in message [:chat :id])
             (:message_id message)
             (str receipt "\nid=" (:approval-id data)))
            (catch Exception e
              (binding [*out* *err*]
                (println (str "telegram approval edit failed: "
                              (.getMessage e)))))))))))
