#!/usr/bin/env bb
;; Theseus-native watchdog for the hourly AI-stock brief job.
;;
;; Watches the thing that matters: state.md only advances after a confirmed
;; Telegram send, so its mtime IS the freshness signal. A stale slot gets
;; one clean retry — `bb brief` re-run directly, debounced to once an hour.
;;
;; History: originally lived in ~/.opencrabs/scripts watching the opencrabs
;; cron ledger via `opencrabs cron test`. Rewired 2026-09-10 (bk-02b7): the
;; brief pipeline is now Theseus-owned end to end, so the watchdog calls the
;; Theseus job itself. No opencrabs dependency remains.

(ns watchdog-briefs
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private stale-after-minutes 70)   ; one run takes ~5 min; 70 min = a full missed slot
(def ^:private retry-debounce-minutes 60)

(def ^:private home "/Users/moe/theseus")
(def ^:private state-md (str home "/brain/ai-stock-briefs/state.md"))
(def ^:private lock-path (str home "/state/ai-brief/brief.lock"))
(def ^:private log-path (str home "/state/ai-brief/watchdog.log"))
(def ^:private retry-state-path (str home "/state/ai-brief/retry-state.txt"))
(def ^:private bb-bin "/opt/homebrew/bin/bb")
(def ^:private brief-cwd (str home "/theseus"))

(defn- now-ms [] (System/currentTimeMillis))

(defn- minutes-old [path]
  (when (fs/exists? path)
    (/ (- (now-ms) (.toMillis (fs/last-modified-time path))) 60000.0)))

(defn- emit! [msg]
  (fs/create-dirs (str home "/state/ai-brief"))
  (let [line (str (java.time.OffsetDateTime/now) " " msg)]
    (spit log-path (str line "\n") :append true)
    (println line)))

(defn- read-last-retry []
  (when (fs/exists? retry-state-path)
    (try (Long/parseLong (str/trim (slurp retry-state-path)))
         (catch Exception _ nil))))

(defn- record-retry! [] (spit retry-state-path (str (now-ms))))

(defn- trigger-retry! []
  (let [last-retry (read-last-retry)
        age (when last-retry (/ (- (now-ms) last-retry) 60000.0))]
    (if (and age (< age retry-debounce-minutes))
      (emit! (str "OBSERVE brief job: retry already requested " (int age)
                  " min ago (debounce " retry-debounce-minutes " min)."))
      (do
        (record-retry!)
        (let [{:keys [exit err]} (sh/sh bb-bin "brief"
                                        :dir brief-cwd
                                        :env (assoc (into {} (System/getenv))
                                                    "THESEUS_HOME" home
                                                    "PATH" (str home "/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin")))]
          (emit! (if (zero? exit)
                   "AUTO-REPAIR brief job missed its slot — `bb brief` retry completed."
                   (str "AUTO-REPAIR brief-job retry failed (exit " exit "): "
                        (or (str/trim err) "no stderr")))))))))

(defn check-brief-job! []
  (cond
    (not (fs/exists? state-md))
    (emit! "ALERT brief job: state.md missing — pipeline not initialized.")

    (let [lock-age (minutes-old lock-path)]
      (and lock-age (< lock-age 30)))
    (emit! "OBSERVE brief job: run in flight (fresh lock).")

    :else
    (let [age (minutes-old state-md)]
      (if (and age (< age stale-after-minutes))
        (emit! (str "OBSERVE brief job: healthy — last confirmed send "
                    (int age) " min ago."))
        (do
          (emit! (str "ALERT brief job: state.md "
                      (if age (str (int age) " min old") "unreadable")
                      " — missed slot."))
          (trigger-retry!))))))

(defn -main [& _]
  (check-brief-job!))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
