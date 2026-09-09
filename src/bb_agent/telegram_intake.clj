(ns bb-agent.telegram-intake
  "Update consumption: everything that turns a Telegram update into
   actions — commands, media/albums, edits, reactions, approval replies,
   and the LLM turn spine. Extracted from telegram.clj (bk-bf8b, LOC
   ceiling); no behavior change. Lifecycle (polling) lives in
   bb-agent.telegram-lifecycle and calls the four public entry points."
  (:require [bb-agent.approval :as approval]
            [bb-agent.autonomy :as autonomy]
            [bb-agent.bot-commands :as bot-commands]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.goal.progress :as goal-progress]
            [bb-agent.session :as session]
            [bb-agent.skill :as skill]
            [bb-agent.telegram-approval-ui :as approval-ui]
            [bb-agent.telegram-attachment :as attachment]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-extract :as extract]
            [bb-agent.telegram-flow :as flow]
            [bb-agent.telegram-group-context :as gctx]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-guard :as guard]
            [bb-agent.telegram-media :as media]
            [bb-agent.telegram-notes :as notes]
            [bb-agent.telegram-presence :as presence]
            [bb-agent.telegram-rich :as tr]
            [bb-agent.telegram-state :as state]
            [bb-agent.telegram-voice :as voice]
            [bb-agent.usage :as usage]
            [clojure.string :as str]))

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

(defn handle-reaction!
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
  "Session-level commands answered without an LLM turn. /goal (with args)
   also routes here so it never reaches the LLM or the skill matcher.
   Build-verb messages auto-route to the goal loop too (owner directive
   2026-09-06) — deterministic, never an LLM judgment."
  [text]
  (cond
    (and text (let [t (str/trim text)]
                (or (str/starts-with? t "/goal ") (= "/goal" t))))
    :goal-request

    (and text (let [t (str/trim text)]
                (or (str/starts-with? t "/model ") (= "/model" t))))
    :model

    :else
    (or (case text
    ("/new" "/reset") :new
    ("/usage" "/stats") :usage
    "/autonomy" :autonomy
    "/goals" :goals
    ("/skills" "/help") :skills
    nil)
      (when (goal-bridge/build-intent? text) :build-goal))))

