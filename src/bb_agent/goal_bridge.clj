(ns bb-agent.goal-bridge
  "Group → goal-runner seam. `/goal <spec>` in chat authors the goal project
   via one constrained LLM turn (the runner's own config validation is the
   judge, one repair round), scaffolds a workspace OUTSIDE the repo (rollback
   restores the whole workdir — configs must not live inside), launches
   `bb goal` detached with a watcher that posts the outcome to the requesting
   topic. One goal at a time; the runner's lock is per-workdir so the bridge
   keeps its own registry."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [axiom.config :as axiom-config]
            [axiom.observe :as observe]
            [axiom.predicates :as predicates]))

(def goals-root (str (config/home) "/goals"))
(def active-file (str goals-root "/active.edn"))
(def max-spec-chars 400)
(def watcher-max-polls 480) ; 480 * 5s = 40 min ceiling for the watcher

(defn- pid-alive? [pid]
  (try
    (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid))))
    (catch Exception _ false)))

(defn active-run
  "The live goal run, if any. A stale registry entry (dead pid) is cleared."
  []
  (let [f (io/file active-file)]
    (when (.exists f)
      (let [run (edn/read-string (slurp f))]
        (if (pid-alive? (:pid run))
          run
          (do (fs/delete f) nil))))))

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
   the authoring turn's file tools are jailed to its cwd (learned live-fire)."
  [name]
  (let [ws (str goals-root "/" name)
        refs (str ws "/references")]
    (fs/create-dirs refs)
    (doseq [r ["normalize.config.edn" "usage-stats.config.edn"]]
      (when (fs/exists? (str goals-root "/" r))
        (fs/copy (str goals-root "/" r) (str refs "/" r) {:replace-existing true})))
    (p/shell {:dir ws :out :string :err :string} "git" "init" "-q")
    (p/shell {:dir ws :out :string :err :string} "git" "config" "user.email" "goal-bridge@theseus.local")
    (p/shell {:dir ws :out :string :err :string} "git" "config" "user.name" "goal-bridge")
    ws))

