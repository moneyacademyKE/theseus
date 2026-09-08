(ns bb-agent.outbox
  "Durable outbox for outbound effects.

   A message intent is persisted BEFORE it is attempted: enqueue writes one
   EDN file, the sender attempts delivery, and only a confirmed API success
   acks (deletes) the file. A process that dies mid-send therefore loses
   nothing — the next drain (boot or poll cycle) re-attempts whatever is
   still on disk.

   Semantics: AT-LEAST-ONCE. The crash window between a successful send and
   its ack can produce a rare duplicate; a duplicate beats a silent loss,
   and the window is nanoseconds. Stated, not hidden.

   Poison discipline mirrors goal_bridge/drain-outcomes!: a corrupt file is
   skipped (kept for inspection), never allowed to kill the poll loop.
   A record that fails `max-attempts` drains moves to dead/ with a loud log
   line — failure is data, not silence.

   This namespace owns QUEUE MECHANICS ONLY. Transport is injected by the
   caller (the poller supplies delivery/post-api), so the outbox never
   depends on the delivery layer and stays trivially testable."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def max-attempts 5)
(def drain-batch-size 20)

(defn root-for
  "Outbox directory for this config. `:outbox-root` in cfg wins (tests,
   isolated runners); otherwise the live home's state dir."
  [cfg]
  (or (:outbox-root cfg) (str (config/home) "/state/outbox")))

(defn- file-for [cfg id] (str (root-for cfg) "/" id ".edn"))

(defn enqueue!
  "Persist one outbound intent. Returns its id. The record carries enough
   to re-issue the effect without any in-memory context: API method, raw
   request params, and routing keys for failure data."
  [cfg record]
  (let [id (str (random-uuid))
        record (merge {:id id :attempts 0
                       :created/at (str (java.time.Instant/now))}
                      record)]
    (fs/create-dirs (root-for cfg))
    (spit (file-for cfg id) (pr-str record))
    id))

(defn ack!
  "Confirmed delivery consumes the intent. Idempotent: a missing file is
   already acked."
  [cfg id]
  (fs/delete-if-exists (file-for cfg id)))

(defn pending
  "All pending [file record] pairs, oldest first. Unparseable files yield
   ::poison markers so the caller can log-and-skip without dying."
  [cfg]
  (let [root (root-for cfg)]
    (if-not (fs/directory? root)
      []
      (->> (fs/glob root "*.edn")
           (filter fs/regular-file?)
           (map (fn [f]
                  (try
                    (let [r (edn/read-string (slurp (str f)))]
                      (if (and (map? r) (:id r) (:method r))
                        {:file f :record r}
                        {:file f :record ::poison}))
                    (catch Exception _ {:file f :record ::poison}))))
           (sort-by #(get-in % [:record :created/at] ""))
           (vec)))))

(defn pending-count
  "Cheap probe for health reporting: pending files, no parsing."
  [cfg]
  (let [root (root-for cfg)]
    (if (fs/directory? root)
      (count (fs/glob root "*.edn"))
      0)))

(defn mark-attempt!
  "Bump the attempt counter on a persisted intent, by id. Returns the new
   count. Public: the send path marks its own failed first attempt so the
   drain inherits a truthful count instead of starting from zero."
  [cfg id]
  (let [f (file-for cfg id)
        record (edn/read-string (slurp f))
        attempts (inc (long (or (:attempts record) 0)))]
    (spit f (pr-str (assoc record :attempts attempts
                                  :last-attempt/at (str (java.time.Instant/now)))))
    attempts))

(defn- dead!
  "Move a file out of circulation, loudly. `reason` is the truthful cause —
   exhausted retries or unreadable bytes."
  [cfg file record reason]
  (let [dead-dir (str (root-for cfg) "/dead")]
    (fs/create-dirs dead-dir)
    (fs/move file (str dead-dir "/" (:id record) ".edn"))
    (println (str "OUTBOX DEAD-LETTER: " (:method record)
                  " to chat " (get-in record [:routing :chat-id])
                  " — " reason " — kept in "
                  dead-dir " @ " (java.time.Instant/now)))
    (flush)))

(defn drain!
  "Attempt every pending intent (oldest first, capped per call) through the
   injected `attempt-fn` — a fn of one record that returns on success and
   throws on failure. Success acks; failure bumps the attempt counter, and
   the record that exhausts max-attempts becomes a dead letter. Poison files
   are logged and skipped. Returns {:sent n :deferred n :dead n :poison n}.

   A drain is safe to run from any number of cycles: acks only ever delete
   the file that earned the success, and enqueue writes unique names."
  [cfg attempt-fn]
  (let [batch (take drain-batch-size (pending cfg))]
    (reduce
     (fn [acc {:keys [file record]}]
       (if (= ::poison record)
         ;; Poison can never succeed — it cannot even be read. Dead-letter
         ;; on first sight instead of logging every poll cycle forever.
         (do (dead! cfg file {:id (-> file fs/file-name (str/replace #"\.edn$" ""))
                              :method :unreadable
                              :routing {}}
                    "unreadable bytes (poison)")
             (update acc :poison inc))
         (try
           (attempt-fn record)
           (ack! cfg (:id record))
           (update acc :sent inc)
           (catch Exception e
             ;; The sender may ack (delete) the file between our listing and
             ;; this mark — a won race, not an error. Count it as deferred.
             (let [attempts (try (mark-attempt! cfg (:id record))
                                 (catch Exception _ :gone))]
               (if (and (int? attempts) (>= attempts max-attempts))
                 (do (dead! cfg file record
                            (str "gave up after " max-attempts " attempts"))
                     (update acc :dead inc))
                 (do (println (str "outbox: deferred " (:method record)
                                   " (attempt " attempts "/" max-attempts
                                   "): " (.getMessage e)
                                   " @ " (java.time.Instant/now)))
                     (flush)
                     (update acc :deferred inc))))))))
     {:sent 0 :deferred 0 :dead 0 :poison 0}
     batch)))