(defn- skill-command
  "If text starts with /<skill-name>, returns composed prompt with skill body or nil.
   Underscore spellings (the only form the Telegram menu can show for
   hyphenated skills) resolve via bot-commands/resolve-skill."
  [text]
  (when (and text (str/starts-with? (str/trim text) "/"))
    (let [trimmed (str/trim text)
          parts (str/split trimmed #"\s+" 2)
          cmd-name (subs (first parts) 1)
          input (or (second parts) "")
          skills (skill/discover-all-skills)]
      (when-let [matched (bot-commands/resolve-skill skills cmd-name)]
        (skill/compose-prompt matched input)))))

(defn- handle-chat-command
  [cmd session-id text chat-id thread-id]
  (case cmd
    ;; :goal-request/:build-goal are intercepted in the dispatch before this
    ;; runs (V1a: detached authoring, no flow wrapper needed) — deliberately
    ;; NOT cased here so an un-intercepted goal request can never silently
    ;; take the generic-command path.
    :goals (or (when-let [lines (goal-progress/list-goals)]
                 (str "🎯 Goals:\n" (str/join "\n" lines)))
               "No goals yet — /goal <what to build> launches one.")
    :new (do (session/reset! session-id)
             "🧹 Session reset — fresh context from here.")
    :usage (let [r (usage/report)
                 by-provider (->> (:by-provider r)
                                  (map (fn [[p v]] (str (name p) ": " (:tokens/total v) " tok")))
                                  (str/join ", "))]
             (str "📊 Usage: " (:usage/events r) " events, "
                  (:tokens/total r) " tokens"
                  (format ", ~$%.4f" (double (or (:cost/estimate-usd r) 0)))
                  (when (seq by-provider) (str " (" by-provider ")"))))
    :autonomy (autonomy/report (autonomy/granted-tier)
                               (autonomy/load-ledger))
    :model (let [cfg (config/load-config)
                 arg (second (str/split (str/trim (or text "")) #"\s+" 2))]
             (if (str/blank? arg)
               (let [fallbacks (map :model (:provider/fallbacks cfg))
                     catalog (distinct (concat [(:model cfg)] fallbacks (:models cfg)))]
                 (str "🤖 Model: " (:model cfg)
                      (when (:vision-model cfg) (str "\n👁 Vision: " (:vision-model cfg)))
                      "\n\nPicker:\n"
                      (str/join "\n"
                                (map (fn [m] (str "• " m
                                                  (when (= m (:model cfg)) " ← current")
                                                  (when (some #{m} fallbacks) " (fallback)")))
                                     catalog))
                      "\n\n/model <provider/name> to switch — persists, next message uses it."
                      (when-not (seq (:models cfg))
                        "\nAdd :models [\"provider/name\" …] to config.edn to grow this list.")))
               (if-not (str/includes? arg "/")
                 (str "🚫 Model names look like provider/name — got: " arg ". No change.")
                 (do (config/write-config! (assoc cfg :model arg))
                     (str "🤖 Model switched: " (:model cfg) " → " arg
                          "\nPersisted to config — the next message runs on it.")))))
    :skills (let [skills (skill/discover-all-skills)
                  native "⌨️ Commands: /new /reset /usage /autonomy /model /goal <spec> /goals /skills"
                  lines (map (fn [{:keys [name description]}]
                               (str "• /" name " — "
                                    (let [d (str/replace (or description "") #"\s+" " ")]
                                      (if (> (count d) 60) (str (subs d 0 60) "…") d))))
                             skills)]
              (if (seq lines)
                (str native "\n🧩 Skills (" (count skills) ") — /<name> <input> runs one:\n"
                     (str/join "\n" lines))
                (str native "\nNo skills found.")))))

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

(defn- approval-ask
  "The approval gate wired for one Telegram chat/topic."
  [telegram-cfg session-id chat-id thread-id]
  (approval/waiting-approver
   {:session-id session-id
    :channel :telegram
    ;; Groups breathe slower than DMs: a human sees the keyboard, finishes
    ;; their coffee, types "you have my approval" — 30s was a denial machine
    ;; (2026-09-08 approval-deadlock). 10 minutes by default in groups.
    :timeout-ms (or (:approval-timeout-ms telegram-cfg)
                    (if (or thread-id (neg? (long chat-id))) 600000 30000))
    :notify #(approval-ui/send-approval-request! telegram-cfg chat-id thread-id %)
    :on-expire (fn [pending sent]
                 (when-let [mid (:message-id sent)]
                   (approval-ui/expire-keyboard! telegram-cfg chat-id mid
                                                 (:approval/id pending))))}))

(defn- rerun-edited-reply!
  "An edit to a message we already answered re-runs the turn and replaces
   the prior reply in place — a stale answer next to an edited question
   is a lie. Failure surfaces through the same notice path as any turn."
  [cfg bot edited prior-reply-id]
  (let [telegram-cfg (:telegram cfg)
        chat-id (get-in edited [:chat :id])
        thread-id (group/topic-id edited)
        session-id (group/session-id edited)
        turn-flow (flow/make-flow)]
    (try
      (let [text (group/normalize-command
                  (or (:text edited) (:caption edited) "") bot)
            turn (presence/with-typing-heartbeat
                  telegram-cfg chat-id {:thread-id thread-id}
                  (fn []
                    (core/run-turn!
                     (assoc cfg
                            :session/id session-id
                            :session/shared? (group/group-chat? edited)
                            :status/emit (flow/flow-emit
                                          turn-flow telegram-cfg chat-id thread-id)
                            :approval/ask (approval-ask telegram-cfg session-id chat-id thread-id))
                     (group/agent-input bot (assoc edited :text text)))))]
        (flow/settle! turn-flow telegram-cfg chat-id true)
        (let [chunks (tr/split-message (tr/to-html (:assistant/final turn)))]
          (delivery/edit-message-text! telegram-cfg chat-id prior-reply-id
                                       (first chunks) {:parse-mode "HTML"})))
      (catch Exception e
        (flow/settle! turn-flow telegram-cfg chat-id false)
        (notify-turn-failure! telegram-cfg chat-id thread-id (:message_id edited) e)))))

(defn handle-edited!
  "Route an edit: a message with a recorded prior reply re-runs its turn
   and updates that reply in place; anything else stays a bounded context
   note for a future turn."
  [cfg bot edited]
  (when (and edited (guard/message-allowed? cfg edited))
    (let [chat-id (get-in edited [:chat :id])
          prior-reply (state/lookup-reply chat-id (:message_id edited))]
      (if (and prior-reply
               (seq (str/trim (str (or (:text edited) (:caption edited) "")))))
        (rerun-edited-reply! cfg bot edited prior-reply)
        (notes/add! (config/home) (group/session-id edited) edited)))))

(defn- run-telegram-turn!
  "Shared spine of message and album turns: typing heartbeat → run-turn! →
   settle(true) → HTML delivery → reply recording. Input text and :user/images
   travel on input-message; reply-to-id is the message being answered."
  [cfg bot telegram-cfg turn-flow input-message reply-to-id]
  (let [chat-id (get-in input-message [:chat :id])
        thread-id (group/topic-id input-message)
        session-id (group/session-id input-message)
        turn (presence/with-typing-heartbeat
              telegram-cfg chat-id {:thread-id thread-id}
              (fn []
                (core/run-turn!
                 (assoc cfg
                        :session/id session-id
                        :session/shared? (group/group-chat? input-message)
                        :status/emit (flow/flow-emit turn-flow telegram-cfg chat-id thread-id)
                        :telegram/send-context {:chat-id chat-id
                                                :thread-id thread-id}
                        :user/images (:user/images input-message)
                        :approval/ask (approval-ask telegram-cfg session-id chat-id thread-id))
                 (group/agent-input bot input-message))))]
    (flow/settle! turn-flow telegram-cfg chat-id true)
    (let [delivered (delivery/send-html!
                     telegram-cfg chat-id
                     (tr/to-html (:assistant/final turn))
                     {:thread-id thread-id
                      :reply-to-message-id reply-to-id})]
      (when (= 1 (count delivered))
        (state/record-reply! chat-id reply-to-id
                             (:message-id (first delivered))))
      ;; 2026-09-08 amnesia fix: Telegram never echoes a bot's own messages
      ;; back via getUpdates — record the answer in the topic buffer or the
      ;; agent forgets everything it itself said.
      (when (and (group/group-chat? input-message)
                 (seq (str (:assistant/final turn))))
        (gctx/record! chat-id
                      {:message-id (or (:message-id (first delivered)) 0)
                       :from "assistant"
                       :text (:assistant/final turn)}
                      :thread-id thread-id))
      delivered)))

(defn process-message!
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
            (let [reply-text (if (contains? #{:goal-request :build-goal} cmd)
                               ;; V1a: goal launches return instantly (authoring
                               ;; is detached into goal_launch.bb with its own
                               ;; progress message) — no turn-scoped flow needed
                               (case cmd
                                 :goal-request (goal-bridge/handle-request! text chat-id thread-id)
                                 :build-goal (goal-bridge/route-build-request! text chat-id thread-id)
                                 nil)
                               (handle-chat-command cmd session-id text chat-id thread-id))
                  sent (delivery/send-message!
                        telegram-cfg chat-id reply-text
                        {:thread-id thread-id
                         :reply-to-message-id (:message_id message)})]
              ;; B2: command replies (goals/goal/usage/autonomy…) rode a
              ;; different send path and never reached the replies ledger,
              ;; so "did Eileen answer?" was unauditable for exactly the
              ;; messages that matter most.
              (when (and (map? sent) (:message-id sent))
                (state/record-reply! chat-id (:message_id message) (:message-id sent))
                ;; 2026-09-08 amnesia fix: a command reply must be memory,
                ;; not just an audit id — record it in the topic buffer and
                ;; as a durable session turn (goal launches included), or
                ;; follow-ups find nothing.
                (when (group/group-chat? message)
                  (gctx/record! chat-id
                                {:message-id (:message-id sent)
                                 :from "assistant"
                                 :text (str reply-text)}
                                :thread-id thread-id))
                (when-not (= :new cmd)
                  (session/append-turn!
                   session-id {:session/id session-id
                               :user/input text
                               :assistant/final (str reply-text)
                               :source :command
                               :created/at (str (java.time.Instant/now))})))
              sent)
          (let [composed-text (or (skill-command text) text)
                edit-context (or (edit-notes-context session-id) "")
                history-context (if (and (:group-context telegram-cfg true)
                                         (group/group-chat? message))
                                  (or (gctx/history-block chat-id (:message_id message)
                                                          :size (or (:group-context-size telegram-cfg) 30)
                                                          :thread-id thread-id)
                                      "")
                                  "")
                input-message (assoc message :text
                                     (str history-context
                                          edit-context
                                          composed-text
                                          (attachment-context telegram-cfg saved)))

                input-message (if (and saved
                                       (str/starts-with?
                                        (or (:mime-type saved) "")
                                        "image/"))
                                (assoc input-message :user/images
                                       [{:path (:path saved)
                                         :mime-type (:mime-type saved)}])
                                input-message)]
            (run-telegram-turn! cfg bot telegram-cfg turn-flow
                                input-message (:message_id message))))))

      (catch Exception e
        (flow/settle! turn-flow telegram-cfg chat-id false)
        (notify-turn-failure! telegram-cfg chat-id thread-id (:message_id message) e)))))


(defn process-album!
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
            session-id (group/session-id primary)
            edit-context (or (edit-notes-context session-id) "")
            history-context (if (:group-context telegram-cfg true)
                              (or (gctx/history-block chat-id (:message_id primary)
                                                      :size (or (:group-context-size telegram-cfg) 30)
                                                      :thread-id (group/topic-id primary))
                                  "")
                              "")
            _ (when (:react-ack telegram-cfg true)
                (presence/reaction! telegram-cfg chat-id (:message_id primary)))
            {:keys [persisted skipped]} (attachment/persist-batch! (config/home) telegram-cfg messages)
            contexts (str (apply str (map #(attachment-context telegram-cfg %) persisted))
                          (when (pos? skipped)
                            (str "\n[" skipped " attachment(s) skipped: cumulative turn media limit exceeded]")))

            input-message (assoc primary :text
                                 (str history-context edit-context text contexts))]
        (run-telegram-turn! cfg bot telegram-cfg turn-flow
                            input-message (:message_id primary))))

      (catch Exception e
        (flow/settle! turn-flow telegram-cfg (get-in primary [:chat :id]) false)
        (notify-turn-failure! telegram-cfg (get-in primary [:chat :id])
                              (group/topic-id primary) (:message_id primary) e)))))
