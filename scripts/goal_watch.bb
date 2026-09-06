#!/usr/bin/env bb
;; goal_watch.bb — posts a goal run's outcome to its requesting Telegram topic.
;; Usage: bb goal_watch.bb <name> <chat-id> <thread-id> <runner-pid>
;; Dependency-free (bb builtins + curl): spawned detached by bb-agent.goal-bridge,
;; it must survive and speak without the poller. Polls the runner pid, scrapes
;; run.log for the runner's own verdict words, sends once, exits.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def max-polls 480)          ; 480 * 5s = 40 min ceiling
(def poll-ms 5000)

(defn home [] (or (System/getenv "OPENCRABS_HOME")
                  (str (System/getProperty "user.home") "/.opencrabs-bb")))

(defn bot-token []
  (let [cfg (edn/read-string (slurp (str (home) "/config.edn")))]
    (get-in cfg [:telegram :token])))

(defn pid-alive? [pid]
  (try (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" pid)))
       (catch Exception _ false)))

(defn verdict [run-log]
  (let [s (slurp run-log)]
    (cond
      (str/includes? s "GOAL FULFILLED") "✅ GOAL FULFILLED"
      :else (or (some->> (re-find #"HALT: \S+[^\n]*" s) (str "⛔ "))
                "⏱ watcher gave up waiting (run may still be going)"))))

(defn send! [token chat-id thread-id text]
  (let [payload (str (home) "/goals/.watch-payload.json")
        body (json/generate-string
              (cond-> {:chat_id (parse-long chat-id) :text text}
                (seq thread-id) (assoc :message_thread_id (parse-long thread-id))))]
    (spit payload body)
    (p/shell {:continue true :out :string :err :string}
             "curl" "-s" "-X" "POST"
             (str "https://api.telegram.org/bot" token "/sendMessage")
             "-H" "Content-Type: application/json"
             "-d" (str "@" payload))))

(let [[name chat-id thread-id pid] *command-line-args*
      run-log (str (home) "/goals/" name "/run.log")
      active (str (home) "/goals/active.edn")]
  (loop [i 0]
    (when (and (< i max-polls) (pid-alive? pid))
      (Thread/sleep poll-ms)
      (recur (inc i))))
  (let [token (bot-token)]
    (when token
      (try
        (send! token chat-id thread-id (str "🎯 goal `" name "`: " (verdict run-log)))
        (catch Exception e
          (spit (str (home) "/goals/" name "/watch.err") (.getMessage e)))))
    (when (.exists (io/file active))
      (try (io/delete-file active) (catch Exception _))))))
