(ns bb-agent.state-hygiene
  "Gate on the state/ directory: durable state and known live-runtime
   artifacts only. Scratch (probe scripts, stray logs, .bak files) must
   live in the sibling scratch/ dir — lifetimes do not share a folder.
   Extend durable-entries consciously when adding a new durable file."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def durable-entries
  "Every top-level entry permitted in state/. Three lifetimes:
   durable data (sessions, outbox, ledgers), durable config state
   (offsets, seen-set, memory), and live runtime artifacts (log, pids)."
  #{"sessions" "session-metadata" "session-archive" "cursor-archive"
    "group-context" "telegram-replies" "outbox" "rsi"
    "memory.edn" "session-summaries.edn" "usage.edn" "usage-index.db"
    "telegram-offset.edn" "telegram-seen.edn"
    "telegram-poll.log" "telegram-poller.pid" "gateway.pid"})

(defn violations
  "Top-level entries under state-dir that are not allowlisted durable
   entries. Empty when the dir is clean or absent."
  [state-dir]
  (if-not (fs/directory? state-dir)
    []
    (->> (fs/list-dir state-dir)
         (map #(fs/file-name %))
         (remove durable-entries)
         (remove #(str/starts-with? % "."))
         sort
         vec)))

(def goals-durable-entries
  "Top-level FILES permitted in goals/: the run registry. Everything else
   durable in goals/ is a workspace directory. Scratch files (probe output,
   timelines, payloads) must live in the workspace or scratch/ (bk-b1e2)."
  #{"active.edn"})

(defn goals-violations
  "Top-level FILES under goals-dir that are not the registry. Directories
   are workspaces and pass unconditionally; only files can be scratch.
   Empty when the dir is clean or absent."
  [goals-dir]
  (if-not (fs/directory? goals-dir)
    []
    (->> (fs/list-dir goals-dir)
         (filter fs/regular-file?)
         (map #(fs/file-name %))
         (remove goals-durable-entries)
         (remove #(str/starts-with? % "."))
         sort
         vec)))