(def ^:private author-prompt
  "You are authoring an axiom goal project. Your cwd is the project workdir: %s
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

The project to build: %s
Do NOT run the goal. Write files only.")

(defn- author-cfg [name ws]
  (-> (config/load-config)
      (assoc :session/id (str "goal-author-" name) :cwd ws)))

(defn validate!
  "Run the runner's own validator over the authored config. Returns nil when
   it loads clean, else the problems as a string."
  [cfg-path]
  (try
    (axiom-config/load-config cfg-path)
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

(defn ^:private author-with-validation!
  "One authoring turn, then the validator judges; on failure, one repair
   turn carrying the exact validation errors; then the baseline-integrity
   pre-flight (the runner's iteration-0 halt, pre-paid) with one repair.
   Returns nil on success or the problems string."
  [name ws spec]
  (let [cfg-path (str ws "/project.edn")
        refs [(str ws "/references/normalize.config.edn")
              (str ws "/references/usage-stats.config.edn")]]
    (core/run-turn! (author-cfg name ws)
                    (format author-prompt ws (first refs) (second refs) ws
                            (subs name 0 (min 12 (count name))) spec))
    (loop [attempt 0]
      (if-let [err (validate! cfg-path)]
        (if (zero? attempt)
          (do (core/run-turn! (author-cfg name ws)
                              (str "Your project.edn failed validation:\n" err
                                   "\n\nFix project.edn (and act.sh if it is implicated). Do not run the goal."))
              (recur 1))
          err)
        (if-let [baseline (baseline-problems
                           (axiom-config/load-config cfg-path))]
          (if (zero? attempt)
            (do (core/run-turn! (author-cfg name ws)
                                (str "Your goal project failed the baseline pre-flight:\n"
                                     baseline
                                     "\n\nFix project.edn (and act.sh if it is implicated). Do not run the goal."))
                (recur 1))
            baseline)
          nil)))))

(defn ^:private spawn-detached!
  "nohup + background + echo pid: the child survives the poller and reports
   its pid for the registry and the watcher."
  [dir cmd]
  (let [res (p/shell {:dir dir :out :string :err :string :continue true}
                     "bash" "-c" (str "nohup " cmd " & echo $!"))]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (throw (ex-info (str "spawn failed: " (:err res)) {:exit (:exit res)})))))

(defn launch!
  "Promote project.edn to config.edn with the bridge-injected :notify block
   (the authored file deliberately carries no secrets — the fence forbids
   the LLM writing config.edn), then start `bb goal` detached from the repo
   root (its bb.edn holds the task), register the run, and spawn the outcome
   watcher for this chat/topic."
  [name chat-id thread-id]
  (let [ws (str goals-root "/" name)
        authored (axiom-config/load-config (str ws "/project.edn"))
        ;; the RUNNER's notify shape (axiom.notify posts EDN to the halt
        ;; listener); the runtime config's :notify is the LISTENER's own
        ;; binding shape — they share a key but not a schema
        secret (:hmac-secret (:notify (config/load-config)))
        notify (merge {:type :http :url "http://127.0.0.1:7787/halt"}
                      (when secret {:hmac-secret secret}))
        cfg-path (str ws "/config.edn")
        log-path (str ws "/run.log")
        repo (str (fs/cwd))
        _ (spit cfg-path (pr-str (assoc authored :notify notify)))
         pid (spawn-detached! repo (str "bb goal " cfg-path " > " log-path " 2>&1"))
        _ (spit active-file (pr-str {:pid (parse-long pid) :name name}))
        watch-args (str/join " " [name (str chat-id) (str (or thread-id "")) pid])]
    (spawn-detached! repo (str "bb scripts/goal_watch.bb " watch-args))
    pid))

(defn run-status
  "Scrape the run log for the outcome. The runner's own words are the truth:
   GOAL FULFILLED / HALT lines; anything else while the pid lives is running."
  [name]
  (let [log (io/file goals-root name "run.log")]
    (cond
      (not (.exists log)) :authored
      :else (let [s (slurp log)]
              (cond
                (str/includes? s "GOAL FULFILLED") :fulfilled
                (str/includes? s "HALT") :halted
                :else :running)))))

(defn list-goals
  "One line per workspace: name + last known status."
  []
  (when (.exists (io/file goals-root))
    (->> (fs/list-dir goals-root)
         (filter #(-> % str io/file .isDirectory))
         (map #(-> % str (str/split #"/") last))
         (remove #{"logs"})
         (sort)
         (map (fn [n] (str n " — " (name (run-status n))))))))

(defn handle-request!
  "The /goal seam for the chat dispatch. Non-goal text → nil. Bare /goal →
   usage. Active run → refusal. Otherwise: scaffold, author (validator as
   judge), launch detached + watcher, return the announcement string."
  [text chat-id thread-id]
  (let [trimmed (when text (str/trim text))]
    (when (and trimmed (or (str/starts-with? trimmed "/goal ") (= "/goal" trimmed)))
      (if (= "/goal" trimmed)
        "Usage: /goal <what to build> — I author the goal project, run it supervised, and the outcome lands here."
        (if-let [active (active-run)]
          (str "⏳ Goal `" (:name active) "` is already running — one at a time. /goals for status.")
          (let [spec (str/trim (subs trimmed 5))]
            (if (or (str/blank? spec) (> (count spec) max-spec-chars))
              (str "Spec must be 1–" max-spec-chars " characters.")
              (let [name (slugify spec)
                    scaffolded (try
                                 {:ws (scaffold! name)}
                                 (catch Exception e
                                   {:err (str "workspace error: " (.getMessage e))}))]
                (if-let [scaffold-err (:err scaffolded)]
                  (str "🚫 Goal failed — " scaffold-err)
                  (let [ws (:ws scaffolded)
                        err (try
                              (if-let [verr (author-with-validation! name ws spec)]
                                verr
                                (do (launch! name chat-id thread-id) nil))
                              (catch Exception e
                                (str "authoring error: " (.getMessage e))))]
                    (if err
                      (str "🚫 Goal authoring failed — " err)
                      (str "🚀 Goal `" name "` launched — outcome lands here when it fulfills or halts."))))))))))))
