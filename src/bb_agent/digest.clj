(ns bb-agent.digest
  "Daily owner digest (V7, bk-6476). The roadmap's observability item: the
   system reports itself to the owner once a day, in the owner's DM, via
   the durable outbox — no cron infrastructure, no LaunchAgent to forget.
   The poller's own cycle is the clock: at/after 08:00 local, once per
   calendar day, render stats and enqueue. Drift is fine; silence isn't."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.outbox :as outbox]
            [bb-agent.stats :as stats]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private digest-file
  (delay (io/file (config/home) "state" "digest.edn")))

(defn- read-stamp []
  (let [f @digest-file]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn- write-stamp! [m]
  (fs/create-dirs (.getParent @digest-file))
  (spit @digest-file (pr-str m)))

(defn due?
  "Digest fires at/after 08:00 local, once per calendar day. Pure."
  [{:keys [today hour]} {:keys [last]}]
  (boolean (and (not= today last) (>= (long hour) 8))))

(defn render
  "The digest body from stats/summary — the same facts `bb stats` prints,
   shaped for a message."
  [{:keys [usage outbox goals]}]
  (str "📊 Theseus daily digest\n"
       "tokens: " (or (:tokens/total usage) 0)
       " · events: " (or (:usage/events usage) 0)
       " · ~$" (format "%.2f" (or (:cost/estimate-usd usage) 0.0)) "\n"
       "outbox: " (or (:outbox/pending outbox) 0) " pending, "
       (or (:outbox/dead outbox) 0) " dead\n"
       "goals: " (or (:goal/workspaces goals) 0) " workspaces · "
       (:fulfilled goals 0) " fulfilled, " (:stalled goals 0) " stalled, "
       (:failed goals 0) " failed"
       (when (pos? (:goal/active goals 0))
         (str " · " (:goal/active goals) " active"))))

(defn maybe-digest! [telegram-cfg now]
  "Send today's digest if due. now = map {:today \"2026-09-10\" :hour 9}
   (injectable for tests). Returns the queued id or nil."
  (let [stamp (read-stamp)]
    (when (due? now stamp)
      (let [chat-id (get-in telegram-cfg [:notify :chat-id])
            text (render (stats/summary))
            id (when chat-id
                 (outbox/enqueue! {:telegram telegram-cfg}
                                  {:method "sendMessage"
                                   :params {:chat_id chat-id :text text}
                                   :chat-id chat-id :kind :digest}))]
        (write-stamp! {:last (:today now) :at (str (java.time.Instant/now))})
        id))))
