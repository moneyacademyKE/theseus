(ns bb-agent.schedule
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.cron :as cron]
            [bb-agent.outbox :as outbox]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn schedules-file []
  (fs/path (config/home) "state" "schedules.edn"))

(defn runs-file []
  (fs/path (config/home) "state" "schedule-runs.edn"))

(defn load-schedules []
  (let [path (schedules-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      [])))

(defn save-schedules! [entries]
  (let [path (schedules-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str (vec entries)))
    (vec entries)))

(defn add-schedule!
  "Register `schedule-id` -> `prompt`. The 3-arity attaches a 5-field
  cron expression: the entry then fires only when the expression
  matched since its last logged run (see due-schedules). Entries
  without one are free-running and fire every tick, as before."
  ([schedule-id prompt] (add-schedule! schedule-id prompt nil))
  ([schedule-id prompt cron-expr]
   (let [cfg (config/load-config)
         entry (cond-> {:schedule/id schedule-id
                        :schedule/prompt prompt
                        ;; config carries no :cwd; an entry registered without
                        ;; one would inherit the daemon's ambient directory —
                        ;; the exact drift launchd WorkingDirectory exists to
                        ;; prevent. Pin registration-time cwd instead.
                        :cwd (or (:cwd cfg) (System/getProperty "user.dir"))
                        :provider (:provider cfg)
                        :model (:model cfg)}
                 (seq cron-expr) (assoc :schedule/cron cron-expr))
         existing (->> (load-schedules)
                       (remove #(= schedule-id (:schedule/id %)))
                       vec)
         entries (conj existing entry)]
     (save-schedules! entries)
     entry)))

(defn remove-schedule! [schedule-id]
  (let [remaining (->> (load-schedules)
                       (remove #(= schedule-id (:schedule/id %)))
                       vec)]
    (save-schedules! remaining)
    remaining))

(defn find-schedule [schedule-id]
  (some #(when (= schedule-id (:schedule/id %)) %) (load-schedules)))

(defn- append-run-log! [entry]
  (let [path (runs-file)
        entries (if (fs/regular-file? path)
                  (edn/read-string (slurp (str path)))
                  [])
        updated (conj (vec entries) entry)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str updated))
    updated))

(defn last-run-times
  "schedule/id -> :at stamp of its most recent run (ISO-8601 instant
  string; due-schedules coerces). Entries are appended chronologically,
  so later entries win the reduce. Runs from before :at stamping
  existed simply carry no time and are ignored."
  []
  (let [path (runs-file)]
    (if (fs/regular-file? path)
      (->> (edn/read-string (slurp (str path)))
           (keep (fn [{:keys [schedule/id] :as e}]
                   (when (and id (:at e)) [id (:at e)])))
           (reduce (fn [acc [id at]] (assoc acc id at)) {}))
      {})))

(defn- minute-aligned [zdt]
  (.truncatedTo zdt java.time.temporal.ChronoUnit/MINUTES))

(defn- ->instant
  "Last-run times arrive as Instants (pure callers) or the :at
  strings last-run-times reads back from the log. One seam, both."
  [t]
  (cond
    (instance? java.time.Instant t) t
    (string? t) (java.time.Instant/parse t)
    :else (throw (ex-info "Not a timestamp" {:value t :type (type t)}))))

(defn cron-due?
  "Pure gating: cron `expr` is due when it matched at any minute in
  (last-run, now] — catch-up is just the window closing over missed
  slots. Never-run schedules are due on the first observed matching
  minute. `now` is a ZonedDateTime, `last-run` an Instant or nil;
  both arrive as arguments so DST stays the matcher's problem and
  gating stays a function."
  [expr last-run now]
  (if last-run
    (let [zone (.getZone now)
          from (.plusMinutes (minute-aligned (.atZone last-run zone)) 1)
          to (.plusMinutes (minute-aligned now) 1)]
      (boolean (seq (cron/matches-in-window expr from to))))
    (cron/match-cron expr now)))

(defn due-schedules
  "Schedules to fire at `now`: those without :schedule/cron always
  (unchanged pre-cron behavior), those with one only when cron-due?
  against their last run time."
  [schedules last-runs now]
  (filter (fn [{:keys [schedule/cron] :as s}]
            (or (str/blank? cron)
                (cron-due? cron
                           (some-> (get last-runs (:schedule/id s))
                                   ->instant)
                           now)))
          schedules))

(defn failure-notice
  "Terse one-line failure notice: schedule id, exit code, and the cause —
   the babashka crash box's `Message:` line when present, else trimmed
   stdout, else a placeholder. Never a stacktrace dump: the topic is the
   reader's surface, a crash box is ledger material."
  [schedule-id exit out err]
  (let [msg-line (some->> err
                          (re-find #"Message:\s*(\S[^\n]*)")
                          (second))
        cause (or (some-> msg-line str/trim)
                  (-> out str/trim not-empty)
                  "no output")]
    (format "❌ %s failed (exit %d): %s" schedule-id exit cause)))

(defn- run-command-lane!
  "Command-shaped schedules never touch an LLM. The prompt lane's job was
  'run this command, make its stdout your final message' — but a model
  asked to parrot can fabricate the parrot instead: rsi-daily's fallback
  model produced three weeks of plausible cycle output with ZERO tool
  calls (session receipts, 2026-09-28), each day's lie seeded by the
  previous one in the shared session history. Direct execution cannot
  lie — stdout is the command's, or the failure is. Optional
  :schedule/deliver-to {:chat-id :thread-id} routes the output through
  the durable outbox to a topic; absent means the ledger is the only
  record, as before. Delivery shape (2026-10-01, owner: 'unacceptable
  format'): a non-zero exit NEVER posts raw stderr — the run ledger
  keeps the full crash, and a deliver-to topic gets one terse
  failure-notice line. A raw babashka crash box in a Telegram topic is
  a failure reaching the reader, not a report reaching him. Lanes that
  deliver their own content (brief posts its document itself) set
  :schedule/deliver-on-success false so operational stdout never posts
  on success either."
  [cfg {:keys [schedule/command cwd schedule/deliver-to schedule/deliver-on-success] :as _schedule} schedule-id at]
  (let [res (p/shell {:dir (or cwd (System/getProperty "user.dir"))
                      :out :string :err :string :continue true}
                     command)
        exit (:exit res)
        out (str/trim (str (:out res)))
        err (str/trim (str (:err res)))
        final (if (zero? exit)
                out
                (failure-notice schedule-id exit out err))
        entry {:schedule/id schedule-id
               :status (if (zero? exit) :ok :failed)
               :at at
               :command command
               :command/exit exit
               :assistant/final final}]
    ;; Deliver on success by default; :schedule/deliver-on-success false
    ;; gates success delivery for self-delivering lanes. A failure with a
    ;; deliver-to still posts the terse notice — silence is how misses
    ;; went unseen for two weeks.
    (when (and deliver-to
               (or (not (zero? exit))
                   (not (false? deliver-on-success))))
      (try
        (outbox/enqueue! cfg {:method "sendMessage"
                              :params (cond-> {:chat_id (:chat-id deliver-to)
                                               :text (subs final 0 (min (count final) 4000))}
                                        (:thread-id deliver-to)
                                        (assoc :message_thread_id (:thread-id deliver-to)))
                              :routing deliver-to})
        (catch Exception e
          (println (str "command-lane delivery enqueue failed: " (.getMessage e)
                        " @ " (java.time.Instant/now))))))
    (append-run-log! entry)
    {:assistant/final final :command/exit exit :status (:status entry)}))

(defn run-schedule!
  "Run one schedule by id and log it with an :at stamp — the
   wall-clock instant of the run, the hook the cron gate reads back.
   The 3-arity lets the cron lane stamp the injected clock instead of
   the host's, keeping the log consistent with the window that
   justified the run. Entries with :schedule/command execute directly
   (see run-command-lane!); everything else runs an LLM turn."
  ([cfg schedule-id] (run-schedule! cfg schedule-id (str (java.time.Instant/now))))
  ([cfg schedule-id at]
   (if-let [schedule (find-schedule schedule-id)]
     (if-let [cmd (:schedule/command schedule)]
       (run-command-lane! cfg schedule schedule-id at)
       (let [turn (core/run-turn! (merge cfg
                                         (select-keys schedule [:cwd :provider :model])
                                         {:session/id schedule-id
                                          ;; Scheduled turns are non-interactive: no human
                                          ;; can answer an approval prompt inside a cron lane
                                          ;; (the request just expires and the run fails, as
                                          ;; ai-stock-brief-hourly did hourly). The owner
                                          ;; pre-consented by installing the schedule, and
                                          ;; the constitution still vetoes first — policy
                                          ;; :deny short-circuits before this gate.
                                          :approval/ask (constantly :approved)})
                                    (:schedule/prompt schedule))
             log-entry {:schedule/id schedule-id
                        :status :ok
                        :at at
                        :assistant/final (:assistant/final turn)}]
         (append-run-log! log-entry)
         turn))
     (throw (ex-info (str "Unknown schedule: " schedule-id)
                     {:schedule/id schedule-id})))))

(defn run-all-schedules!
  "Fire what's due. The 1-arity is what the daemon rides: a single
  config arg, wall-clock now. The 2-arity takes `now` explicitly so
  tests (and callers with their own clock) drive the tick. Either way
  cron entries fire only inside their (last-run, now] catch-up
  window; free entries fire every tick. Returns the completed turns
  of everything that fired, in file order — same shape the 1-arity
  always returned."
  ([cfg] (run-all-schedules! cfg (java.time.ZonedDateTime/now)))
  ([cfg now]
   (let [at (str (.toInstant now))]
     (->> (due-schedules (load-schedules) (last-run-times) now)
          (mapv #(run-schedule! cfg (:schedule/id %) at))))))
