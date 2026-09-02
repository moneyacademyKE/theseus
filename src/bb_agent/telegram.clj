(ns bb-agent.telegram
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.telegram-attachment :as attachment]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-media :as media]
            [bb-agent.telegram-notes :as notes]
            [bb-agent.telegram-extract :as extract]
            [bb-agent.telegram-guard :as guard]
            [bb-agent.telegram-presence :as presence]
            [bb-agent.telegram-rich :as tr]
            [bb-agent.telegram-voice :as voice]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn telegram-config []
  (:telegram (config/load-config)))

(defn- require-telegram [cfg key]
  (let [value (get cfg key)]
    (when (str/blank? value)
      (throw (ex-info (str "Missing telegram config: " (name key))
                      {:config/key key})))
    value))

(defn- api-url [{:keys [base-url token]} method]
  (str (str/replace (or base-url "https://api.telegram.org") #"/+$" "")
       "/bot" token "/" method))

(defn- offset-file []
  (fs/path (config/home) "state" "telegram-offset.edn"))

(defn- seen-file []
  (fs/path (config/home) "state" "telegram-seen.edn"))

(defn- load-offset []
  (let [path (offset-file)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn- save-offset! [offset]
  (let [path (offset-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str offset))
    offset))

(defn- load-seen []
  (let [path (seen-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      #{})))

(defn- save-seen! [seen]
  (let [path (seen-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str seen))
    seen))

(defn- api-get [cfg method opts]
  (let [response (http/get (api-url cfg method) (assoc opts :throw false))]
    (json/parse-string (:body response) keyword)))

(defn- get-bot [cfg]
  (:result (api-get cfg "getMe" {:headers {"accept" "application/json"}})))

(defn- get-updates [cfg]
  (let [body (api-get cfg "getUpdates"
                      (cond-> {:headers {"accept" "application/json"}}
                        (load-offset) (assoc :query-params {:offset (load-offset)})))]
    (if (and (map? body) (true? (:ok body)))
      {:updates (or (:result body) []) :conflict? false}
      {:updates []
       :conflict? (boolean
                   (str/includes? (str/lower-case (str (:description body)))
                                  "conflict"))})))

(defn- attachment-context
  [telegram-cfg saved]
  (when saved
    (let [voice-kind? (contains? #{:voice} (:kind saved))
          body (if voice-kind?
                 (voice/annotation telegram-cfg saved)
                 (or (extract/extract-text
                      saved
                      {:max-chars (or (:attachment-text-max-chars telegram-cfg)
                                      20000)})
                     "[text extraction unavailable; the persisted bytes remain available at the path above]"))]
      (str "\n[Telegram attachment: " (:path saved)
           "; kind=" (name (:kind saved))
           (when-let [mime-type (:mime-type saved)]
             (str "; mime=" mime-type))
           "; bytes=" (:bytes saved) "]"
           "\n[Attachment content begin]\n"
           body
           "\n[Attachment content end]"))))

(defn- handle-edited!
  "Record a bounded edit note. An edit is context, never a new turn."
  [cfg edited]
  (when (and edited (guard/message-allowed? cfg edited))
    (notes/add! (config/home) (group/session-id edited) edited)))

(defn- edit-notes-context
  "Consume pending edit notes for a session; include the newest three."
  [session-id]
  (let [taken (notes/take! (config/home) session-id)]
    (when (seq taken)
      (str "\n[Context: the sender edited earlier messages since your last reply]\n"
           (str/join "\n" (->> taken reverse (take 3) reverse))))))

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

(defn- handle-callback!
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

(defn- process-message!
  [cfg bot message]
  (let [chat-id (get-in message [:chat :id])
        thread-id (group/topic-id message)
        session-id (group/session-id message)
        text (group/normalize-command (or (:text message) (:caption message)) bot)]
    (when (and (seq text)
               (guard/message-allowed? cfg message)
               (or (approval/telegram-approval-reply text)
                   (group/should-respond? cfg bot (assoc message :text text))))
      (let [telegram-cfg (:telegram cfg)]
        (when (:react-ack telegram-cfg true)
          (presence/reaction! telegram-cfg chat-id (:message_id message)))
        (if-let [decision (approval/telegram-approval-reply text)]
          (delivery/send-message!
           telegram-cfg chat-id
           (approval/approval-reply-text
            (approval/resolve! session-id decision)
            decision)
           {:thread-id thread-id})
          (let [edit-context (or (edit-notes-context session-id) "")
                saved (attachment/persist! (config/home) telegram-cfg message)
                input-message (assoc message :text
                                     (str edit-context
                                          text
                                          (attachment-context telegram-cfg saved)))
                _ (when (:typing-indicator telegram-cfg true)
                    (presence/typing! telegram-cfg chat-id {:thread-id thread-id}))
                turn (core/run-turn!
                      (assoc cfg
                             :session/id session-id
                             :session/shared? (group/group-chat? message)
                             :telegram/send-context {:chat-id chat-id
                                                     :thread-id thread-id}
                             :approval/ask
                             (approval/waiting-approver
                              {:session-id session-id
                               :channel :telegram
                               :timeout-ms (or (:approval-timeout-ms telegram-cfg) 30000)
                               :notify #(send-approval-request!
                                         telegram-cfg chat-id thread-id %)}))
                      (group/agent-input bot input-message))]
            (delivery/send-html!
             telegram-cfg chat-id
             (tr/to-html (:assistant/final turn))
             {:thread-id thread-id
              :reply-to-message-id (:message_id message)})))))))

(defn- process-album!
  "Run one turn for a media-group batch. The captioned member activates the
   turn; every member persists, including captionless ones."
  [cfg bot batch]
  (let [telegram-cfg (:telegram cfg)
        messages (:messages batch)
        primary (or (first (filter #(seq (str/trim (str (or (:text %) (:caption %) ""))))
                                   messages))
                    (first messages))
        text (group/normalize-command (or (media/activation-text messages) "") bot)]
    (when (and primary
               (guard/message-allowed? cfg primary)
               (group/should-respond? cfg bot (assoc primary :text text)))
      (let [chat-id (get-in primary [:chat :id])
            thread-id (group/topic-id primary)
            session-id (group/session-id primary)
            edit-context (or (edit-notes-context session-id) "")
            _ (when (:react-ack telegram-cfg true)
                (presence/reaction! telegram-cfg chat-id (:message_id primary)))
            saved (keep #(attachment/persist! (config/home) telegram-cfg %) messages)
            contexts (apply str (map #(attachment-context telegram-cfg %) saved))
            _ (when (:typing-indicator telegram-cfg true)
                (presence/typing! telegram-cfg chat-id {:thread-id thread-id}))
            turn (core/run-turn!
                  (assoc cfg
                         :session/id session-id
                         :session/shared? (group/group-chat? primary)
                         :telegram/send-context {:chat-id chat-id
                                                 :thread-id thread-id}
                         :approval/ask
                         (approval/waiting-approver
                          {:session-id session-id
                           :channel :telegram
                           :timeout-ms (or (:approval-timeout-ms telegram-cfg) 30000)
                           :notify #(send-approval-request!
                                     telegram-cfg chat-id thread-id %)}))
                  (group/agent-input bot
                                     (assoc primary :text (str edit-context text contexts))))]
        (delivery/send-html!
         telegram-cfg chat-id
         (tr/to-html (:assistant/final turn))
         {:thread-id thread-id
          :reply-to-message-id (:message_id primary)})))))

(defn poll-once! []
  (let [cfg (config/load-config)
        telegram-cfg (:telegram cfg)
        token (require-telegram telegram-cfg :token)
        telegram-cfg (assoc telegram-cfg :token token)
        cfg (assoc cfg :telegram telegram-cfg)
        bot (get-bot telegram-cfg)
        {:keys [updates conflict?]} (get-updates telegram-cfg)
        seen (atom (load-seen))
        processed (atom 0)]
    (let [fresh (remove #(contains? @seen (:update_id %)) updates)]
      (doseq [update fresh]
        (when (= :edited (media/update-kind update))
          (handle-edited! cfg (media/edited-message update)))
        (swap! seen conj (:update_id update))
        (save-seen! @seen)
        (save-offset! (inc (:update_id update)))
        (swap! processed inc))
      (doseq [batch (media/batches (filter #(some? (:message %)) fresh))]
        (if (:album? batch)
          (process-album! cfg bot batch)
          (process-message! cfg bot (:message (first (:updates batch))))))
      (doseq [callback (filter #(some? (:callback_query %)) fresh)]
        (handle-callback! cfg callback)))
    {:updates @processed :conflict? conflict?}))

(defn poll-loop!
  "Continuous polling with a sleep between cycles. Stop with ctrl-c.
   A getUpdates conflict (another active client) backs off 5x for one
   cycle instead of hammering the API."
  [& {:keys [interval-ms] :or {interval-ms 2000}}]
  (loop []
    (try
      (let [{:keys [conflict?]} (poll-once!)]
        (Thread/sleep (long (if conflict? (* 5 interval-ms) interval-ms))))
      (catch Exception e
        (println (str "telegram poll error: " (.getMessage e)))
        (Thread/sleep (long interval-ms))))
    (recur)))
