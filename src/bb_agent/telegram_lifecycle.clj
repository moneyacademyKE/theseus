(ns bb-agent.telegram-lifecycle
  "Polling lifecycle: the Bot API GET transport, the getUpdates loop with
   its dedupe/offset ledger, the durable outbox drain, the SIGTERM hook,
   and boot ceremony. Extracted from telegram.clj (bk-bf8b, LOC ceiling);
   no behavior change. Update consumption lives in bb-agent.telegram-intake."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.bot-commands :as bot-commands]
            [bb-agent.config :as config]
            [bb-agent.digest :as digest]
            [bb-agent.doctor :as doctor]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.goal.outcomes :as goal-outcomes]
            [bb-agent.goal.registry :as goal-registry]
            [bb-agent.log-cap :as log-cap]
            [bb-agent.outbox :as outbox]
            [bb-agent.telegram-approval-ui :as approval-ui]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-group-context :as gctx]
            [bb-agent.telegram-group :as group]
            [bb-agent.telegram-intake :as intake]
            [bb-agent.telegram-media :as media]
            [bb-agent.telegram-state :as state]
            [bb-agent.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- api-url [{:keys [base-url token]} method]
  (str (str/replace (or base-url "https://api.telegram.org") #"/+$" "")
       "/bot" token "/" method))

(defn- api-get [cfg method opts]
  ;; Bounded calls: an un-timed http/get hangs forever on a dead connection
  ;; and the poll-loop wedges silently (observed 2026-09-06 under launchd).
  (let [response (http/get (api-url cfg method)
                           (assoc opts :throw false
                                  :timeout (or (:timeout-ms cfg) 15000)))]
    (json/parse-string (:body response) keyword)))

(defn- api-get! [cfg method opts]
  ;; api-get that THROWS on non-ok bodies — for callers that must not
  ;; mistake an API failure for "no updates".
  (let [body (api-get cfg method opts)]
    (if (and (map? body) (true? (:ok body)))
      body
      (throw (ex-info (str "telegram api error: " (:error_code body)
                           " " (:description body))
                      {:method method :body body})))))

(def ^:private last-poll-error (atom nil))

(defn- report-poll-error!
  "Print an API failure once per distinct error (and a recovery line when it
  clears). Failures here previously vanished into an empty updates list —
  the bot sat deaf with a healthy-looking process for 18h (2026-09-05/06)."
  [body]
  (let [desc (str "status " (:error_code body) ": " (:description body))]
    (when (not= desc @last-poll-error)
      (reset! last-poll-error desc)
      (println (str "telegram api error: " desc))
      (flush))))

(defn- clear-poll-error! []
  (when (some? @last-poll-error)
    (reset! last-poll-error nil)
    (println "telegram api recovered")
    (flush)))

(defn- get-bot [cfg]
  (:result (api-get! cfg "getMe" {:headers {"accept" "application/json"}})))

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
      (do (clear-poll-error!)
          {:updates (or (:result body) []) :conflict? false})
      (do (report-poll-error! body)
          {:updates []
           :conflict? (boolean
                       (str/includes? (str/lower-case (str (:description body)))
                                      "conflict"))}))))

(defn- mark-done!
  "Per-unit durability ledger, written AFTER the unit dispatches (audit H1).
   seen is the dedupe set, offset is the getUpdates confirmation. Turn
   handlers own their errors (intake/notify-turn-failure!), so done here
   means the handler ran — a crash before this line costs a redelivered
   update, never a silently lost one."
  [seen processed updates]
  (when (seq updates)
    (let [last-id (-> updates last :update_id)]
      (doseq [u updates]
        (swap! seen conj (:update_id u))
        (swap! processed inc))
      (state/save-seen! @seen)
      (state/save-offset! (inc last-id)))))

(defn- drain-outbox!
  "Re-attempt every persisted-but-unconfirmed outbound message through the
   same result-aware delivery ladder as a live send. Runs at boot and every
   poll cycle: a kill between enqueue and confirmed send replays from disk
   instead of vanishing (the 2026-09-08 409 casualty)."
  [telegram-cfg]
  (outbox/drain!
   telegram-cfg
   (fn [record]
     (delivery/post-api telegram-cfg (:method record)
                        {:headers {"content-type" "application/json"}
                         :body (json/generate-string (:params record))}
                        (:routing record)
                        delivery/default-runtime))))

