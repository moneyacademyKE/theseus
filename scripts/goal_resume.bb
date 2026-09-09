#!/usr/bin/env bb
;; goal_resume.bb — detached resume of a goal that died with a poller
;; restart. Usage: bb goal_resume.bb <workspace>/resume-args.edn
;; Calls goal-bridge/resume! (re-authors, re-launches, spawns a fresh
;; watcher) and queues the reply string as the workspace's outcome.edn so
;; the poller's drain records it as a session turn. Never run this on the
;; poll loop — authoring takes minutes.

(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[bb-agent.goal-bridge :as gb]
         '[bb-agent.goal.outcomes :as outcomes])

(defn home [] (or (System/getenv "OPENCRABS_HOME")
                  (str (System/getProperty "user.home") "/.opencrabs-bb")))

(defn fail!
  [ws msg]
  (spit (str ws "/resume.err") (str (java.time.Instant/now) " " msg "\n") :append true)
  (System/exit 1))

(let [args-file (first *command-line-args*)
      args (when args-file
             (try (edn/read-string (slurp args-file)) (catch Exception _ nil)))
      {:keys [name chat-id thread-id]} (if (map? args) args {})
      ws (str (home) "/goals/" name)]
  (when-not (and name chat-id)
    (fail! (or ws (str (home) "/goals")) (str "bad resume args: " (pr-str *command-line-args*))))
  (let [reply (try (gb/resume! name chat-id thread-id nil)
                   (catch Exception e
                     (str "🚫 resume of `" name "` failed: " (.getMessage e))))]
    (outcomes/queue-outcome! name chat-id thread-id
                       (str "🔁 resume report for `" name "`: " reply))))
