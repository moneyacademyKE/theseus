#!/usr/bin/env bb
;; send_document.bb — one Bot API sendDocument, multipart, from config.
;; Standalone sibling of goal_watch.bb's upload!: any scheduled turn can
;; deliver a file without hand-rolled `bb -e` quoting (which is exactly what
;; killed rsi-report-daily's first delivery — multipart is not a JSON body).
;; Usage: bb scripts/send_document.bb <chat-id> <thread-id-or-dash> <file> <caption>
;; Exit 0 = Telegram accepted it; 1 = rejected; 2 = usage. Receipt on stdout.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[bb-agent.config :as config])

(defn base-url []
  (or (get-in (config/load-config) [:telegram :base-url])
      "https://api.telegram.org"))

(let [[chat thread path caption] *command-line-args*]
  (if (or (nil? chat) (nil? path))
    (do (println "usage: bb scripts/send_document.bb <chat-id> <thread-id|-> <file> <caption>")
        (System/exit 2))
    (let [token   (get-in (config/load-config) [:telegram :token])
          thread* (when (and thread (not= thread "-")) (parse-long thread))
          args    (cond-> ["curl" "-s" "-X" "POST"
                           (str (base-url) "/bot" token "/sendDocument")
                           "-F" (str "chat_id=" chat)
                           "-F" (str "caption=" (or caption ""))
                           "-F" (str "document=@" path)]
                    thread* (concat ["-F" (str "message_thread_id=" thread*)]))
          res     (apply p/shell {:continue true :out :string :err :string} args)
          r       (try (json/parse-string (:out res) true) (catch Exception _ nil))]
      (println (str (java.util.Date.) " sendDocument " path
                    " ok=" (:ok r)
                    " res=" (or (get-in r [:result :document :file_name])
                                (:description r))))
      (System/exit (if (:ok r) 0 1)))))