(defn poll-once! []
  (let [cfg (config/load-config)
        telegram-cfg (:telegram cfg)
        _ (let [problems (config/validate-telegram telegram-cfg)]
            (when (seq problems)
              (throw (ex-info "Telegram config invalid" {:errors problems}))))
        cfg (assoc cfg :telegram telegram-cfg)
        bot (get-bot telegram-cfg)
        {:keys [updates conflict?]} (get-updates telegram-cfg)
        seen (atom (state/load-seen))
        processed (atom 0)]
    ;; Ledger writes happen per-unit AFTER dispatch (audit H1) — never as a
    ;; pre-pass. Advancing the offset before the turns run meant a crash
    ;; mid-dispatch silently lost every unprocessed update.
    (let [fresh (remove #(contains? @seen (:update_id %)) updates)]
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
                                  :size (or (:group-context-size telegram-cfg) 30)
                                  :thread-id (group/topic-id m))))))
            (if (:album? batch)
              (intake/process-album! cfg bot batch)
              (intake/process-message! cfg bot (:message (first (:updates batch)))))
            (mark-done! seen processed (:updates batch)))
          (doseq [u chunk]
            (case (media/update-kind u)
              :edited (intake/handle-edited! cfg bot (media/edited-message u))
              :reaction (intake/handle-reaction! cfg (:message_reaction u))
              (when (:callback_query u)
                (approval-ui/handle-callback! cfg u)))
            (mark-done! seen processed [u])))))
    ;; 2026-09-08: watcher/recovery verdicts land in goals/<name>/outcome.edn
    ;; — drain them into session turns every cycle so finished work becomes
    ;; memory instead of a message that evaporates. A drain failure must
    ;; never kill polling.
    (try (goal-outcomes/drain-outcomes!) (catch Exception _ nil))
    ;; 2026-09-08 durable outbox: any send that died mid-flight (process
    ;; kill, provider blackout) is retried from disk every cycle. Same rule
    ;; as above — a drain failure must never kill polling.
    (try (drain-outbox! telegram-cfg) (catch Exception _ nil))
    ;; V7: daily owner digest — the poller cycle is the clock (drift OK,
    ;; silence isn't). A digest failure must never kill polling either.
    (try (digest/maybe-digest!
          telegram-cfg
          {:today (str (java.time.LocalDate/now))
           :hour (.getHour (java.time.LocalTime/now))})
         (catch Exception _ nil))
    {:updates @processed :conflict? conflict?}))

(defn- install-shutdown-hook!
  "SIGTERM (launchd kickstart, system stop) used to amputate the poller
   mid-cycle: in-flight sends died and the getUpdates request stayed held,
   so the replacement process ate a 409 Conflict and one delivery was lost
   (2026-09-08). The hook flips :running? off, then gives an in-flight cycle
   up to `grace-ms` to finish before the VM halts. A sleeping poller exits
   immediately; a working one gets bounded grace and no more.

   No final drain here: the outbox drains every cycle during life and again
   on the next boot, so a hook-side drain would duplicate responsibility for
   zero additional guarantee."
  [lifecycle grace-ms]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    (fn []
      (swap! lifecycle assoc :running? false)
      (let [deadline (+ (System/currentTimeMillis) grace-ms)]
        (while (and (:in-cycle? @lifecycle)
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50)))
      (println (str "poller: graceful shutdown (in-flight-cycle="
                    (boolean (:in-cycle? @lifecycle)) ") @ "
                    (java.time.Instant/now)))
      (flush))))
  lifecycle)

(defn- instance-lock-path []
  (str (fs/path (config/home) "state" "poller.pid")))

(defn acquire-instance-lock!
  "The poller is a singleton: two live pollers fight over getUpdates and
   both lose — 409 wars, orphaned deliveries, the v1.0.0-cut orphan race.
   bk-8ba2: the lock is a PID file (state/poller.pid). A live foreign PID
   refuses the boot; a stale file (dead PID) is taken over. Returns true
   when this process holds the lock after the call."
  []
  (let [path (instance-lock-path)
        my-pid (.pid (java.lang.ProcessHandle/current))]
    (if (and (fs/exists? path)
             (let [pid (parse-long (str/trim (slurp path)))]
               (and pid (not= pid my-pid) (goal-registry/pid-alive? pid))))
      false
      (do (fs/create-dirs (fs/parent path))
          (spit path (str my-pid))
          true))))

