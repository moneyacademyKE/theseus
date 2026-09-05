(ns bb-agent.telegram
  (:require [babashka.http-client :as http]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.telegram-approval-ui :as approval-ui]
            [bb-agent.telegram-attachment :as attachment]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-extract :as extract]
            [bb-agent.telegram-flow :as flow]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-group-context :as gctx]
            [bb-agent.telegram-guard :as guard]
            [bb-agent.telegram-media :as media]
            [bb-agent.telegram-notes :as notes]
            [bb-agent.telegram-presence :as presence]
            [bb-agent.telegram-rich :as tr]
            [bb-agent.telegram-state :as state]
            [bb-agent.telegram-voice :as voice]
            [bb-agent.session :as session]
            [bb-agent.skill :as skill]
            [bb-agent.usage :as usage]
            [cheshire.core :as json]
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

(defn- api-get [cfg method opts]
  (let [response (http/get (api-url cfg method) (assoc opts :throw false))]
    (json/parse-string (:body response) keyword)))

(defn- get-bot [cfg]
  (:result (api-get cfg "getMe" {:headers {"accept" "application/json"}})))

(defn- get-updates [cfg]
  (let [body (api-get cfg "getUpdates"
                      (cond-> {:headers {"accept" "application/json"}}
                        (state/load-offset) (assoc :query-params {:offset (state/load-offset)})
                        (:reactions-context cfg true)
                        (assoc-in [:query-params :allowed_updates]
                                  (json/generate-string
                                   ["message" "edited_message" "channel_post"
                                    "edited_channel_post" "callback_query"
                                    "message_reaction"]))))]
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

(defn- handle-reaction!
  "Record a bounded reaction note (👍 on message #N) as session context —
   a reaction is signal, never a turn."
  [cfg reaction]
  (when (and reaction (:reactions-context (:telegram cfg) true))
    (let [msg {:chat (:chat reaction)
               :message_thread_id (:message_thread_id reaction)
               :from (:user reaction)}
          sender (or (get-in reaction [:user :username])
                     (get-in reaction [:user :first_name])
                     "unknown")
          emojis (->> (:new_reaction reaction)
                      (keep :emoji)
                      (str/join " "))]
      (when (and (seq emojis)
                 (guard/message-allowed? cfg msg))
        (notes/add-raw! (config/home) (group/session-id msg)
                        (str "[Telegram reaction from " sender ": " emojis
                             " on message #" (:message_id reaction) "]"))))))

(defn- edit-notes-context
  "Consume pending edit notes for a session; include the newest three."
  [session-id]
  (let [taken (notes/take! (config/home) session-id)]
    (when (seq taken)
      (str "\n[Context: the sender edited earlier messages since your last reply]\n"
           (str/join "\n" (->> taken reverse (take 3) reverse))))))

(defn- chat-command
  "Session-level commands answered without an LLM turn."
  [text]
  (case text
    ("/new" "/reset") :new
    ("/usage" "/stats") :usage
    nil))

(defn- skill-command
  "If text starts with /<skill-name>, returns composed prompt with skill body or nil."
  [text]
  (when (and text (str/starts-with? (str/trim text) "/"))
    (let [trimmed (str/trim text)
          parts (str/split trimmed #"\s+" 2)
          cmd-name (subs (first parts) 1)
          input (or (second parts) "")
          skills (skill/discover-all-skills)]
      (when-let [matched (first (filter #(= cmd-name (:name %)) skills))]
        (skill/compose-prompt matched input)))))

(defn- handle-chat-command
  [cmd session-id]
  (case cmd
    :new (do (session/reset! session-id)
             "🧹 Session reset — fresh context from here.")
    :usage (let [r (usage/report)
                 by-provider (->> (:by-provider r)
                                  (map (fn [[p v]] (str (name p) ": " (:tokens/total v) " tok")))
                                  (str/join ", "))]
             (str "📊 Usage: " (:usage/events r) " events, "
                  (:tokens/total r) " tokens"
                  (format ", ~$%.4f" (double (or (:cost/estimate-usd r) 0)))
                  (when (seq by-provider) (str " (" by-provider ")"))))))

(defn- notify-turn-failure!
  "A dead turn must never be silent: swap the ack reaction to a failure
   signal and send one bounded error reply. The notice itself failing
   degrades to a log line, never a poll crash."
  [telegram-cfg chat-id thread-id message-id e]
  (binding [*out* *err*]
    (println (str "telegram turn failed: " (.getMessage e))))
  (try
    (when (:react-ack telegram-cfg true)
      (presence/reaction! telegram-cfg chat-id message-id {:emoji "🤯"}))
    (let [m (or (.getMessage e) (str (type e)))
          summary (if (> (count m) 300) (str (subs m 0 300) "…") m)]
      (delivery/send-message! telegram-cfg chat-id
                              (str "⚠️ Turn failed: " summary)
                              {:thread-id thread-id
                               :reply-to-message-id message-id}))
    (catch Exception notice-failure
      (binding [*out* *err*]
        (println (str "telegram failure notice failed: "
                      (.getMessage notice-failure)))))))

(defn- process-message!
  [cfg bot message]
  (let [chat-id (get-in message [:chat :id])
        thread-id (group/topic-id message)
        session-id (group/session-id message)
        text (group/normalize-command (or (:text message) (:caption message)) bot)
        telegram-cfg (:telegram cfg)
        authorized? (guard/message-allowed? cfg message)
        ;; Authorized media persists even without text — voice notes and bare
        ;; photos are real inputs, not noise (and voice gets transcribed by
        ;; attachment-context via stt-bin).
        saved (when (and authorized? (attachment/persistable? message))
                (attachment/persist! (config/home) telegram-cfg message))
        turn-flow (flow/make-flow)]
    (try
      (when (and authorized?
               (or (seq text) saved)
               (or (approval/telegram-approval-reply text)
                   (group/should-respond?
                    cfg bot (assoc message :text (or (not-empty text) "[media]")))))
      (when (:react-ack telegram-cfg true)
          (presence/reaction! telegram-cfg chat-id (:message_id message)))
        (if-let [decision (approval/telegram-approval-reply text)]
          (delivery/send-message!
           telegram-cfg chat-id
           (approval/approval-reply-text
            (approval/resolve! session-id decision)
            decision)
           {:thread-id thread-id})
          (if-let [cmd (chat-command text)]
            (delivery/send-message!
             telegram-cfg chat-id
             (handle-chat-command cmd session-id)
             {:thread-id thread-id
              :reply-to-message-id (:message_id message)})
          (let [composed-text (or (skill-command text) text)
                edit-context (or (edit-notes-context session-id) "")
                history-context (if (and (:group-context telegram-cfg true)
                                         (group/group-chat? message))
                                  (or (gctx/history-block chat-id (:message_id message)
                                                          :size (or (:group-context-size telegram-cfg) 30))
                                      "")
                                  "")
                input-message (assoc message :text
                                     (str history-context
                                          edit-context
                                          composed-text
                                          (attachment-context telegram-cfg saved)))
                turn (presence/with-typing-heartbeat
                      telegram-cfg chat-id {:thread-id thread-id}
                      (fn []
                        (core/run-turn!
                         (assoc cfg
                                :session/id session-id
                                :session/shared? (group/group-chat? message)
                                :status/emit (flow/flow-emit turn-flow telegram-cfg chat-id thread-id)
                                :telegram/send-context {:chat-id chat-id
                                                        :thread-id thread-id}
                             :user/images (when (and saved
                                                     (str/starts-with?
                                                      (or (:mime-type saved) "")
                                                      "image/"))
                                            [{:path (:path saved)
                                              :mime-type (:mime-type saved)}])
                             :approval/ask
                             (approval/waiting-approver
                              {:session-id session-id
                               :channel :telegram
                               :timeout-ms (or (:approval-timeout-ms telegram-cfg) 30000)
                               :notify #(approval-ui/send-approval-request!
                                         telegram-cfg chat-id thread-id %)
                               :on-expire (fn [pending sent]
                                            (when-let [mid (:message-id sent)]
                                              (approval-ui/expire-keyboard!
                                               telegram-cfg chat-id mid
                                               (:approval/id pending))))}))
                         (group/agent-input bot input-message))))]
            (flow/settle! turn-flow telegram-cfg chat-id true)
            (delivery/send-html!
             telegram-cfg chat-id
             (tr/to-html (:assistant/final turn))
              {:thread-id thread-id
               :reply-to-message-id (:message_id message)})))))
      (catch Exception e
        (flow/settle! turn-flow telegram-cfg chat-id false)
        (notify-turn-failure! telegram-cfg chat-id thread-id (:message_id message) e)))))

(defn- process-album!
  "Run one turn for a media-group batch. The captioned member activates the
   turn; every member persists, including captionless ones."
  [cfg bot batch]
  (let [telegram-cfg (:telegram cfg)
        messages (:messages batch)
        primary (or (first (filter #(seq (str/trim (str (or (:text %) (:caption %) ""))))
                                   messages))
                    (first messages))
        text (group/normalize-command (or (media/activation-text messages) "") bot)
        turn-flow (flow/make-flow)]
    (try
      (when (and primary
               (guard/message-allowed? cfg primary)
               (group/should-respond? cfg bot (assoc primary :text text)))
      (let [chat-id (get-in primary [:chat :id])
            thread-id (group/topic-id primary)
            session-id (group/session-id primary)
            edit-context (or (edit-notes-context session-id) "")
            history-context (if (:group-context telegram-cfg true)
                              (or (gctx/history-block chat-id (:message_id primary)
                                                      :size (or (:group-context-size telegram-cfg) 30))
                                  "")
                              "")
            _ (when (:react-ack telegram-cfg true)
                (presence/reaction! telegram-cfg chat-id (:message_id primary)))
            {:keys [persisted skipped]} (attachment/persist-batch! (config/home) telegram-cfg messages)
            contexts (str (apply str (map #(attachment-context telegram-cfg %) persisted))
                          (when (pos? skipped)
                            (str "\n[" skipped " attachment(s) skipped: cumulative turn media limit exceeded]")))
            turn (presence/with-typing-heartbeat
                  telegram-cfg chat-id {:thread-id thread-id}
                  (fn []
                    (core/run-turn!
                     (assoc cfg
                            :session/id session-id
                            :session/shared? (group/group-chat? primary)
                            :status/emit (flow/flow-emit turn-flow telegram-cfg chat-id thread-id)
                            :telegram/send-context {:chat-id chat-id
                                                    :thread-id thread-id}
                            :approval/ask
                            (approval/waiting-approver
                             {:session-id session-id
                              :channel :telegram
                              :timeout-ms (or (:approval-timeout-ms telegram-cfg) 30000)
                              :notify #(approval-ui/send-approval-request!
                                        telegram-cfg chat-id thread-id %)
                              :on-expire (fn [pending sent]
                                           (when-let [mid (:message-id sent)]
                                             (approval-ui/expire-keyboard!
                                              telegram-cfg chat-id mid
                                              (:approval/id pending))))}))
                     (group/agent-input bot
                                        (assoc primary :text (str history-context edit-context text contexts))))))]
        (flow/settle! turn-flow telegram-cfg chat-id true)
        (delivery/send-html!
         telegram-cfg chat-id
         (tr/to-html (:assistant/final turn))
         {:thread-id thread-id
          :reply-to-message-id (:message_id primary)})))
      (catch Exception e
        (flow/settle! turn-flow telegram-cfg (get-in primary [:chat :id]) false)
        (notify-turn-failure! telegram-cfg (get-in primary [:chat :id])
                              (group/topic-id primary) (:message_id primary) e)))))

(defn poll-once! []
  (let [cfg (config/load-config)
        telegram-cfg (:telegram cfg)
        token (require-telegram telegram-cfg :token)
        telegram-cfg (assoc telegram-cfg :token token)
        cfg (assoc cfg :telegram telegram-cfg)
        bot (get-bot telegram-cfg)
        {:keys [updates conflict?]} (get-updates telegram-cfg)
        seen (atom (state/load-seen))
        processed (atom 0)]
    (let [fresh (remove #(contains? @seen (:update_id %)) updates)]
      (doseq [update fresh]
        (swap! seen conj (:update_id update))
        (state/save-seen! @seen)
        (state/save-offset! (inc (:update_id update)))
        (swap! processed inc))
      ;; Dispatch in update order: reactions and edits land as notes for the
      ;; NEXT turn, not one that already ran. Consecutive message runs still
      ;; batch as albums.
      (doseq [chunk (partition-by #(let [k (media/update-kind %)]
                                     (if (= :message k) :messages k))
                                  fresh)]
        (if (= :message (media/update-kind (first chunk)))
          (doseq [batch (media/batches chunk)]
            ;; Record every observed group message so future turns have
            ;; conversational context — independent of whether we respond.
            (when (:group-context telegram-cfg true)
              (doseq [u (:updates batch)]
                (let [m (:message u)
                      cid (get-in m [:chat :id])]
                  (when (and cid (neg? (long cid)))
                    (gctx/record! cid {:message-id (:message_id m)
                                       :from (or (get-in m [:from :first_name])
                                                 (get-in m [:from :username]))
                                       :text (or (:text m) (:caption m))}
                                  :size (or (:group-context-size telegram-cfg) 30))))))
            (if (:album? batch)
              (process-album! cfg bot batch)
              (process-message! cfg bot (:message (first (:updates batch))))))
          (doseq [u chunk]
            (case (media/update-kind u)
              :edited (handle-edited! cfg (media/edited-message u))
              :reaction (handle-reaction! cfg (:message_reaction u))
              (when (:callback_query u)
                (approval-ui/handle-callback! cfg u)))))))
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
