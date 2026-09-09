(ns relaunch-goal
  "Pure re-launch of a fully-authored goal workspace: promote config, baseline
   commit, spawn runner + watcher, register. No re-authoring — the authored
   artifacts are reused as-is. Usage:
     bb scripts/relaunch_goal.clj <name> <chat-id> <thread-id>")

(require '[bb-agent.goal-bridge :as gb])

(let [[name chat thread] *command-line-args*]
  (assert (and name chat thread) "usage: relaunch_goal.clj <name> <chat-id> <thread-id>")
  (println :launched (gb/launch! name (parse-long chat) (parse-long thread))))
