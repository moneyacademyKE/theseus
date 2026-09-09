(ns bb-agent.goal.author-progress
  "Edited-in-place authoring progress message for the owning topic
   (bk-4876 / V1a). The v0.9.0 dogfood's 44-minute authoring was invisible
   in every detached launch (emit nil) and parked the poller in every chat
   launch (inline authoring on the poll loop). With authoring now detached,
   this is the live surface: ONE message in the topic, edited in place as
   the authoring emit channel fires tool calls — the same semantics the
   chat flow message has, for runs that happen outside any turn.
   Pure rendering and throttle decisions are functions; transport arrives
   injected (:send!/:edit!), so tests need no Telegram."
  (:require [bb-agent.config :as config]
            [clojure.string :as str]))

(def default-throttle-ms
  "One edit per 15s reads as live without flooding. An emit that fires
   inside the window still updates state — only the message edit waits."
  15000)

(defn enabled?
  "Config gate :goal/authoring-progress (default on). Absent stays on:
   the progress surface is the point of detaching authoring."
  []
  (-> (config/load-config) :goal/authoring-progress (not= false)))

(defn render
  "Progress message body. Plain text on purpose — no HTML escaping to
   maintain, and tool names are identifiers anyway."
  [{:keys [calls last-tool started-ms]} now-ms]
  (let [mins (quot (max 0 (- now-ms started-ms)) 60000)]
    (str "⚙️ authoring — " calls " tool call"
         (when-not (= 1 calls) "s")
         (when last-tool (str " · last: " last-tool))
         " · " mins "m elapsed")))

(defn should-edit?
  "Edit due? At least throttle-ms since the last edit, and only once a
   call has landed (the initial post already says authoring started)."
  [{:keys [calls last-edit-ms]} now-ms {:keys [throttle-ms]}]
  (and (pos? calls)
       (>= (- now-ms last-edit-ms) (long (or throttle-ms default-throttle-ms)))))

(defn summarize
  "Final-edit text: the first bounded lines of the launch/resume reply —
   an edit-message body must stay short and Telegram-safe."
  [reply]
  (->> (str/split-lines (or reply ""))
       (remove str/blank?)
       (take 3)
       (str/join "\n")))

(defn make-progress-emit
  "Build the authoring progress surface. Returns {:emit fn :finish! fn},
   or nil when the initial post fails — a missing progress message must
   degrade to today's behavior (silent authoring), never block a launch.
   deps: {:send!   (f [text] -> {:message-id n})
          :edit!   (f [message-id text])
          :throttle-ms optional}."
  [{:keys [send! edit!] :as deps}]
  (when-let [{:keys [message-id]} (try (send! "⚙️ authoring goal — progress updates below")
                                       (catch Exception _ nil))]
    (let [state (atom {:calls 0 :last-tool nil
                       :started-ms (System/currentTimeMillis) :last-edit-ms 0})
          bump! (fn [tool]
                  (swap! state (fn [s] (-> s (update :calls inc)
                                           (assoc :last-tool tool)))))
          try-edit! (fn []
                      (let [{:keys [calls last-edit-ms]} @state
                            now (System/currentTimeMillis)]
                        (when (should-edit? @state now deps)
                          (edit! message-id (render @state now))
                          ;; set AFTER the edit succeeds — a failed edit
                          ;; retries on the next emit instead of going dark
                          (swap! state assoc :last-edit-ms now))))]
      {:emit (fn [{:keys [status tool]}]
               (try
                 (when (= status :tool/call)
                   (bump! tool)
                   (try-edit!))
                 (catch Exception _)))
       :finish! (fn [reply]
                  (try (edit! message-id (summarize reply)) (catch Exception _))
                  reply)})))
