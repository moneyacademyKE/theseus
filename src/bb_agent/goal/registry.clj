(ns bb-agent.goal.registry
  "The goal-run registry: which workspace runs for which [chat-id thread-id].
   Extracted from goal_bridge.clj (bk-c60f, LOC ceiling) — pure state on
   disk, no authoring/launch knowledge. One goal per topic; the runner's
   lock is per-workdir so this registry is the bridge's own bookkeeping."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn goals-root [] (str (config/home) "/goals"))
(defn active-file [] (str (goals-root) "/active.edn"))

(defn pid-alive? [pid]
  (try
    (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid))))
    (catch Exception _ false)))

(defn- runs-map
  "Registry content → {[chat-id thread-id] entry}. The legacy single-run
   shape (a bare {:pid ...} map) is re-keyed, so a registry written before
   parallelism upgrades in place; malformed entries (no pid) are dropped."
  [content]
  (let [m (cond
            (and (map? content) (contains? content :pid))
            {[(:chat-id content) (:thread-id content)] content}
            (map? content) content
            :else {})]
    (into {} (filter (comp :pid val)) m)))

(defn read-runs
  "The goal registry, keyed by [chat-id thread-id]. Pure read: dead
   entries stay visible — recovery owns them (pruning on read would
   orphan the restart-announcement path)."
  []
  (let [f (io/file (active-file))]
    (if (.exists f)
      (runs-map (try (edn/read-string (slurp f)) (catch Exception _ nil)))
      {})))

(defn write-runs!
  "Persist the registry; an empty map removes the file."
  [runs]
  (let [f (io/file (active-file))]
    (if (empty? runs)
      (when (.exists f) (fs/delete f))
      (spit f (pr-str runs)))))

(defn register-run!
  "Merge one topic's entry into the registry (launch! registration)."
  [chat-id thread-id entry]
  (write-runs! (assoc (read-runs) [chat-id thread-id] entry)))

(defn unregister-run!
  "Drop the entry whose :name matches — the watcher knows the goal name,
   not its key. No-op when absent."
  [goal-name]
  (let [runs (read-runs)
        kept (into {} (remove (comp #(= goal-name (:name %)) val)) runs)]
    (when (not= kept runs) (write-runs! kept))))

(defn active-run-for
  "The topic's live goal run, if any. A stale entry (dead pid) is cleared —
   same self-healing contract as the pre-parallelism single-slot registry."
  [chat-id thread-id]
  (let [k [chat-id thread-id]
        entry (get (read-runs) k)]
    (when entry
      (if (pid-alive? (:pid entry))
        entry
        (do (write-runs! (dissoc (read-runs) k)) nil)))))