(defn run-poll-cycles!
  "The poll-loop skeleton with its body injected, so the lifecycle is
   testable without the Bot API. Runs `body-fn` until :running? flips false
   (the shutdown hook does that on SIGTERM); :in-cycle? is true exactly
   while a body run is in flight so the hook waits for the current cycle
   instead of cutting it.

   Failure posture (bk-9394): a throwing body backs off geometrically —
   interval × 1, 2, 4, 8, capped at 16× — so an outage doesn't hot-loop
   against a dead network (one 2026-09-10 outage produced 2,400+ identical
   error lines). Logging is throttled: the 1st failure, every 30th, and one
   recovery line when a cycle succeeds again. A getUpdates conflict keeps
   its fixed 5× backoff. Returns the completed cycle count."
  [lifecycle interval-ms body-fn]
  (loop [cycles 0 failures 0]
    (if-not (:running? @lifecycle)
      cycles
      (let [result (try
                     (swap! lifecycle assoc :in-cycle? true)
                     (body-fn)
                     (catch Exception e
                       (let [n (inc failures)]
                         (when (or (= n 1) (zero? (mod n 30)))
                           (println (str "telegram poll error #" n " ["
                                         (.getName (.getClass e)) "] "
                                         (.getMessage e)
                                         (when-let [d (ex-data e)]
                                           (str " data " (pr-str d)))
                                         " @ " (java.time.Instant/now)))
                           (flush)))
                       ::failed))]
        (swap! lifecycle assoc :in-cycle? false)
        (cond
          (= ::failed result)
          (do (Thread/sleep (long (* interval-ms
                                     (bit-shift-left 1 (min failures 4)))))
              (recur (inc cycles) (inc failures)))

          (:conflict? result)
          (do (when (pos? failures)
                (println (str "telegram poll recovered after " failures
                              " consecutive error(s) @ " (java.time.Instant/now)))
                (flush))
            (Thread/sleep (long (* 5 interval-ms)))
            (recur (inc cycles) 0))

          :else
          (do (when (pos? failures)
                (println (str "telegram poll recovered after " failures
                              " consecutive error(s) @ " (java.time.Instant/now)))
                (flush))
            (Thread/sleep (long interval-ms))
            (recur (inc cycles) 0)))))))

(defn poll-loop!
  "Continuous polling with a sleep between cycles. Stop with ctrl-c.
   A getUpdates conflict (another active client) backs off 5x for one
   cycle instead of hammering the API. Registers the Telegram command
   menu at boot — failure prints one line and never blocks polling."
  [& {:keys [interval-ms] :or {interval-ms 2000}}]
  ;; bk-8ba2: singleton gate BEFORE any boot side effects — a second poller
  ;; must fail fast, not discover the 409 war after registering commands.
  (when-not (acquire-instance-lock!)
    (binding [*out* *err*]
      (println (str "telegram: another live poller holds "
                    (instance-lock-path) " — refusing to start")))
    (flush)
    (System/exit 1))
  (let [boot-cfg (config/load-config)]
    ;; bk-e6ff: cap the launchd log BEFORE boot output lands — in-place
    ;; truncation, never a rename (the fd stays on the inode).
    (log-cap/cap-log!
     (str (fs/path (config/home) "state" "telegram-poll.log"))
     (get-in boot-cfg [:telegram :log-max-bytes] log-cap/default-max-bytes)
     (get-in boot-cfg [:telegram :log-keep-bytes] log-cap/default-keep-bytes))
    ;; bk-173e: the boot log answers "what version is running" — no ps, no prayer.
    (println (str "theseus " version/v " booting"))
    (bot-commands/register-safely!)
    ;; bk-1825: doctor-lite at boot. Print every check; on errors, shout —
    ;; log line + owner DM via the durable outbox. Never throws: a health
    ;; check bug must not block polling.
    (try
      (let [checks (doctor/run-checks)]
        (doseq [c checks]
          (println (str "boot health " (doctor/format-check c))))
        (when-let [summary (doctor/degraded-summary checks)]
          (println summary)
          (flush)
          (when-let [chat-id (get-in boot-cfg [:notify :chat-id])]
            (try (delivery/send-message! (:telegram boot-cfg) chat-id summary)
                 (catch Exception e
                   (println (str "boot degraded announce failed: " (.getMessage e))))))))
      (catch Exception e
        (println (str "boot health check failed (non-fatal): " (.getMessage e)))))
    (flush))
  ;; 2026-09-08 restart auto-resume: a poller/daemon restart used to orphan
  ;; every active goal silently. Recover: announce verdicts that landed
  ;; while we were down, re-launch runs that died mid-flight (detached —
  ;; never author on the boot path).
  (try
    (let [telegram-cfg (:telegram (config/load-config))]
      (doseq [{:keys [chat-id thread-id text]} (goal-bridge/recover-interrupted!)]
        (try (delivery/send-message! telegram-cfg chat-id text {:thread-id thread-id})
             (catch Exception e
               (println (str "recovery announce failed: " (.getMessage e)))))))
    (catch Exception e
      (println (str "goal recovery at boot failed: " (.getMessage e)))))
  (flush)
  (let [lifecycle (install-shutdown-hook!
                   (atom {:running? true :in-cycle? false}) 10000)]
    (run-poll-cycles! lifecycle interval-ms
                      (fn [] (poll-once!)))))
