(ns bb-agent.web-turn
  "The deck's interactive turn lane — Telegram parity over HTTP.

  One engine, two transports. A compose box queues a job on a
  single-threaded executor; the job runs the same core/run-turn! the
  Telegram poller runs, with two web-local seams: :status/emit appends
  flow entries to the job (the web's live tool ticker), and
  :approval/ask is the shared waiting-approver whose notify surface is
  the job itself. Approval decisions arrive as POSTs and resolve
  through the same state/approvals.edn the Telegram buttons write — a
  pending request is a fact about the turn, not about the channel.

  The durable record is the session file: finish-turn appends there
  under the same FileChannel lock the poller honors, so the web
  transcript and the Telegram history of a continued session are one
  history. Commands and skills route through the intake module's
  public dispatch, so both channels agree on what a command is."
  (:require [babashka.fs :as fs]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.session :as session]
            [bb-agent.telegram-flow :as flow]
            [bb-agent.telegram-intake :as intake]
            [bb-agent.web-artifacts :as artifacts]
            [bb-agent.web-lane :as lane]
            [clojure.string :as str]))

(def jobs
  "Job registry: id -> job map. Public for tests (registering a
  synthetic job to pin approval decisions) — production writes flow
  through submit-turn!/run-job! only."
  (atom {}))
(def uploads
  "Upload registry: id -> descriptor map. Public for tests (recovery
  pins assert descriptors rehydrate after a restart); production
  writes flow through save-upload! only."
  (atom {}))
(def ^:private job-seq (atom 0))
(def ^:private max-jobs 60)
(def ^:private max-upload-bytes (* 20 1024 1024))
(def ^:private max-events 200)

(def ^:private executor
  "One lane, one turn at a time: the deck mirrors the poller's sequential
  model instead of racing two turn engines inside one process. The
  thread is a daemon — a test process must be able to exit without
  draining the pool."
  (java.util.concurrent.Executors/newSingleThreadExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "theseus-web-lane")
         (.setDaemon true))))))

;; ---------- internals ----------

(defn- now-ms [] (System/currentTimeMillis))

(defn- persist-lane!
  "Write the registries through at the transitions that matter. Best
  effort: a persistence fault must never kill a turn."
  []
  (try (lane/persist! @jobs @uploads) (catch Exception _ nil)))

(defn- job-update!
  "Apply (f args…) to one job map, missing job = no-op. Varargs mirror
  clojure.core/update so call sites read like the update they are."
  [id f & args]
  (swap! jobs (fn [m]
                (if (contains? m id)
                  (apply update m id f args)
                  m))))

(defn- terminal!
  "One terminal transition: job fields + the write-through. cond
  branches are single-expression, so this is the body the branches
  share."
  [id & kvs]
  (apply job-update! id assoc kvs)
  (persist-lane!))

(defn- bound-error [e]
  (let [m (or (.getMessage e) (str (class e)))]
    (if (> (count m) 400) (str (subs m 0 400) "…") m)))

(defn- bound-text
  "Any text bounded to display size; nil-safe."
  [s]
  (let [m (str (or s ""))]
    (if (> (count m) 200) (str (subs m 0 200) "…") m)))

(defn- append-event! [id entry]
  (job-update! id (fn [j]
                    (if (>= (count (:events j)) max-events) j
                      (update j :events conj entry)))))

(defn- web-emit
  "The :status/emit for web jobs: flow entries land in the job map for
  JSON polling. Never throws — same contract as core's safe-emit."
  [id]
  (fn [{:keys [status tool args attempt kind reason]}]
    (try
      (case status
        :tool/call (append-event! id {:type "call" :tool (str tool)
                                      :context (flow/tool-context tool args)
                                      :at (now-ms)})
        :tool/done (append-event! id {:type "done" :tool (str tool)
                                      :ok (boolean (:ok? args)) :at (now-ms)})
        :retry/attempt (append-event! id {:type "retry"
                                          :attempt (or attempt 1)
                                          :kind (some-> kind name)
                                          :reason (bound-text reason)
                                          :at (now-ms)})
        nil)
      (catch Exception _ nil))))

