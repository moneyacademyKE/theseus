(ns bb-agent.goal.outcomes
  "The goal outcome queue: one outcome.edn per finished run, drained into
   the owning topic's session by the poller. Extracted from goal_bridge.clj
   (bk-c60f, LOC ceiling) — durable terminal state, distinct from the
   active-run registry."
  (:require [babashka.fs :as fs]
            [bb-agent.goal.registry :as registry]
            [bb-agent.session :as session]
            [clojure.edn :as edn]))

(defn- outcome-file [name] (str (registry/goals-root) "/" name "/outcome.edn"))

(defn queue-outcome!
  "One file, one verdict: the durable handoff from any producer (watcher
   at exit, restart recovery, detached resume) to the poller's session
   turn. Consumed exactly once by drain-outcomes!."
  [name chat-id thread-id text]
  (let [ws (str (registry/goals-root) "/" name)]
    (fs/create-dirs ws)
    (spit (outcome-file name)
          (pr-str {:name name :chat-id chat-id :thread-id thread-id :text (str text)}))))

(defn drain-outcomes!
  "Turn every queued goal outcome into a session turn on the chat/topic
   that launched it, then consume the file. Returns the count drained.
   A poison file is skipped (kept for inspection) — polling must survive
   bad bytes in one workspace."
  []
  (if-not (fs/directory? (registry/goals-root))
    0
    (let [files (->> (fs/glob (registry/goals-root) "*/outcome.edn")
                     (filter fs/regular-file?))]
      (count
       (filter identity
               (for [f files]
                 (try
                   (let [{:keys [name chat-id thread-id text]}
                         (edn/read-string (slurp (str f)))
                         sid (cond-> (str "telegram-" chat-id)
                               thread-id (str "-topic-" thread-id))]
                     (session/append-turn!
                      sid {:session/id sid
                           :user/input (str "[goal " name " finished]")
                           :assistant/final (str text)
                           :source :goal-outcome
                           :created/at (str (java.time.Instant/now))})
                     (fs/delete f)
                     true)
                   (catch Exception e
                     (println (str "goal outcome drain failed ["
                                   (.getName (.getClass e)) "] " (.getMessage e)
                                   " @ " (java.time.Instant/now)))
                     (flush)
                     nil))))))))
