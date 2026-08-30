(ns bb-agent.cli
  (:require [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.daemon :as daemon]
            [bb-agent.doctor :as doctor]
            [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.rich :as rich]
            [bb-agent.schedule :as schedule]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.session :as session]
            [bb-agent.slack :as slack]
            [bb-agent.telegram :as telegram]
            [bb-agent.ui :as ui]
            [bb-agent.usage :as usage]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- usage! [message exit-code]
  (binding [*out* *err*]
    (println message))
  (System/exit exit-code))

(defn- handle-agent-command [args]
  (let [ask? (= "--ask" (first args))
        prompt-args (if ask? (rest args) args)
        prompt (str/join " " prompt-args)]
    (if (str/blank? prompt)
      (usage! "Usage: bb agent [--ask] \"say pong\"" 2)
      (try
        (let [cfg (cond-> (config/load-config)
                    ask? (assoc :approval/ask (approval/interactive-approver)))
              turn (core/run-turn! cfg prompt)]
          (println (rich/terminal (rich/final-message (:assistant/final turn)))))
        (catch Exception e
           (usage! (str "Error: " (ex-message e)) 1))))))

(defn- handle-session-command [args]
  (let [[subcommand & rest-args] args
        cfg (config/load-config)]
    (case subcommand
      "list"
      (doseq [entry (session/list-metadata)]
        (println (pr-str entry)))

      "current"
      (println (pr-str (or (session/load-metadata (:session/id cfg))
                           (session/touch-metadata! (:session/id cfg) cfg))))

      "set-cwd"
      (let [[session-id cwd] rest-args]
        (if (or (str/blank? session-id) (str/blank? cwd))
          (usage! "Usage: bb session set-cwd <session-id> <cwd>" 2)
          (println (pr-str (session/set-cwd! session-id cwd)))))

      (usage! "Usage: bb session list | bb session current | bb session set-cwd <session-id> <cwd>" 2))))

(defn- handle-usage-command [args]
  (let [[subcommand] args]
    (case subcommand
      "report" (println (pr-str (usage/report)))
      (usage! "Usage: bb usage report" 2))))

(defn- handle-memory-command [args]
  (let [[subcommand & rest-args] args
        payload (str/join " " rest-args)]
    (case subcommand
      "add"
      (if (str/blank? payload)
        (usage! "Usage: bb memory add <text>" 2)
        (let [entry (memory/add-memory! payload)]
          (println (:memory/text entry))))

      "search"
      (if (str/blank? payload)
        (usage! "Usage: bb memory search <query>" 2)
        (doseq [entry (memory/search-memories payload 10)]
          (println (:memory/text entry))))

      "curate"
      (let [[id] rest-args]
        (if (str/blank? id)
          (usage! "Usage: bb memory curate <id>" 2)
          (try
            (let [entry (memory/curate-memory! id)]
              (println "Curated:" (:memory/text entry)))
            (catch Exception e
              (usage! (ex-message e) 1)))))

      "index-session"
      (let [[session-id] rest-args]
        (if (str/blank? session-id)
          (usage! "Usage: bb memory index-session <session-id>" 2)
          (if-let [record (semantic-memory/index-session! session-id)]
            (println (:summary record))
            (println "No turns to index for session:" session-id))))

      "semantic-search"
      (if (str/blank? payload)
        (usage! "Usage: bb memory semantic-search <query>" 2)
        (doseq [hit (semantic-memory/semantic-search payload {:top-k 10})]
          (println (format "%.3f" (double (:score hit)))
                   "- [Session" (:session/id hit) "]:"
                   (:summary hit))))

      (usage! "Usage: bb memory add <text> | bb memory search <query> | bb memory curate <id> | bb memory index-session <id> | bb memory semantic-search <query>" 2))))

(defn- handle-model-command [args]
  (let [[subcommand & rest-args] args]
    (case subcommand
      "set"
      (let [[session-id provider model-id] rest-args]
        (if (or (str/blank? session-id) (str/blank? provider) (str/blank? model-id))
          (usage! "Usage: bb model set <session-id> <provider> <model>" 2)
          (println (pr-str (model/save-session-model! session-id provider model-id)))))

      "current"
      (let [[session-id] rest-args]
        (if (str/blank? session-id)
          (usage! "Usage: bb model current <session-id>" 2)
          (println (pr-str (or (model/load-session-model session-id)
                              (select-keys (assoc (config/load-config) :session/id session-id)
                                           [:session/id :provider :model]))))))

      (usage! "Usage: bb model set <session-id> <provider> <model> | bb model current <session-id>" 2))))

(defn- print-schedules [entries]
  (doseq [entry entries]
    (println (pr-str entry))))

