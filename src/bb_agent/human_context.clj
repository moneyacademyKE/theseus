(ns bb-agent.human-context
  "Atomic human-context.md sidecar & baseline-align conflict pause
   (ported from LoopForge engine/human_context.go + engine/align.go).
   Persists mid-run human guidance atomically (<=8 KB) so retries and parallel
   subagents never see truncated operator context."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [clojure.string :as str]))

(def max-human-context-bytes 8000)

(defn- clip-bytes [s]
  (let [t (str/trim (str (or s "")))]
    (if (> (count t) max-human-context-bytes)
      (subs t 0 max-human-context-bytes)
      t)))

(defn compose-feedback
  "Combine a retry/rework reason with bounded operator context."
  [reason context]
  (let [r (str/trim (str (or reason "")))
        c (clip-bytes context)]
    (cond
      (str/blank? c) r
      (str/blank? r) (str "Operator context:\n" c)
      :else (str r "\n\nOperator context:\n" c))))

(defn write-human-context!
  "Atomically write <workspace>/human-context.md via atomic temp+rename."
  [workspace note]
  (let [n (clip-bytes note)]
    (when (and (not (str/blank? n)) (not (str/blank? (str workspace))))
      (let [target (fs/path workspace "human-context.md")
            body (str "# Human Context\n\n" n "\n")]
        (when (not= (when (fs/regular-file? target) (slurp (str target))) body)
          (aio/write-atomic-file! target body))
        (str target)))))

(defn read-human-context
  "Read <workspace>/human-context.md if present, else nil."
  [workspace]
  (when (and workspace (not (str/blank? (str workspace))))
    (let [target (fs/path workspace "human-context.md")]
      (when (fs/regular-file? target)
        (slurp (str target))))))

(defn baseline-align-paused?
  "True when a repository's integration branch has unresolved conflict blocks
   against its default branch, signalling scheduled dispatches to pause."
  [{:keys [conflict-blocks]}]
  (boolean (seq conflict-blocks)))