(defn- web-approver
  "The :approval/ask for web jobs: the shared waiting-approver with the
  notify surface pointed at the job. A decision POST (this module's
  decide!), a typed approval (approval-bypass!), or a plain
  approve/deny message resolves it through approvals.edn within the
  approver's 200ms poll."
  [id session-id cfg]
  (approval/waiting-approver
   {:session-id session-id
    :channel :web
    :timeout-ms (or (get-in cfg [:web :approval-timeout-ms]) 600000)
    :notify (fn [pending]
              (job-update! id assoc :approval
                           {:id (:approval/id pending)
                            :tool (str (:tool/name pending))
                            :context (flow/tool-context (:tool/name pending)
                                                        (:tool/args pending))})
              (persist-lane!)
              {:web/registered true})
    :on-expire (fn [_ _] (job-update! id dissoc :approval))}))

(defn- goal-request-reply
  "Web /goal and build-verb routing: the goal loop detaches exactly as it
  does from Telegram; progress and deliverables land in the chat/topic
  named by [:web :goal-chat-id] — config data, not a hardcode. Unset
  gets the honest refusal, never a silent no-op."
  [cfg text kind]
  (if-let [chat-id (get-in cfg [:web :goal-chat-id])]
    (let [thread-id (get-in cfg [:web :goal-thread-id])]
      (case kind
        :goal-request (goal-bridge/handle-request! text chat-id thread-id)
        :build-goal (goal-bridge/route-build-request! text chat-id thread-id)))
    (str "Goal launching from the web needs a delivery route — add\n"
         "[:web {:goal-chat-id .. :goal-thread-id ..}] to config.edn.\n"
         "Or launch from Telegram and watch it here in the Goals column.")))

