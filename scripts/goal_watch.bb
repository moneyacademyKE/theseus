#!/usr/bin/env bb
;; goal_watch.bb — live progress + outcome for a goal run, in its topic.
;; Usage: bb goal_watch.bb <name> <chat-id> <thread-id> <runner-pid>
;; Dependency-free (bb builtins + curl); the progress formatter comes from
;; bb-agent.goal-bridge via the repo classpath. Polls the runner pid; each
;; new iteration ledger updates ONE topic message (sendMessage once, then
;; editMessageText in place); the final edit carries the runner's verdict.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[bb-agent.goal-bridge :as gb])

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

(defn api!
  "One Bot API call; parsed JSON response or nil. Every call is receipted to
   the workspace watch.log — a silent watcher is an unobservable watcher."
  [token method body ws]
  (let [payload (str (home) "/goals/.watch-payload.json")]
    (spit payload (json/generate-string body))
    (let [res (p/shell {:continue true :out :string :err :string}
                       "curl" "-s" "-X" "POST"
                       (str "https://api.telegram.org/bot" token "/" method)
                       "-H" "Content-Type: application/json"
                       "-d" (str "@" payload))
          r (try (json/parse-string (:out res) true) (catch Exception _ nil))]
      (spit (str ws "/watch.log")
            (str (java.util.Date.) " " method " ok=" (:ok r)
                 " res=" (or (get-in r [:result :message_id])
                             (:description r)
                             (pr-str (subs (str (:out res)) 0 (min 120 (count (str (:out res))))))) "\n")
            :append true)
      r)))

(defn post!
  "Send the first progress message; returns its message_id for later edits."
  [token chat-id thread-id text ws]
  (let [r (api! token "sendMessage"
                (cond-> {:chat_id chat-id :text text}
                  (seq thread-id) (assoc :message_thread_id (parse-long thread-id)))
                ws)]
    (get-in r [:result :message_id])))

(defn edit!
  "Update the progress message in place."
  [token chat-id msg-id text ws]
  (api! token "editMessageText"
        {:chat_id chat-id :message_id msg-id :text text}
        ws))

(defn final-text
  "The runner's own words are the verdict; last ledger's world rides along."
  [name run-log events]
  (let [log (try (slurp run-log) (catch Exception _ ""))
        verdict (cond
                  (str/includes? log "GOAL FULFILLED") "✅ GOAL FULFILLED"
                  :else (or (some->> (re-find #"HALT: \S+[^\n]*" log) (str "⛔ "))
                            "⏱ watcher gave up waiting (run may still be going)"))
        world (some-> (last events) :world pr-str)]
    (str "🎯 goal `" name "`: " verdict
         (when world (str "\nworld " world)))))

(let [args (if-let [f (first *command-line-args*)]
             (try (edn/read-string (slurp f))
                  (catch Exception _ nil))
             nil)
      ;; args arrive as an EDN file (launch! writes watch-args.edn) — never
      ;; shell-joined: an empty thread-id used to collapse the argv and the
      ;; script read the PID as the thread ("message thread not found").
      ;; EDN gives real types (Long chat-id/pid) where argv gave strings —
      ;; normalize once here so every consumer sees strings.
      {:keys [name thread-id pid] :as raw-args} (if (map? args) args {})
      chat-id (some-> (:chat-id raw-args) str parse-long)
      thread-id (some-> thread-id str)
      pid (some-> pid str)
      ws (str (home) "/goals/" name)
      run-log (str ws "/run.log")
      active (str (home) "/goals/active.edn")
      token (bot-token)]
  (when-not (map? args)
    (spit (str (home) "/goals/.watch-last-error")
          (str "bad watcher args: " (pr-str *command-line-args*))))
  (if (not token)
    (spit (str ws "/watch.err") "no telegram token; watcher ran silent")
    (loop [i 0, seen 0, msg-id nil]
      (if (and (< i max-polls) (pid-alive? pid))
        (do (Thread/sleep poll-ms)
            (let [events (gb/ledger-events ws)
                  n (count events)]
              (if (> n seen)
                (let [text (gb/progress-text name events)
                      mid (or msg-id
                              (try (post! token chat-id thread-id text ws)
                                   (catch Exception _ nil)))]
                  (when (and msg-id mid)
                    (try (edit! token chat-id msg-id text ws) (catch Exception _ nil)))
                  (recur (inc i) n (or mid msg-id)))
                (recur (inc i) seen msg-id))))
        ;; runner exited (or ceiling): final verdict — edit if we can, else send
        (let [text (final-text name run-log (gb/ledger-events ws))]
          (try
            (if msg-id
              (edit! token chat-id msg-id text ws)
              (post! token chat-id thread-id text ws))
            (catch Exception e
              (spit (str ws "/watch.err") (.getMessage e))))))))
  (when (.exists (io/file active))
    (try (io/delete-file active) (catch Exception _))))
