(ns bb-agent.subagent
  "Subagent ledger & isolated executor: pure record transitions
  (:pending -> :claimed -> :completed/:failed), file-backed shell in
  state/subagents.edn, and run-subagent! with optional detached git
  worktree isolation and inherited parent taint exposure."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(defn- ->id [x]
  (if (map? x) (:subagent/id x) x))

(defn spawn
  "([ledger prompt] [ledger id prompt] [ledger id prompt opts]) -> [ledger record].
  Explicit id replaces an existing record instead of duplicating.
  Optional :exposure in opts propagates parent turn taint into the subagent."
  ([ledger prompt]
   (spawn ledger (str (java.util.UUID/randomUUID)) prompt {}))
  ([ledger id prompt]
   (spawn ledger id prompt {}))
  ([ledger id prompt {:keys [exposure]}]
   (let [record (cond-> {:subagent/id     (->id id)
                         :subagent/prompt prompt
                         :subagent/status :pending}
                  exposure (assoc :subagent/exposure exposure))]
     [(conj (vec (remove #(= (:subagent/id record) (:subagent/id %)) ledger))
            record)
      record])))

(defn by-id [ledger id]
  (first (filter #(= (->id id) (:subagent/id %)) ledger)))

(defn- transition
  "Rewrite the record for `id` when its status is in `froms`;
  otherwise the ledger is returned unchanged."
  [ledger id froms f]
  (let [id (->id id)]
    (mapv (fn [r] (if (and (= id (:subagent/id r))
                           (contains? froms (:subagent/status r)))
                    (f r) r)) ledger)))

(defn claim
  "([ledger id assignee t] [ledger id assignee t capacity]).
  pending -> claimed. No-op on unknown id or non-pending record; the
  capacity arity also no-ops when `capacity` claims are outstanding."
  ([ledger id assignee t]
   (transition ledger id #{:pending}
               #(assoc % :subagent/status :claimed
                         :subagent/assignee assignee
                         :subagent/at t)))
  ([ledger id assignee t capacity]
   (if (< (count (filterv #(= :claimed (:subagent/status %)) ledger))
          (long capacity))
     (claim ledger id assignee t)
     ledger)))

(defn complete
  "claimed -> completed with :subagent/result. No-op otherwise."
  [ledger id result t]
  (transition ledger id #{:claimed}
              #(assoc % :subagent/status :completed
                        :subagent/result result
                        :subagent/at t)))

(defn fail
  "claimed -> failed with :subagent/reason. No-op otherwise."
  [ledger id reason t]
  (transition ledger id #{:claimed}
              #(assoc % :subagent/status :failed
                        :subagent/reason reason
                        :subagent/at t)))

(defn pending [ledger]
  (filterv #(= :pending (:subagent/status %)) ledger))

(defn claimed [ledger]
  (filterv #(= :claimed (:subagent/status %)) ledger))

(defn can-claim? [ledger capacity]
  (< (count (claimed ledger)) (long capacity)))

;; --- file shell: each bang is load -> pure transition -> save ---

(defn- ledger-file []
  (fs/path (config/home) "state" "subagents.edn"))

(defn- apply! [f & args]
  (aio/update-edn! (ledger-file) []
                   (fn [ledger]
                     (vec (apply f (if (sequential? ledger) (vec ledger) []) args)))))

(defn spawn!
  ([id prompt] (spawn! id prompt {}))
  ([id prompt opts]
   (aio/update-edn! (ledger-file) []
                    (fn [ledger]
                      (let [[ledger' _record]
                            (spawn (if (sequential? ledger) (vec ledger) [])
                                   (->id id) prompt opts)]
                        (vec ledger'))))))

(defn claim! [id assignee t]
  (apply! claim (->id id) assignee t))

(defn complete! [id result]
  (apply! complete (->id id) result (System/currentTimeMillis)))

(defn fail! [id reason]
  (apply! fail (->id id) reason (System/currentTimeMillis)))

;; --- live isolated subagent executor ---

(defn- git-repo? [dir]
  (and (string? dir)
       (fs/directory? dir)
       (try
         (zero? (:exit (p/sh {:dir dir} "git" "rev-parse" "--is-inside-work-tree")))
         (catch Exception _ false))))

(defn- create-worktree! [repo-dir id]
  (let [wt-dir (str (fs/create-temp-dir {:prefix (str "theseus-wt-" id "-")}))]
    (fs/delete-tree wt-dir)
    (let [res (p/sh {:dir repo-dir} "git" "worktree" "add" "--detach" wt-dir "HEAD")]
      (if (zero? (:exit res))
        wt-dir
        (throw (ex-info (str "git worktree add failed: " (str/trim (str (:err res))))
                        {:exit (:exit res)}))))))

(defn- remove-worktree! [repo-dir wt-dir]
  (try
    (p/sh {:dir repo-dir} "git" "worktree" "remove" "--force" wt-dir)
    (catch Exception _ nil))
  (when (fs/exists? wt-dir)
    (try (fs/delete-tree wt-dir) (catch Exception _ nil))))

(defn run-subagent!
  "Spawn, claim, execute, and record a child turn in-process.
   Supports optional detached git worktree isolation (:worktree true),
   inherits parent :security/exposure taint, and enforces :subagent/max-concurrent."
  [{:keys [prompt worktree max_rounds]} cfg]
  (let [prompt-s (str/trim (str (or prompt "")))
        depth (long (or (:subagent/depth cfg) 0))
        allow-nesting? (boolean (:subagent/allow-nesting cfg))
        cap (long (or (:subagent/max-concurrent cfg) 2))]
    (cond
      (str/blank? prompt-s)
      {:tool/name "spawn_subagent" :status :error :executed? false
       :error/message "spawn_subagent requires a non-empty :prompt"}

      (and (pos? depth) (not allow-nesting?))
      {:tool/name "spawn_subagent" :status :error :executed? false
       :error/message "Nested spawn_subagent is disabled"}

      :else
      (let [id (str "sub-" (subs (str (java.util.UUID/randomUUID)) 0 8))
            parent-exp (:security/exposure cfg)
            ledger (aio/read-edn-safe (ledger-file) [])]
        (if-not (can-claim? (if (sequential? ledger) ledger []) cap)
          {:tool/name "spawn_subagent" :status :error :executed? false
           :error/message (str "Subagent capacity reached (" cap " active)")}
          (let [parent-cwd (or (:cwd cfg) (str (config/home)))
                use-wt? (boolean (and worktree (git-repo? parent-cwd)))
                wt-dir (when use-wt? (create-worktree! parent-cwd id))
                child-cwd (or wt-dir parent-cwd)
                runner (or (:subagent/runner cfg)
                           (requiring-resolve 'bb-agent.core/run-turn!))
                child-cfg (cond-> (dissoc cfg :subagent/runner :plan/gate-after-tool-calls)
                            true (assoc :session/id (str "subagent-" id)
                                        :cwd child-cwd
                                        :subagent/depth (inc depth)
                                        :max-tool-rounds (min 12 (max 1 (long (or max_rounds 8)))))
                            parent-exp (assoc :security/exposure parent-exp))]
            (spawn! id prompt-s (when parent-exp {:exposure parent-exp}))
            (claim! id (str "worker-" id) (System/currentTimeMillis))
            (try
              (let [turn (runner child-cfg prompt-s)
                    final-text (str (or (:assistant/final turn) ""))]
                (complete! id final-text)
                {:tool/name "spawn_subagent"
                 :status :ok
                 :executed? true
                 :subagent/id id
                 :worktree? use-wt?
                 :assistant/final final-text
                 :turn/ok (not (false? (:turn/ok turn)))})
              (catch Exception e
                (fail! id (or (ex-message e) (str (class e))))
                {:tool/name "spawn_subagent"
                 :status :error
                 :executed? true
                 :subagent/id id
                 :worktree? use-wt?
                 :error/message (or (ex-message e) (str (class e)))})
              (finally
                (when wt-dir
                  (remove-worktree! parent-cwd wt-dir))))))))))
