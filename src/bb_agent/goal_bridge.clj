(ns bb-agent.goal-bridge
  "Group → goal-runner seam. `/goal <spec>` in chat authors the goal project
   via one constrained LLM turn (the runner's own config validation is the
   judge, one repair round), scaffolds a workspace OUTSIDE the repo (rollback
   restores the whole workdir — configs must not live inside), launches
   `bb goal` detached with a watcher that posts the outcome to the requesting
   topic. One goal per topic (registry keyed by [chat-id thread-id]);
   the runner's lock is per-workdir so the bridge keeps its own registry.
   Registry/outcomes/progress live in their own namespaces (bk-c60f)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bb-agent.goal.config :as goal-config]
            [bb-agent.goal.observe :as observe]
            [bb-agent.goal.outcomes :as outcomes]
            [bb-agent.goal.predicates :as predicates]
            [bb-agent.goal.progress :as progress]
            [bb-agent.goal.registry :as registry]))

(def max-spec-chars 400)
(def watcher-max-polls 480) ; 480 * 5s = 40 min ceiling for the watcher

(defn slugify
  "Short filesystem-safe goal name, uniquified by a time suffix."
  [spec]
  (let [base (-> spec str/trim str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        base (if (str/blank? base) "goal" (subs base 0 (min 24 (count base))))]
    (str base "-" (mod (System/currentTimeMillis) 1000000))))

(defn scaffold!
  "Create the workspace with git initialized and a bridge identity — the
   runner checkpoints and rolls back via git, so the initial commit exists
   before the first act. Schema references are COPIED into the workspace:
   the authoring turn can read them with cwd-relative paths (learned live-fire)."
  [name]
  (let [ws (str (registry/goals-root) "/" name)
        refs (str ws "/references")]
    (fs/create-dirs refs)
    (doseq [r ["normalize.config.edn" "usage-stats.config.edn"]]
      (when (fs/exists? (str (config/home) "/references/" r))
        (fs/copy (str (config/home) "/references/" r) (str refs "/" r) {:replace-existing true})))
    ;; The default goal methodology (owner directive 2026-09-07, conf: high)
    ;; rides into every workspace — the authoring agent reads it like the
    ;; schema references. Brain knowledge dir is the single source.
    (let [m (str (config/home) "/brain/knowledge/goal-methodology.md")]
      (when (fs/exists? m)
        (fs/copy m (str refs "/goal-methodology.md") {:replace-existing true})))
    (p/shell {:dir ws :out :string :err :string} "git" "init" "-q")
    (p/shell {:dir ws :out :string :err :string} "git" "config" "user.email" "goal-bridge@theseus.local")
    (p/shell {:dir ws :out :string :err :string} "git" "config" "user.name" "goal-bridge")
    ws))

;; ── authoring lock ─────────────────────────────────────────────────────
;; Authoring is detached (V1a: it never runs on the poll loop), so the
;; registry's active-run guard fires only AFTER a launch — two /goal calls
;; in one topic would author two workspaces before either registered. The
;; lock is one pid-stamped file per [chat thread]; a dead pid makes it
;; stale, and stale is removable (same contract as registry pid-alive?).

(defn- authoring-lock-file [chat-id thread-id]
  (io/file (registry/goals-root)
           (str ".authoring-" chat-id "-" (or thread-id 0) ".edn")))

(defn authoring-busy?
  "True when a live authoring process holds this topic's lock. A lock whose
   pid can't be read is stale by definition — unreadable must mean free,
   or one corrupt file wedges the topic's authoring forever."
  [chat-id thread-id]
  (let [f (authoring-lock-file chat-id thread-id)]
    (and (.exists f)
         (let [{:keys [pid]} (try (edn/read-string (slurp f))
                                  (catch Exception _ nil))]
           (and (number? pid)
                (registry/pid-alive? pid))))))

(defn authoring-lock!
  "Stamp the topic's authoring lock with this process's pid. Returns true
   when acquired, false when another live process holds it."
  [chat-id thread-id]
  (if (authoring-busy? chat-id thread-id)
    false
    (do (spit (authoring-lock-file chat-id thread-id)
              (pr-str {:pid (.pid (java.lang.ProcessHandle/current))}))
        true)))

(defn authoring-unlock!
  "Release the topic's authoring lock. Safe when absent."
  [chat-id thread-id]
  (let [f (authoring-lock-file chat-id thread-id)]
    (when (.exists f) (.delete f))
    true))

(def ^:private author-prompt
  "You are authoring a goal project. Your cwd is the project workdir: %s
METHODOLOGY (the default goal methodology, owner-stamped): read
  references/goal-methodology.md
and follow it — Babashka for anything scripted (Python is floored by the
constitution; never invoke it), YAGNI, prefer one-liners that honestly carry
the intent, verify claims from disk rather than reporting intent.
SKILL CONTRACT: your context carries a skills index. If the plan uses a
skill, you MUST read_file its SKILL.md BEFORE writing project.edn, then
name every skill you actually read in project.edn as :skills-used [\"name\"].
A skill mentioned but never read is decoration — claim only what you read;
read only what the project needs (dogfood bk-d8a0).
Before writing anything, READ these reference configs for the exact schema:
  %s
  %s
Then write exactly two files:

1. config.edn — same keys as the references:
   :workdir \"%s\" (absolute), :lock \"./bridge.lock\", :log-dir \"./logs\",
   :checkpoint {:tag-prefix \"%s\"}, :act {:sh \"./act.sh {{attempt}}\"},
   :observers {<name> {:sh \"<command>\" :parse :int|:string}}, :goal {:op ... :ref ... :value ...},
   :integrity [...], :progress <an observer name>, :stall-after, :max-rollbacks.
   Do NOT add a :notify block — the bridge injects it at launch, and writing
   config.edn is fenced. Name your config file project.edn.
2. act.sh — idempotent, each run under 60 seconds, executable (chmod +x).
3. Declare :deliverables [\"relative/path.ext\", ...] in project.edn — the
   files this goal PRODUCED that the requesting topic should receive when
   the goal fulfills (max 3, each ≤ 45 MB). Declare only real outputs;
   declared-but-missing files are skipped honestly at ship time.

The project to build: %s
Do NOT run the goal. Write files only.")

(defn- author-cfg
  "Config for an authoring turn. Authoring a real project costs far more
   rounds than chat (:max-tool-rounds 24 died mid-project live 2026-09-07),
   so the budget is its own key: :goal/max-authoring-rounds, floor 48.
   :goal/author-model overrides the model for AUTHORING ONLY (V1b) — a
   fast non-reasoning author against a 44-minute reasoning one; turns keep
   the configured model. Absent key = same model everywhere.
   The approver is constant-approved: authoring turns are non-interactive,
   so :ask would deny with no human to ask (B3) — the user pre-consented
   by launching the goal, and the constitution still vetoes first (policy
   :deny short-circuits before the approval gate)."
  [name ws]
  (let [base (config/load-config)]
    (cond-> (-> base
                (assoc :session/id (str "goal-author-" name) :cwd ws)
                (assoc :max-tool-rounds (max (or (:max-tool-rounds base) 8)
                                             (or (:goal/max-authoring-rounds base) 48)))
                (assoc :approval/ask (constantly :approved)))
      (:goal/author-model base) (assoc :model (:goal/author-model base)))))

(defn validate!
  "Run the runner's own validator over the authored config. Returns nil when
   it loads clean, else the problems as a string."
  [cfg-path]
  (try
    (goal-config/load-config cfg-path)
    nil
    (catch Exception e
      (or (some-> e ex-data :keys) (some-> e ex-data :key))
      (str (or (some-> e ex-data :errors) (.getMessage e))))))

(defn baseline-clean?
  "The runner halts :integrity at iteration 0 when the scaffold doesn't
   satisfy the integrity checks — so the bridge runs the runner's OWN
   observers + predicates against the scaffold BEFORE launching."
  [cfg]
  (empty? (predicates/integrity-violations
           (observe/build-world (:observers cfg)
                                {:dir (:workdir cfg) :timeout 15000})
           (:integrity cfg))))

(defn- baseline-problems [cfg]
  (let [world (observe/build-world (:observers cfg)
                                   {:dir (:workdir cfg) :timeout 15000})
        violations (predicates/integrity-violations world (:integrity cfg))]
    (when (seq violations)
      (str "baseline integrity violations: " (pr-str violations)
           " — the scaffold must ALREADY satisfy every integrity check before "
           "the first act. Either make act.sh's first-run path establish them, "
           "or move that check into :goal instead of :integrity."))))

(def ^:private default-authoring-timeout-ms
  "Hard ceiling for the whole authoring phase — the backstop, not the
   primary clock. 2026-09-09 dogfood: a complex spec on a reasoning model
   authors honestly for 10-20+ min, and the old 10-min total budget
   amputated live work mid-round every time. Primary clock is progress
   (see default-authoring-idle-ms); this only fires when work is STILL
   moving past any sane total. Override via :goal/authoring-timeout-ms."
  2700000)

(def ^:private default-authoring-idle-ms
  "No-progress clock for the authoring phase — the real stall detector.
   A healthy authoring round emits a tool call every minute or two; a
   wedged provider call emits nothing and pins 0% CPU. 2026-09-08's 60-min
   parked resume was zero progress for an hour; that invariant — silence,
   not duration — is what a stall is. Override via
   :goal/authoring-idle-timeout-ms."
  480000)

(defn ^:private author-attempts!
  "One authoring turn, then the validator judges; on failure, one repair
   turn carrying the exact validation errors; then the baseline-integrity
   pre-flight (the runner's iteration-0 halt, pre-paid) with one repair.
   Returns nil on success or the problems string."
  [name ws spec acfg refs]
  (let [cfg-path (str ws "/project.edn")]
    (core/run-turn! acfg
                    (format author-prompt ws (first refs) (second refs) ws
                            (subs name 0 (min 12 (count name))) spec))
    (loop [attempt 0]
      (if-let [err (validate! cfg-path)]
        (if (zero? attempt)
          (do (core/run-turn! acfg
                              (str "Your project.edn failed validation:\n" err
                                   "\n\nFix project.edn (and act.sh if it is implicated). Do not run the goal."))
              (recur 1))
          err)
        (if-let [baseline (baseline-problems
                           (goal-config/load-config cfg-path))]
          (if (zero? attempt)
            (do (core/run-turn! acfg
                                (str "Your goal project failed the baseline pre-flight:\n"
                                     baseline
                                     "\n\nFix project.edn (and act.sh if it is implicated). Do not run the goal."))
                (recur 1))
            baseline)
          nil)))))

(defn ^:private watch-authoring!
  "Await an authoring future under a two-clock budget:
   - idle: no progress bump for idle-ms → ::stalled (the real hang detector)
   - ceiling: total wall-clock exceeds ceiling-ms → ::timed-out (backstop)
   last-progress is an atom of System/nanoTime, bumped by the emit wrapper
   around every authoring tool call. A completed future returns its value
   untouched. The losing future is abandoned, not killed — bounded waste,
   never a hang (same contract as policy.clj's pred eval)."
  [fut last-progress {:keys [idle-ms ceiling-ms poll-ms]}]
  (let [deadline (+ (System/nanoTime) (* (long ceiling-ms) 1000000))
        idle-nanos (* (long idle-ms) 1000000)]
    (loop []
      (cond
        (realized? fut) @fut
        (> (System/nanoTime) deadline) ::timed-out
        (> (- (System/nanoTime) @last-progress) idle-nanos) ::stalled
        :else (do (Thread/sleep (long (or poll-ms 250)))
                  (recur))))))

(defn- author-with-validation!
  "author-attempts! under a two-clock budget (see watch-authoring!). The
   emit channel — one event per authoring tool call — doubles as the
   progress signal: it is always wrapped so it bumps last-progress even
   when no requester is listening (emit nil), because a hang must be
   detectable whether or not anyone is watching. Returns nil on success,
   else the stall/problems string.
   Every run persists <ws>/authoring.edn — {:model :rounds :duration-ms
   :result :finished} (V1c): the 44-minute silence must never be
   unmeasured again, and model comparisons (V1b) read data, not vibes."
  [name ws spec emit]
  (let [started-ms (System/currentTimeMillis)
        rounds (atom 0)
        last-progress (atom (System/nanoTime))
        progress-emit (fn [ev]
                        (when (= (:status ev) :tool/call) (swap! rounds inc))
                        (reset! last-progress (System/nanoTime))
                        (when emit (emit ev)))
        acfg (-> (author-cfg name ws)
                 (assoc :status/emit progress-emit))
        refs [(str ws "/references/normalize.config.edn")
              (str ws "/references/usage-stats.config.edn")]
        idle (or (:goal/authoring-idle-timeout-ms acfg) default-authoring-idle-ms)
        budget (or (:goal/authoring-timeout-ms acfg) default-authoring-timeout-ms)
        result (watch-authoring!
                (future (author-attempts! name ws spec acfg refs))
                last-progress
                {:idle-ms idle :ceiling-ms budget :poll-ms 250})
        record {:model (or (:goal/author-model acfg) (:model acfg))
                :rounds @rounds
                :duration-ms (- (System/currentTimeMillis) started-ms)
                :result (case result
                          ::stalled :stalled
                          ::timed-out :timed-out
                          :authored)
                :finished (str (java.time.Instant/now))}]
    (try (spit (str ws "/authoring.edn") (pr-str record)) (catch Exception _))
    (cond
      (= ::stalled result)
      (str "authoring stalled: no progress for " (quot idle 60000)
           " min (provider hang or wedged turn)")

      (= ::timed-out result)
      (str "authoring stalled: exceeded total budget of " (quot budget 60000)
           " min while still making progress — raise :goal/authoring-timeout-ms"
           " or simplify the spec")

      :else result)))

(defn ^:private spawn-detached!
  "nohup + background + echo pid: the child survives the poller and reports
   its pid for the registry and the watcher."
  [dir cmd]
  (let [res (p/shell {:dir dir :out :string :err :string :continue true}
                     "bash" "-c" (str "nohup " cmd " & echo $!"))]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (throw (ex-info (str "spawn failed: " (:err res)) {:exit (:exit res)})))))

(defn promote-config
  "Authored config + the bridge-owned keys: :notify (the halt transport,
   runner-shaped) and :name (the workspace slug — the LLM must never be
   trusted to remember bridge data). Missing :name was the #7 failure:
   the validator demanded it, the repair round misread the error."
  [authored name]
  (let [secret (:hmac-secret (:notify (config/load-config)))
        notify (merge {:type :http :url "http://127.0.0.1:7787/halt"}
                      (when secret {:hmac-secret secret}))]
    (assoc authored :name name :notify notify)))

(defn- tcc-guard
  "Desktop/Documents/Downloads are TCC-protected: launchd-spawned processes
   get promptless denial there (proven 2026-09-06 — /bin/pwd under launchd
   returned 'Operation not permitted'). The bridge runs in the poller's TCC
   context, the same one the runner inherits, so an unreadable workdir here
   is a dead run there. Refuse early with the reason (B8)."
  [ws]
  (let [cfg-path (str ws "/config.edn")]
    (when (fs/exists? cfg-path)
      (let [workdir (str (:workdir (edn/read-string (slurp cfg-path))))
            home (System/getProperty "user.home")]
        (when (some #(str/starts-with? workdir (str home %))
                    ["/Desktop" "/Documents" "/Downloads"])
          (try
            (doall (fs/list-dir workdir))
            nil
            (catch Exception e
              (str "workdir " workdir " is under a TCC-protected path and "
                   "unreadable from the service context: " (.getMessage e)
                   ". Move the project out of ~/Desktop, ~/Documents or "
                   "~/Downloads — background daemons cannot be granted access."))))))))

(defn launch!
  "Promote project.edn to config.edn with the bridge-injected :notify block
   (the authored file deliberately carries no secrets — the fence forbids
   the LLM writing config.edn), then start `bb goal` detached from the repo
   root (its bb.edn holds the task), register the run, and spawn the outcome
   watcher for this chat/topic."
  [name chat-id thread-id]
  (let [ws (str (registry/goals-root) "/" name)
        authored (goal-config/load-config (str ws "/project.edn"))
        cfg-path (str ws "/config.edn")
        log-path (str ws "/run.log")
        repo (str (fs/cwd))
        _ (spit cfg-path (pr-str (promote-config authored name)))
        _ (when-let [tcc-err (tcc-guard ws)]
            (throw (ex-info tcc-err {:goal/tcc-guard true})))
        ;; baseline commit: the runner's pre-act checkpoint tags HEAD, and a
        ;; rollback resets to this commit — with authored files tracked, a
        ;; rollback genuinely reverts an act's outputs instead of no-opping
        _ (p/shell {:dir ws :out :string :err :string} "git" "add" "-A")
        _ (p/shell {:dir ws :out :string :err :string} "git" "commit" "-q" "-m" "baseline: authored project")
         pid (spawn-detached! repo (str "bb goal " cfg-path " > " log-path " 2>&1"))
        ;; the registry carries the routing needed to recover this run after
        ;; a poller/daemon restart — pid+name alone orphaned every run that
        ;; outlived its process (amnesia class, 2026-09-08). Keyed by
        ;; [chat-id thread-id] so topics run goals in parallel. :home rides
        ;; the args file — the watcher reads config from THIS home, never
        ;; ambient env (same contract as goal_launch.bb). Args ride an EDN
        ;; file, never shell-joined: an empty thread-id collapsed argv once
        ;; and the script read the PID as the thread.
        _ (registry/register-run! chat-id thread-id
                         {:pid (parse-long pid) :name name
                          :chat-id chat-id :thread-id thread-id})
         _ (spit (str ws "/watch-args.edn")
                 (pr-str {:name name :home (str (config/home))
                          :chat-id chat-id :thread-id thread-id :pid pid}))]
    (spawn-detached! repo (str "bb scripts/goal_watch.bb " (str ws "/watch-args.edn")))
    pid))

(defn launch-scaffolded!
  "Author + launch an already-scaffolded workspace (spec.txt written).
   Called by the detached goal_launch.bb script with a real progress emit
   (V1a) — never on the poll loop. Returns the user-facing reply string."
  [name ws spec chat-id thread-id emit]
  (if-let [active (registry/active-run-for chat-id thread-id)]
    (str "⏳ Goal `" (:name active) "` is already running in this topic — one goal per topic. /goals for status.")
    (let [err (try
                (if-let [verr (author-with-validation! name ws spec emit)]
                  verr
                  (do (launch! name chat-id thread-id) nil))
                (catch Exception e
                  (str "authoring error: " (.getMessage e))))]
      (if err
        (str "🚫 Goal authoring failed — " err
             "\nWorkspace kept: `" name "` — reply /goal resume " name " to continue.")
        (str "🚀 Goal `" name "` launched — outcome lands here when it fulfills or halts.")))))

(defn dispatch-spec!
  "Shared spec guard for every entry point (chat /goal, build-verb route,
   launch_goal tool): blank/length checks, per-topic authoring-lock check,
   scaffold, then DETACH authoring into goal_launch.bb. V1a: authoring
   never runs on the poll loop or inside a turn — it can take 10-45
   honest minutes, and inline authoring parked the whole service (the
   dogfood's silent 44). Returns the immediate reply; the authoring result
   reaches the topic via the progress message and the outcome queue."
  [spec chat-id thread-id]
  (cond
    (or (str/blank? spec) (> (count spec) max-spec-chars))
    (str "Spec must be 1–" max-spec-chars " characters.")

    (authoring-busy? chat-id thread-id)
    "⏳ This topic is already authoring a goal — the outcome will land here."

    (registry/active-run-for chat-id thread-id)
    (str "⏳ Goal `" (:name (registry/active-run-for chat-id thread-id))
         "` is already running in this topic — one goal per topic. /goals for status.")

    :else
    (let [name (slugify spec)
          scaffolded (try
                       {:ws (scaffold! name)}
                       (catch Exception e
                         {:err (str "workspace error: " (.getMessage e))}))]
      (if-let [scaffold-err (:err scaffolded)]
        (str "🚫 Goal failed — " scaffold-err)
        (let [ws (:ws scaffolded)]
          (spit (str ws "/spec.txt") spec)
          ;; :home rides the args file — the detached child does NOT inherit
          ;; this process's config/home (env var, or a test's redef); it must
          ;; read config from the SAME home that dispatched it.
          (spit (str ws "/launch-args.edn")
                (pr-str {:name name :spec spec :home (str (config/home))
                         :chat-id chat-id :thread-id thread-id}))
          (spawn-detached! (str (fs/cwd))
                           (str "bb scripts/goal_launch.bb "
                                (str ws "/launch-args.edn")
                                " >> " (str ws "/launch.log") " 2>&1"))
          (str "🚀 Goal `" name "` — authoring started, progress updates below."))))))

(defn resume!
  "Re-enter authoring for a failed/unfinished goal workspace. Authoring
   failure keeps the scaffold and spec (B1, 2026-09-07: half-built
   workspaces were orphaned with no resume path) — resume reads the kept
   spec, re-authors with a continuation note, then launches. Bare resume
   picks the most recently touched workspace."
  [slug chat-id thread-id emit]
  (if-let [active (registry/active-run-for chat-id thread-id)]
    (str "⏳ Goal `" (:name active) "` is already running in this topic — one goal per topic. /goals for status.")
    (let [name (if (str/blank? slug)
                 (some->> (fs/list-dir (registry/goals-root))
                          (filter fs/directory?)
                          (sort-by fs/last-modified-time)
                          last
                          .getFileName
                          str)
                 slug)
          ws (str (registry/goals-root) "/" name)
          spec-file (io/file (str ws "/spec.txt"))]
      (cond
        (str/blank? name) "No goal workspaces to resume."
        (not (.exists spec-file)) (str "No resumable goal `" name "` (no spec.txt kept).")
        :else
        (let [spec (str/trim (slurp spec-file))
              err (try
                    (if-let [verr (author-with-validation!
                                   name ws
                                   (str spec
                                        "\n\n[RESUME] The workspace already has files from a previous attempt — READ them and fix what failed instead of starting over.")
                                   emit)]
                      verr
                      (do (launch! name chat-id thread-id) nil))
                    (catch Exception e
                      (str "authoring error: " (.getMessage e))))]
          (if err
            (str "🚫 Goal authoring failed — " err
                 "\nWorkspace kept: `" name "` — reply /goal resume " name " to continue.")
            (str "🚀 Goal `" name "` resumed + launched — outcome lands here when it fulfills or halts.")))))))

(defn resume-detached!
  "Re-enter a crashed goal WITHOUT blocking the caller: the resume (one or
   more authoring turns, minutes) runs in a detached bb process that loads
   its args from an EDN file and queues its result as the workspace's
   outcome.edn — the poller's drain turns it into a session turn and the
   re-launched watcher announces progress in the owning topic. Never run
   authoring on the poll loop."
  [name chat-id thread-id]
  (let [ws (str (registry/goals-root) "/" name)
        args-file (str ws "/resume-args.edn")]
    (fs/create-dirs ws)
    ;; :home rides the args file — the detached child must read config from
    ;; the SAME home that dispatched it (see dispatch-spec!).
    (spit args-file (pr-str {:name name :home (str (config/home))
                             :chat-id chat-id :thread-id thread-id}))
    (spawn-detached! (str (fs/cwd))
                     (str "bb scripts/goal_resume.bb " args-file))))

(defn recover-interrupted!
  "Poller-boot recovery for goals (amnesia class, 2026-09-08: a daemon
   restart orphaned every active run silently). The registry maps
   [chat-id thread-id] → {:pid :name :chat-id :thread-id}; per entry:
   - pid alive → leave it alone (the runner outlived the poller).
   - dead pid + verdict in run.log → queue the verdict as an outcome and
     announce it in the owning topic.
   - dead pid, no verdict → the run died with the poller: hand it to the
     detached resume path and say so in the topic.
   Returns announcements [{:chat-id :thread-id :text}] for the boot path
   to deliver; handled entries are dropped, live entries kept, so
   resume!/launch! can re-register."
  []
  (let [runs (registry/read-runs)]
    (if (empty? runs)
      []
      (let [[handled kept]
            (reduce (fn [[handled kept] [k {:keys [pid name chat-id thread-id] :as run}]]
                      (if (registry/pid-alive? pid)
                        [handled (assoc kept k run)]
                        (let [status (progress/run-status name)
                              text (case status
                                     :fulfilled (str "🎯 goal `" name "`: ✅ GOAL FULFILLED"
                                                     " — recovered after a Theseus restart.")
                                     :halted (str "⛔ goal `" name "` halted — recovered after a Theseus restart.")
                                     ;; :running (no verdict — crashed mid-run) or :authored
                                     (str "🔁 goal `" name "` died with the last restart — resuming it now."))]
                          (case status
                            (:fulfilled :halted) (outcomes/queue-outcome! name chat-id thread-id text)
                            (resume-detached! name chat-id thread-id))
                          ;; dissoc, not just return kept: the accumulator
                          ;; STARTS as the full runs map — a handled entry
                          ;; must leave it or the write-back never fires.
                          [(conj handled {:chat-id chat-id :thread-id thread-id :text text})
                           (dissoc kept k)])))
                    [[] runs]
                    runs)]
        (when (not= kept runs) (registry/write-runs! kept))
        handled))))

(defn handle-request!
  "The /goal seam for the chat dispatch. Non-goal text → nil. Bare /goal →
   usage. Launch/resume detach immediately (V1a) — authoring never runs on
   the poll loop."
  [text chat-id thread-id]
  (let [trimmed (when text (str/trim text))]
    (when (and trimmed (or (str/starts-with? trimmed "/goal ") (= "/goal" trimmed)))
      (if (= "/goal" trimmed)
        "Usage: /goal <what to build> — I author the goal project, run it supervised, and the outcome lands here."
        (let [spec (str/trim (subs trimmed 5))]
          (if (or (= spec "resume") (str/starts-with? spec "resume "))
            (let [slug (str/trim (subs spec 6))]
              (if (authoring-busy? chat-id thread-id)
                "⏳ This topic is already authoring a goal — the outcome will land here."
                (do (resume-detached! slug chat-id thread-id)
                    (str "🔁 Resuming" (when-not (str/blank? slug) (str " `" slug "`"))
                         " — authoring detached, progress updates below."))))
            (dispatch-spec! spec chat-id thread-id)))))))

(def build-verbs
  "First-word production verbs that force the goal loop (owner directive
   2026-09-06: build verbs always goal). Data — extend here, no code change."
  #{"build" "create" "make" "generate" "implement" "scaffold" "develop"})

(def ^:private build-stop-phrases
  "Verb-openers that are conversation management, not production requests."
  #{"make sure" "make it" "make that" "make this" "make them"})

(defn build-intent?
  "Deterministic gate: true when text's FIRST word is a build verb (word
   boundary, case-insensitive) and the :goal-auto-route kill-switch in
   config.edn is not false (default: on). Stop phrases lose. The LLM never
   decides whether a build goes supervised — that decision is data."
  [text]
  (when-let [t (some-> text str/trim str/lower-case)]
    (when (get (config/load-config) :goal-auto-route true)
      (and (not (some #(str/starts-with? t %) build-stop-phrases))
           (some (fn [v] (or (= t v) (str/starts-with? t (str v " "))))
                 build-verbs)))))

(defn route-build-request!
  "Auto-route a plain build-verb message into the goal loop. The FULL text
   is the spec — no /goal prefix. Same honest guard, same detached path."
  [text chat-id thread-id]
  (dispatch-spec! (str/trim (or text "")) chat-id thread-id))