(defn- run-job!
  [id]
  (when-let [job (get @jobs id)]
    (if (= :canceled (:status job))
      (persist-lane!) ; canceled before the executor picked it up
      (let [sid (:session/id job)]
        (job-update! id assoc :status :running :started-at (now-ms))
        (persist-lane!)
        (try
          (let [text (str/trim (str (or (:text job) "")))
                atts (keep #(get @uploads %) (:attachment-ids job))]
            (cond
              (and (str/blank? text) (empty? atts))
              (terminal! id :status :failed :error "empty message"
                         :finished-at (now-ms))

              (approval/telegram-approval-reply text)
              (let [decision (approval/telegram-approval-reply text)
                    resolved (approval/resolve! sid decision)]
                (terminal! id :status :done
                           :final (approval/approval-reply-text resolved decision)
                           :finished-at (now-ms)))

              :else
              (let [cfg (config/load-config)
                    cmd (intake/chat-command text)
                    final (if cmd
                            (let [reply (if (contains? #{:goal-request :build-goal} cmd)
                                          (goal-request-reply cfg text cmd)
                                          (intake/handle-chat-command cmd sid text nil nil))]
                              (when-not (= :new cmd)
                                (session/append-turn!
                                 sid {:session/id sid
                                      :user/input text
                                      :assistant/final (str reply)
                                      :source :command
                                      :created/at (str (java.time.Instant/now))}))
                              (str reply))
                            (let [composed (or (intake/skill-command text) text)
                                  contexts (apply str
                                                  (map #(intake/attachment-context
                                                         (:telegram cfg) %)
                                                       atts))
                                  images (->> atts
                                              (filter #(str/starts-with?
                                                        (or (:mime-type %) "") "image/"))
                                              (mapv (fn [a] {:path (:path a)
                                                             :mime-type (:mime-type a)})))
                                  turn (core/run-turn!
                                        (assoc cfg
                                               :session/id sid
                                               :session/shared? false
                                               :web/job-id id
                                               :status/emit (web-emit id)
                                               :user/images (not-empty images)
                                               :approval/ask (web-approver id sid cfg))
                                        (str composed contexts))]
                              (:assistant/final turn)))]
                (terminal! id :status :done :final (str final)
                                 :finished-at (now-ms)))))
          (catch Exception e
            (job-update! id assoc :status :failed :error (bound-error e)
                         :finished-at (now-ms))
            (persist-lane!))
          (finally
            (job-update! id dissoc :approval)
            (persist-lane!)))))))

(defn- prune-jobs! []
  (swap! jobs (fn [m]
                (let [excess (- (count m) max-jobs)]
                  (if (pos? excess)
                    (apply dissoc m (map :job/id (take excess
                                                      (sort-by :submitted-at (vals m)))))
                    m)))))

;; ---------- public API (called by bb-agent.web routes) ----------

(defn- safe-upload-name
  "A sender-supplied name reduced to a harmless basename: no path
  grammar, no traversal, bounded length."
  [file-name]
  (let [base (-> (or file-name "upload.bin")
                 fs/file-name str
                 (str/replace #"[^A-Za-z0-9._ -]" "_"))]
    (if (str/blank? base)
      "upload.bin"
      (subs base 0 (min (count base) 80)))))

(defn- upload-kind
  "Honest kind derivation: image/* → vision input, audio/* (or the
  common voice extensions) → a voice note for STT, else a document."
  [mime-type path]
  (let [mime (some-> mime-type str str/lower-case)
        ext (some-> (fs/extension path) str str/lower-case)]
    (cond
      (str/starts-with? (or mime "") "image/") :photo
      (or (str/starts-with? (or mime "") "audio/")
          (contains? #{"ogg" "oga" "mp3" "m4a" "wav" "opus" "webm"} ext))
      :voice
      :else :document)))

(defn save-upload!
  "Persist one raw upload under channel_attachments/web and register its
  descriptor — the same {:kind :path :mime-type :bytes} shape the
  Telegram attachment module produces, so intake/attachment-context
  renders it verbatim. Images become vision input, audio becomes a
  voice note (STT through the same stt-bin), everything else a document."
  [file-name mime-type ^bytes bytes]
  (when (> (alength bytes) max-upload-bytes)
    (throw (ex-info (str "upload exceeds " max-upload-bytes " bytes")
                    {:web/upload? true :status 413})))
  (let [id (subs (str (java.util.UUID/randomUUID)) 0 12)
        name (safe-upload-name file-name)
        dir (fs/path (config/home) "channel_attachments" "web")
        path (fs/path dir (str id "-" name))
        desc {:kind (upload-kind mime-type path)
              :path (str path)
              :file-name name
              :mime-type (some-> mime-type str)
              :bytes (alength bytes)}]
    (fs/create-dirs dir)
    (fs/write-bytes path bytes)
    (swap! uploads assoc id desc)
    (assoc desc :upload/id id)))

(defn submit-turn!
  "Queue one web turn: {:session id, :text s, :attachments [upload-id]}.
  Returns the queued job map. Throws on unknown attachment ids — the
  route turns that into a 400."
  [{:keys [session text attachments]}]
  (let [ids (mapv str (or attachments []))
        _ (when-let [missing (seq (remove #(contains? @uploads %) ids))]
            (throw (ex-info (str "unknown attachment ids: " (pr-str (vec missing)))
                            {:web/turn? true :status 400})))
        sid (session/safe-session-id (or (not-empty (str session)) "web"))
        id (str "job-" (swap! job-seq inc))
        job {:job/id id
             :session/id sid
             :status :queued
             :text (str/trim (str (or text "")))
             :attachment-ids ids
             :events []
             :submitted-at (now-ms)}]
    (swap! jobs assoc id job)
    (persist-lane!)
    (.execute executor (fn [] (run-job! id)))
    (prune-jobs!)
    job))

(defn job-view
  "The JSON-facing projection of one job: status, live flow events, a
  pending approval, the final reply, the error, registered artifacts.
  Bounded by construction."
  [id]
  (when-let [j (get @jobs (str id))]
    (-> (select-keys j [:job/id :session/id :status :events :approval :final
                        :error :turn-ok :cancel-requested
                        :submitted-at :started-at :finished-at])
        (assoc :artifacts (artifacts/artifacts-for id)))))

(defn recover-lane!
  "Boot (and test) recovery: hydrate the registries from disk. A restart
  marks still-queued/running jobs :interrupted — honest, never re-run.
  Returns the recovery report ({:interrupted [ids] ..}) for the caller
  to surface."
  []
  (let [report (lane/recover!)]
    (reset! jobs (:jobs report))
    (reset! uploads (:uploads report))
    (reset! job-seq (:next-seq report))
    report))

(defn cancel!
  "Cancel a web job. Queued: instant — the executor skips it. Running:
  an intent flag plus denial of any pending approval (that usually ends
  the wait); an in-flight provider call itself runs to completion — the
  honest limit, documented in the parity matrix. Idempotent on already
  terminal jobs (returns the state unchanged)."
  [id]
  (let [id (str id)
        job (get @jobs id)]
    (cond
      (nil? job) {:error "unknown-job"}
      (= :queued (:status job))
      (do (job-update! id assoc :status :canceled :finished-at (now-ms))
          (persist-lane!)
          {:canceled true :status :canceled})
      (= :running (:status job))
      (do (job-update! id assoc :cancel-requested true)
          (when-let [approval-id (get-in job [:approval :id])]
            (approval/resolve-by-id! approval-id (:session/id job) :deny)
            (job-update! id dissoc :approval))
          (persist-lane!)
          {:cancel-requested true :status :running})
      :else {:canceled false :status (:status job)})))

(defn retry!
  "Re-queue a failed/interrupted/canceled job's inputs as a NEW job.
  Old side effects already happened; retry is an explicit operator
  decision, same as re-sending the message."
  [id]
  (let [job (get @jobs (str id))]
    (cond
      (nil? job) {:error "unknown-job"}
      (not (contains? #{:failed :interrupted :canceled} (:status job)))
      {:error "not-retryable" :status (:status job)}
      :else (let [new-job (submit-turn! {:session (:session/id job)
                                         :text (:text job)
                                         :attachments (:attachment-ids job)})]
              {:retried true :job/id (:job/id new-job)}))))

(defn approval-bypass!
  "Typed approval replies resolve on the REQUEST thread, ahead of the
  single-lane queue — the exact Telegram ordering (process-message!
  checks approval replies before anything queues). A pending approval
  plus a blocked lane is the deadlock this kills: 'approve' typed into
  the composer answers the waiting turn instead of queuing behind it.
  Returns {:resolved true :reply s} or nil when the text is not an
  approval reply for a pending request of that session."
  [session text]
  (when-let [decision (approval/telegram-approval-reply (str text))]
    (let [sid (session/safe-session-id (or (not-empty (str session)) "web"))]
      (when (->> (vals (:pending (approval/load-state)))
                 (some #(= sid (:session/id %))))
        (let [resolved (approval/resolve! sid decision)]
          {:resolved true
           :decision (name decision)
           :reply (approval/approval-reply-text resolved decision)})))))

(defn decide!
  "Resolve a pending approval by id — the web buttons. The decision
  lands in state/approvals.edn where the running turn's approver (any
  channel) picks it up within its 200ms poll."
  [approval-id decision]
  (if-not (contains? #{:approve :deny :approve-rest} decision)
    {:error "invalid-decision"}
    (let [job (some (fn [j] (when (= approval-id (get-in j [:approval :id])) j))
                    (vals @jobs))]
      (if-not job
        {:error "unknown-approval"}
        (let [resolved (approval/resolve-by-id! approval-id
                                                (:session/id job) decision)]
          (if resolved
            (do (job-update! (:job/id job) dissoc :approval)
                {:resolved true :decision decision})
            {:error "not-pending"}))))))

(defn reset-lane!
  "Tests: clear the registries. Never touches session files."
  []
  (reset! jobs {}) (reset! uploads {}))
