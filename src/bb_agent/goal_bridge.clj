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
            [axiom.config :as axiom-config]))

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
   before the first act."
  [name]
  (let [ws (str goals-root "/" name)]
    (fs/create-dirs ws)
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
   :integrity [...], :progress <an observer name>, :stall-after, :max-rollbacks,
   and :notify copied VERBATIM
   from the reference (it routes halt pages).
   CRITICAL: integrity checks run BEFORE the first act — the scaffold you
   write must already satisfy every integrity check. The :goal predicate must
   be achievable only after the act script's work is done.
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

(defn ^:private author-with-validation!
  "One authoring turn, then the validator judges; on failure, one repair
   turn carrying the exact validation errors. Returns nil on success or the
   problems string."
  [name ws spec]
  (let [cfg-path (str ws "/config.edn")
        refs [(str goals-root "/normalize.config.edn")
              (str goals-root "/usage-stats.config.edn")]]
    (core/run-turn! (author-cfg name ws)
                    (format author-prompt ws (first refs) (second refs) ws
                            (subs name 0 (min 12 (count name))) spec))
    (loop [attempt 0]
      (if-let [err (validate! cfg-path)]
        (if (zero? attempt)
          (do (core/run-turn! (author-cfg name ws)
                              (str "Your config.edn failed validation:\n" err
                                   "\n\nFix config.edn (and act.sh if it is implicated). Do not run the goal."))
              (recur 1))
          err)
        nil))))

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
  "Start `bb goal` detached from the repo root (its bb.edn holds the task),
   register the run, and spawn the outcome watcher for this chat/topic."
  [name chat-id thread-id]
  (let [ws (str goals-root "/" name)
        cfg-path (str ws "/config.edn")
        log-path (str ws "/run.log")
        repo (str (fs/cwd))
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