(defn- handle-schedule-command [args]
  (let [[subcommand & rest-args] args
        cfg (config/load-config)]
    (case subcommand
      "add"
      (let [[schedule-id & prompt-parts] rest-args
            prompt (str/join " " prompt-parts)]
        (if (or (str/blank? schedule-id) (str/blank? prompt))
          (usage! "Usage: bb schedule add <id> <prompt>" 2)
          (println (pr-str (schedule/add-schedule! schedule-id prompt)))))

      "list"
      (print-schedules (schedule/load-schedules))

      "remove"
      (let [[schedule-id] rest-args]
        (if (str/blank? schedule-id)
          (usage! "Usage: bb schedule remove <id>" 2)
          (print-schedules (schedule/remove-schedule! schedule-id))))

      "run"
      (let [[schedule-id] rest-args]
        (if (str/blank? schedule-id)
          (usage! "Usage: bb schedule run <id>" 2)
          (println (:assistant/final (schedule/run-schedule! cfg schedule-id)))))

      (usage! "Usage: bb schedule add <id> <prompt> | bb schedule list | bb schedule remove <id> | bb schedule run <id>" 2))))

(defn- handle-daemon-command [args]
  (let [[subcommand & rest-args] args]
    (case subcommand
      "start"
      (let [opts (set rest-args)
            value-after (fn [flag]
                          (second (drop-while #(not= flag %) rest-args)))
            once? (contains? opts "--once")
            max-runs (some-> (value-after "--max-runs") parse-long)
            interval-ms (some-> (value-after "--interval-ms") parse-long)
            {:keys [cycles runs]} (daemon/start! {:once? once?
                                                  :max-runs max-runs
                                                  :interval-ms interval-ms})]
        (println (str "daemon-cycles=" cycles " daemon-ran=" runs)))

      (usage! "Usage: bb daemon start [--once] [--max-runs n] [--interval-ms n]" 2))))

(defn- handle-telegram-command [args]
  (let [[subcommand] args]
    (case subcommand
      "poll-once"
      (let [{:keys [updates]} (telegram/poll-once!)]
        (println (str "telegram-updates=" updates)))

      (usage! "Usage: bb telegram poll-once" 2))))

(defn- handle-slack-command [args]
  (let [[subcommand] args]
    (case subcommand
      "poll-once"
      (let [{:keys [events]} (slack/poll-once!)]
        (println (str "slack-events=" events)))

      (usage! "Usage: bb slack poll-once" 2))))

(defn- handle-ui-command [args]
  (let [[subcommand] args]
    (case subcommand
      "status" (println (ui/render-status))
      (usage! "Usage: bb ui status" 2))))

(defn- handle-config-command [args]
  (let [[subcommand & rest-args] args]
    (case subcommand
      "doctor"
      (let [checks (doctor/run-checks)]
        (doseq [check checks]
          (println (doctor/format-check check)))
        (when (doctor/has-errors? checks)
          (System/exit 1)))

      "apply"
      (let [[candidate-path] rest-args]
        (if (str/blank? candidate-path)
          (usage! "Usage: bb config apply <candidate-file>" 2)
          (try
            (let [candidate (edn/read-string (slurp candidate-path))
                  check (doctor/check-provider-config candidate)]
              (if (= :error (:status check))
                (do (println (doctor/format-check check))
                    (System/exit 1))
                (let [applied (config/write-config! candidate)]
                  (println (str "applied " candidate-path
                                " -> " (:provider applied) "/" (:model applied)
                                " (previous kept in config.last-good.edn)")))))
            (catch Exception e
              (usage! (str "Invalid candidate: " (ex-message e)) 1)))))

      "restore-last-good"
      (try
        (let [cfg (config/restore-last-good!)]
          (println (str "restored config.last-good.edn -> "
                        (:provider cfg) "/" (:model cfg))))
        (catch Exception e
          (usage! (ex-message e) 1)))

      (usage! "Usage: bb config doctor | bb config apply <file> | bb config restore-last-good" 2))))

(defn -main [& args]
  (let [[command & rest-args] (if (= 1 (count args))
                                (let [only (first args)]
                                  (if (sequential? only) only args))
                                args)]
    (case command
      "memory" (handle-memory-command rest-args)
      "session" (handle-session-command rest-args)
      "usage" (handle-usage-command rest-args)
      "model" (handle-model-command rest-args)
      "config" (handle-config-command rest-args)
      "doctor" (handle-config-command (cons "doctor" rest-args))
      "schedule" (handle-schedule-command rest-args)
      "daemon" (handle-daemon-command rest-args)
      "telegram" (handle-telegram-command rest-args)
      "slack" (handle-slack-command rest-args)
      "ui" (handle-ui-command rest-args)
      (handle-agent-command (if command (cons command rest-args) [])))))
