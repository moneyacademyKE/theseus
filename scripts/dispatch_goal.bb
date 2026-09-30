#!/usr/bin/env bb
;; dispatch_goal.bb — dispatch one goal spec FILE to the goal runner.
;; Standalone sibling of send_document.bb, same reason: the rsi-learn-daily
;; prompt once embedded a bb -e one-liner with three nesting levels of
;; escaped quotes (prompt string -> EDN -> cron -> sh -> bb -e -> slurp),
;; and every layer was a chance to mangle it. A script file has one level
;; of quoting: its own. Wraps goal-bridge/dispatch-spec! (spec guard,
;; authoring lock, DETACHED authoring — the runner supervises from there).
;; Usage: bb scripts/dispatch_goal.bb <spec-path> [chat-id] [thread-id]
;; Defaults: chat -1003995594829 (Theseus group), thread 5 (RSI topic).
;; Exit 0 = dispatched (reply on stdout); 2 = usage/missing file.

(require '[bb-agent.goal-bridge :as gb])

(let [[spec-path chat thread] *command-line-args*]
  (cond
    (nil? spec-path)
    (do (println "usage: bb scripts/dispatch_goal.bb <spec-path> [chat-id] [thread-id]")
        (System/exit 2))

    (not (.isFile (java.io.File. spec-path)))
    (do (println (str "spec file not found: " spec-path))
        (System/exit 2))

    :else
    (println (gb/dispatch-spec! (slurp spec-path)
                                (parse-long (or chat "-1003995594829"))
                                (parse-long (or thread "5"))))))
