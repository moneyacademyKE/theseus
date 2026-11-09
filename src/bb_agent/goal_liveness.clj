(ns bb-agent.goal-liveness
  "Dispatch liveness: did the authoring a dispatch spawned survive to leave a
   receipt? One stamp file + one audit. Report-only — nothing here resumes,
   deletes, or rewrites a workspace.

   WHY (measured, not assumed). Goal `propose-must-fail-closed-260708` was
   dispatched 2026-10-09 09:11 and the ledger read '🚀 authoring started'.
   Six days later the workspace holds spec.txt, launch-args.edn and a
   launch.log whose last line is 09:11:38 — no config.edn, no run.log, no
   outcome.edn, no registry entry, and the topic's authoring lock still says
   {:pid 33884} for a pid that is gone. A dispatch receipt was read as a
   completion receipt (brain/knowledge/negative-scope-receipts.md).

   WHY RECOVERY MISSED IT. `goal-bridge/recover-interrupted!` walks
   goals/active.edn, and only the RUNNER pid is ever registered there
   (launch!); the detached authoring pid was discarded by spawn-launch!,
   which is the one true launch path. So a goal that dies before launch is
   invisible to every boundary that exists.

   TWO EVIDENCE LEVELS, TWO NAMES — not-found is not zero
   (aden-hive/hive, 2026-10-14):
     :failed     a stamped authoring pid, verified dead, no receipt. Measured.
     :abandoned  no stamp at all, no receipt, idle past the authoring ceiling.
                 Inferred from age; the report says so.
   A workspace with no stamp is never reported FAILED: 20 of 20 existing
   workspaces predate the stamp, and calling them dead would be a lie told
   with a count.

   THIRD STATE, added 2026-11-03 after both goals dispatched that day died the
   same way (bb-knowledge-tiers-sha25-475780, bb-coords-externals-list-489058):
   each wrote authoring.edn and the audit reported them :receipted, which reads
   as success, while the file itself said {:result :error, :error
   \"java.net.http.HttpTimeoutException: request timed out\"} — 17 and 25
   rounds, 8 and 17 minutes, nothing built. The founding failure was a dispatch
   ack read as a completion receipt; this one is an ERROR RECEIPT read as a
   completion receipt, the same mistake one level down. An authoring turn that
   returned an error did leave evidence, so :receipts keeps the file — but the
   STATUS is :errored, the report names the reason, and the count stops
   flattering the lane.
   FOURTH FINDING, added 2026-11-04 (source: new-world-arkitech/Self's
   acceptance_test_generator.py — a generated sign-off artifact whose AUTHORIZE
   EVOLUTION button is an alert() and which nothing in the repo reads). One level
   down, ours: all four goals dispatched 2026-11-03/04 have a launch.log whose
   last line is sendMessage ok=false res=Bad Request: chat not found, while
   grep -rn launch.log src/ scripts/ returns writers only (goal_bridge.clj:605,
   goal_launch.bb:45, goal_resume.bb:47) and ZERO readers. So dispatch-spec!
   printed a progress ack for a topic the bot cannot see, and the receipt proving
   it sat unread on disk. Reproduced live this lane: getMe ok=true
   (eileenslybot), getChat -1003995594829 -> Bad Request: chat not found, getChat
   on the config's own :notify chat-id -> ok=true type=private.
   DELIVERY-BLIND is therefore an ANNOTATION, never a fifth status: a goal can be
   :receipted (or :errored) AND delivery-blind, and the partition still sums. A
   dispatch ack printed locally is not evidence that a topic saw it."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.goal.registry :as registry]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def stamp-name
  "The one file this ns writes, inside the goal workspace: data, not a
   registry entry, so it survives whatever active.edn does."
  "dispatch.edn")

(def default-idle-minutes
  "Authoring takes 10–45 honest minutes (dispatch-spec!'s own docstring), so
   an unstamped, unreceipted workspace idle past this is not 'in flight' —
   it is abandoned. A threshold stated as data, not a guess buried in a
   comparison."
  120)

(def ^:private receipt-files
  "Evidence that the dispatch produced something, ordered by how far the run
   got; any one of them means the authoring did not die silently."
  {"run.log"       :run-log     ; the runner ran
   "outcome.edn"   :outcome     ; a verdict was queued for the topic
   "config.edn"    :config      ; authoring completed, config promoted
   "authoring.edn" :authoring   ; the authoring turn returned
   "state.edn"     :state})     ; runner iteration state

(defn stamp-path [ws] (str (fs/path ws stamp-name)))

(defn stamp-dispatch!
  "Record the dispatch: name, authoring pid, spec size, topic, instant.
   Called from the one true launch path (goal-bridge/spawn-launch!), which
   already had the pid and threw it away. Idempotent per workspace — a
   re-dispatch overwrites, because the newest attempt is the one whose
   liveness matters. Returns the stamp map."
  [ws name authoring-pid & {:keys [chat-id thread-id spec-chars dispatched-at]}]
  (let [stamp {:name (str name)
               :authoring-pid authoring-pid
               :chat-id chat-id
               :thread-id thread-id
               :spec-chars spec-chars
               :dispatched-at (or dispatched-at (str (java.time.Instant/now)))}]
    (fs/create-dirs ws)
    (spit (stamp-path ws) (pr-str stamp))
    stamp))

(defn read-stamp [ws]
  (let [f (stamp-path ws)]
    (when (fs/regular-file? f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn read-authoring
  "The authoring turn's own record, or nil. Nil covers three different things
   and deliberately does not distinguish them here — the file may be absent,
   unreadable, or valid EDN that is not a map — because in every case the
   honest answer is 'no verdict known', never :error. Manufacturing an error
   verdict out of corrupt bytes is the same mistake as reporting a missing
   ledger as a zero (2026-10-14)."
  [ws]
  (let [f (fs/path ws "authoring.edn")]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
        (when (map? v) v)))))

(defn stamp-status
  ":read | :missing | :unreadable. An unreadable stamp is NOT a missing one —
   corrupt bytes must not silently downgrade a workspace to 'no evidence'."
  [ws]
  (let [f (stamp-path ws)]
    (cond
      (not (fs/exists? f)) :missing
      (nil? (read-stamp ws)) :unreadable
      :else :read)))

(defn receipts
  "The set of receipt files present. Empty means the dispatch left nothing
   behind — which is the whole question this ns exists to ask."
  [ws]
  (into (sorted-set)
        (keep (fn [[file kw]] (when (fs/exists? (fs/path ws file)) kw)))
        receipt-files))

(defn- entries [ws]
  (cons (fs/path ws)
        (when (fs/exists? (fs/path ws)) (fs/list-dir (fs/path ws)))))

(defn last-activity-ms
  "Newest mtime across the workspace and its top-level entries. Top-level
   only: a goal workspace can hold a whole project, and the dispatch's own
   files (spec.txt, launch.log, dispatch.edn) are all at the top."
  [ws]
  (reduce max 0 (map #(-> % fs/last-modified-time .toMillis) (entries ws))))

(defn age-minutes
  [ws now-ms]
  (quot (- now-ms (last-activity-ms ws)) 60000))

(defn- human-age [mins]
  (cond
    (>= mins 1440) (str (quot mins 1440) "d " (quot (mod mins 1440) 60) "h")
    (>= mins 60) (str (quot mins 60) "h " (mod mins 60) "m")
    :else (str mins "m")))

(def launch-log-name
  "The file the launcher receipts every Bot API call to. Written by three paths
   — goal_bridge.clj:605, goal_launch.bb:45, goal_resume.bb:47 — and read by
   none of them until 2026-11-04."
  "launch.log")
(def ^:private delivery-re
  "The launcher's own line format (goal_launch.bb:45):
   `<java.util.Date.> <method> ok=<bool> res=<message_id|description>`.
   `res=` is last and unquoted, so it swallows the rest of the line — which is
   what makes `Bad Request: chat not found` readable as one reason."
  #"\s(\S+)\s+ok=(true|false)\s+res=(.*)$")
(defn parse-delivery-line
  "One launch.log line -> {:method :ok? :reason}, or nil when the line is not in
   the launcher's format. Nil means UNPARSEABLE, and the caller must not turn
   that into a verdict — guessing `looks fine` from bytes we cannot read is how
   a false-clean is born."
  [line]
  (when-let [[_ method ok reason] (and (string? line) (re-find delivery-re line))]
    {:method method :ok? (= "true" ok) :reason (str/trim reason)}))
(defn delivery
  "Did the dispatch's own progress message reach the topic? Reads the LAST
   non-blank line of launch.log, because the file accumulates across attempts
   (goal_bridge.clj:415) and the newest receipt is the one describing this
   dispatch.
     :ok        the API accepted it (a message_id came back)
     :rejected  the API refused it — the reason is Telegram's own words
     :unknown   no launch.log, or a last line this cannot parse
   :unknown is never reported as :rejected and never as :ok: not-found is not
   zero, and an unparseable line is not evidence either way."
  [ws]
  (let [f (fs/path ws launch-log-name)]
    (if (not (fs/regular-file? f))
      {:status :unknown :reason "no launch.log — this dispatch left no wire receipt"}
      (let [line (->> (str/split-lines (slurp (str f)))
                      (remove str/blank?)
                      last)
            parsed (parse-delivery-line line)]
        (cond
          (nil? line) {:status :unknown :reason "launch.log is empty"}
          (nil? parsed) {:status :unknown
                         :reason (str "unparseable last line: " (str/trim line))}
          (:ok? parsed) (assoc parsed :status :ok)
          :else (assoc parsed :status :rejected))))))
(defn delivery-blind?
  "True only on a MEASURED refusal. An unknown wire is not a blind one — a
   workspace that predates launch.log, or whose log this cannot parse, is not
   accused of anything."
  [d]
  (= :rejected (:status d)))
(defn classify
  "One workspace → {:name :ws :status :stamp-status :authoring-pid :receipts
   :age-minutes :age}. Opts: :alive? (pid → bool, default registry/pid-alive?),
   :now-ms, :idle-minutes."
  ([ws] (classify ws {}))
  ([ws {:keys [alive? now-ms idle-minutes]
        :or {alive? registry/pid-alive?
             now-ms (System/currentTimeMillis)
             idle-minutes default-idle-minutes}}]
   (let [stamp (read-stamp ws)
         pid (:authoring-pid stamp)
         rcpts (receipts ws)
         mins (age-minutes ws now-ms)
         auth (read-authoring ws)
         verdict (when (= :error (:result auth)) :error)
         dlv (delivery ws)
         status (cond
                  ;; An authoring turn that returned an error outranks every other
                  ;; receipt, including run.log: a run that halted is not a run that
                  ;; finished, and :receipted would read as success.
                  verdict :errored
                  (seq rcpts) :receipted
                  (and (number? pid) (not (alive? pid))) :failed
                  (number? pid) :live
                  (> mins idle-minutes) :abandoned
                  :else :unstamped)]
     (cond-> {:name (fs/file-name (fs/path ws))
              :ws (str ws)
              :status status
              :stamp-status (stamp-status ws)
              :authoring-pid pid
              :receipts rcpts
              :age-minutes mins
              :age (human-age mins)}
       verdict (assoc :authoring-verdict verdict
                      :authoring-error (or (:error auth) "(no message recorded)"))
       true (assoc :delivery dlv)
       (delivery-blind? dlv) (assoc :delivery-blind? true)))))

(defn- workspace-dirs [root]
  (->> (fs/list-dir (fs/path root))
       (filter fs/directory?)
       (remove #(str/starts-with? (fs/file-name %) "."))
       (remove #(= "logs" (fs/file-name %)))
       (sort-by str)))

(defn audit
  "Every workspace under `root`, grouped by classify status. A root that does
   not exist is :root-status :missing with NO :workspaces count — an absent
   directory is not a measured zero (the same bug fixed 2026-10-14 in
   bb-agent.knowledge/ledger-mentions)."
  ([root] (audit root {}))
  ([root opts]
   (if-not (fs/directory? (fs/path root))
     {:root (str root) :root-status :missing :opts opts}
     (let [ws (workspace-dirs root)
           classified (mapv #(classify % opts) ws)]
       (merge {:root (str root)
               :root-status :read
               :workspaces (count classified)
               :rows classified
               :delivery-blind (filterv :delivery-blind? classified)
               :opts opts}
              (group-by :status classified))))))

(defn report
  "The human receipt: counts, the named failures, and what this did not check."
  [a]
  (if (= :missing (:root-status a))
    (str "goal liveness: goals root not read — " (:root a)
         " (not-found, not zero; nothing here claims those goals are alive or dead)")
    (let [failed (:failed a) abandoned (:abandoned a) unstamped (:unstamped a)
          errored (:errored a)
          blind (:delivery-blind a)
          idle (or (:idle-minutes (:opts a)) default-idle-minutes)]
      (str/join
       "\n"
       (cond-> [(str "goal liveness: " (:root a))
                (format (str "workspaces: %d | live %d, receipted %d, FAILED %d, "
                             "errored %d, abandoned %d, unstamped %d | "
                             "delivery-blind %d")
                        (:workspaces a) (count (:live a)) (count (:receipted a))
                        (count failed) (count errored) (count abandoned)
                        (count unstamped) (count blind))]
         (seq errored)
         (into (for [{:keys [name authoring-error age]} errored]
                 (str "ERRORED (authoring returned an error): " name
                      " [idle " age "] — " authoring-error)))
         (seq failed)
         (into (for [{:keys [name authoring-pid age]} failed]
                 (str "FAILED (authoring pid dead, no receipt): " name
                      " [pid " authoring-pid ", idle " age "]")))
         (seq abandoned)
         (into (for [{:keys [name age]} abandoned]
                 (str "ABANDONED (no stamp, no receipt, idle > " idle "m): " name
                      " [idle " age "]")))
         (seq blind)
         (into (for [{:keys [name status age] d :delivery} blind]
                 (str "DELIVERY-BLIND (dispatch ack rejected by the wire): " name
                      " [" (clojure.core/name status) ", idle " age "] — "
                      (or (:method d) "?") ": " (:reason d))))
         true
         (conj (str "negative scope: " (count unstamped) " workspace(s) carry no readable "
                    "dispatch.edn — their status is unverifiable, not alive; ABANDONED is "
                    "inferred from age alone (no pid was ever recorded). A receipt proves "
                    "the authoring turn returned, not that it built anything — that is what "
                    "ERRORED separates out, and a workspace with no receipt file is not "
                    "evidence of work done either way. DELIVERY-BLIND is an annotation, "
                    "not a status: it says the dispatch's own progress message was refused "
                    "by the wire, so an ack printed locally is not evidence that a topic "
                    "saw it — and an absent or unparseable launch.log is :unknown, never "
                    "blind. Report only: nothing was resumed, "
                    "deleted, or modified.")))))))

(defn -main
  "Usage: bb goal-liveness [goals-root]
   Exit 0 = report printed, 2 = usage or a root that is not a directory.
   Never mutates."
  [args]
  (let [[root] (if (empty? args) [(str (config/home) "/goals")] args)]
    (if-not (fs/directory? (fs/path root))
      (do (println (str "goal liveness: no such goals root — " root))
          (println "usage: bb goal-liveness [goals-root]")
          2)
      (do (println (report (audit root))) 0))))
