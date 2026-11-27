(ns bb-agent.progress-pub
  "Non-blocking 1-slot coalescing progress publisher with public-only boundary
   (ported from LoopForge botinteraction/progress.go). Slow Telegram/Web edits
   never stall tool execution; stale intermediate updates are dropped cleanly."
  (:require [bb-agent.redact :as redact]
            [clojure.string :as str]))

(defn sanitize-public-status
  "Scrub secrets and clamp length so progress lines never leak credentials or
   uncommitted drafts."
  [s]
  (let [clean (-> (str (or s ""))
                  redact/redact-secrets
                  (str/replace #"sk-[A-Za-z0-9_-]{8,}" "[REDACTED]")
                  str/trim)]
    (if (> (count clean) 200)
      (str (subs clean 0 199) "…")
      clean)))

(defn create-publisher
  "Start a non-blocking 1-slot coalescing publisher calling `(deliver-fn status)`."
  [deliver-fn]
  (let [pending (atom nil)
        running? (atom true)
        worker (doto (Thread.
                      (fn []
                        (while @running?
                          (let [next-msg (first (swap-vals! pending (constantly nil)))]
                            (if (some? next-msg)
                              (try (deliver-fn next-msg) (catch Exception _ nil))
                              (try (Thread/sleep 15) (catch InterruptedException _ nil)))))))
                 (.setDaemon true)
                 (.start))]
    {:pending pending :running? running? :worker worker}))

(defn publish!
  "Non-blocking 1-slot publish: overwrites any undelivered stale status."
  [{:keys [pending running?]} status]
  (when @running?
    (reset! pending (sanitize-public-status status)))
  nil)

(defn close!
  "Stop the background publisher thread cleanly."
  [{:keys [running? worker]}]
  (reset! running? false)
  (.interrupt ^Thread worker)
  nil)
