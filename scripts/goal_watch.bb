#!/usr/bin/env bb
;; goal_watch.bb — live progress + outcome for a goal run, in its topic.
;; Usage: bb goal_watch.bb <name> <chat-id> <thread-id> <runner-pid>
;; Dependency-free (bb builtins + curl); the progress formatter comes from
;; bb-agent.goal-bridge via the repo classpath. Polls the runner pid; each
;; new iteration ledger updates ONE topic message (sendMessage once, then
;; editMessageText in place); the final edit carries the runner's verdict.

(require '[babashka.process :as p]
         '[bb-agent.config :as config]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[bb-agent.goal-bridge :as gb]
         '[bb-agent.goal.outcomes :as outcomes]
         '[bb-agent.goal.progress :as progress]
         '[bb-agent.goal.registry :as registry])

(def max-polls 480)          ; 480 * 5s = 40 min ceiling
(def poll-ms 5000)

(def args-file* (first *command-line-args*))

(def args* (when args-file*
             (try (edn/read-string (slurp args-file*)) (catch Exception _ nil))))

;; The dispatching process's home rides the args file — this child does NOT
;; trust the ambient env (same contract as goal_launch.bb / goal_resume.bb).
;; The local `home` below already preferred :home, but it only feeds the token
;; and base-url reads: everything that goes through config/home —
;; registry/goals-root, outcomes/queue-outcome!, gb/launch-next-queued!, the
;; topic-scoped registry drop — resolved from OPENCRABS_HOME instead. An e2e
;; run against a fake Bot API server therefore queued `ship-it` into the REAL
;; goals/ and appended a turn to a REAL session file (2026-09-11). The
;; registry writes are worse than litter: they mutate the live run table.
(when-let [h (:home args*)]
  (alter-var-root #'config/home (constantly (fn [] h))))

(defn home []
  "The dispatching process's home rides the args file (:home) — this child
   does NOT trust the ambient env (a test's redef or a launchd override)."
  (or (:home args*)
      (System/getenv "OPENCRABS_HOME")
      (str (System/getProperty "user.home") "/.opencrabs-bb")))

(defn bot-token []
  (let [cfg (edn/read-string (slurp (str (home) "/config.edn")))]
    (get-in cfg [:telegram :token])))

(defn base-url []
  "Honors :base-url — self-hosted gateways and test fake servers ride the
   same ladder as production Telegram."
  (let [cfg (try (edn/read-string (slurp (str (home) "/config.edn")))
                 (catch Exception _ nil))]
    (or (get-in cfg [:telegram :base-url]) "https://api.telegram.org")))

(def max-deliverables 3)
(def max-deliverable-bytes (* 45 1024 1024)) ; Telegram hard-caps at 50 MB

(defn deliverables
  "Declared artifacts that actually exist: config.edn's :deliverables are
   workdir-relative paths. Guards: regular file, ≤ 45 MB, max 3 — a
   declared-but-missing file is skipped, never a launch blocker."
  [ws]
  (when-let [cfg (try (edn/read-string (slurp (str ws "/config.edn")))
                      (catch Exception _ nil))]
    (->> (:deliverables cfg)
         (map (fn [p] (str ws "/" p)))
         (filter (fn [p] (let [f (io/file p)]
                           (and (.isFile f)
                                (<= (.length f) max-deliverable-bytes)))))
         (take max-deliverables))))

(defn upload!
  "sendDocument — a FILE UPLOAD is multipart, not a JSON body. Receipted to
   watch.log like every other Bot API call this watcher makes."
  [token chat-id thread-id path caption ws]
  (let [args (cond-> ["curl" "-s" "-X" "POST"
                      (str (base-url) "/bot" token "/sendDocument")
                      "-F" (str "chat_id=" chat-id)
                      "-F" (str "caption=" caption)
                      "-F" (str "document=@" path)]
               (seq (str thread-id))
               (concat ["-F" (str "message_thread_id=" (parse-long (str thread-id)))]))
        res (apply p/shell {:continue true :out :string :err :string} args)
        r (try (json/parse-string (:out res) true) (catch Exception _ nil))]
    (spit (str ws "/watch.log")
          (str (java.util.Date.) " sendDocument " path
               " ok=" (:ok r) " res=" (or (get-in r [:result :document :file_name])
                                          (:description r)) "\n")
          :append true)
    r))

(defn ship-deliverables!
  "V2 (bk-7496): on fulfillment, the goal's declared artifacts go to the
   topic as documents — goals build files; topics shouldn't get paragraphs."
  [token chat-id thread-id ws name]
  (doseq [path (deliverables ws)]
    (try (upload! token chat-id thread-id path
                  (str "📦 `" name "` deliverable — " (.getName (io/file path))) ws)
         (catch Exception e
           (spit (str ws "/watch.err") (str "deliverable: " (.getMessage e) "\n")
                 :append true)))))

(defn pid-alive? [pid]
  (try (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" pid)))
       (catch Exception _ false)))

(defn api!
  "One Bot API call; parsed JSON response or nil. Every call is receipted to
   the workspace watch.log — a silent watcher is an unobservable watcher."
  [token method body ws]
  (let [payload (str ws "/.watch-payload.json")]
    (spit payload (json/generate-string body))
    (let [res (p/shell {:continue true :out :string :err :string}
                       "curl" "-s" "-X" "POST"
                       (str (base-url) "/bot" token "/" method)
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
                  (str/includes? log "GOAL ALREADY SATISFIED")
                  "📦 GOAL ALREADY SATISFIED — no acts ran; the target already held the goal (evidence below is pre-existing, not this run's work)"
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
      token (bot-token)]
  (when-not (map? args)
    (spit (str (home) "/goals/.watch-last-error")
          (str "bad watcher args: " (pr-str *command-line-args*))))
  (if (not token)
    (spit (str ws "/watch.err") "no telegram token; watcher ran silent")
    (do
      ;; poll the runner pid; each new iteration ledger edits ONE topic
      ;; message in place; the final edit carries the runner's verdict.
      (loop [i 0, seen 0, msg-id nil]
        (if (and (< i max-polls) (pid-alive? pid))
          (do (Thread/sleep poll-ms)
              (let [events (progress/ledger-events ws)
                    n (count events)]
                (if (> n seen)
                  (let [text (progress/progress-text name events)
                        mid (or msg-id
                                (try (post! token chat-id thread-id text ws)
                                     (catch Exception _ nil)))]
                    (when (and msg-id mid)
                      (try (edit! token chat-id msg-id text ws) (catch Exception _ nil)))
                    (recur (inc i) n (or mid msg-id)))
                  (recur (inc i) seen msg-id))))
          ;; runner exited (or ceiling): final verdict — edit if we can, else send.
          ;; The verdict ALSO queues as outcome.edn: the poller's drain turns it
          ;; into a durable session turn (bot messages never persist otherwise).
          (do
            (let [text (final-text name run-log (progress/ledger-events ws))]
              (try (outcomes/queue-outcome! name chat-id (some-> thread-id parse-long) text)
                   (catch Exception e
                     (spit (str ws "/watch.err") (str (.getMessage e) "\n") :append true)))
              (try
                (if msg-id
                  (edit! token chat-id msg-id text ws)
                  (post! token chat-id thread-id text ws))
                (catch Exception e
                  (spit (str ws "/watch.err") (.getMessage e))))
              ;; V2: fulfilled goals ship their DECLARED artifacts to the topic
              (when (str/includes? text "GOAL FULFILLED")
                (ship-deliverables! token chat-id thread-id ws name)))
            ;; V5: a slot just freed — launch the oldest queued goal, if any
            (try (gb/launch-next-queued!) (catch Exception _ nil)))))
      ;; topic-scoped registry: drop ONLY this goal's entry — a raw file delete
      ;; would wipe other topics' live runs (parallelism, 2026-09-08).
      (try (registry/unregister-run! name) (catch Exception _ nil)))))
