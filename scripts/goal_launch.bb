#!/usr/bin/env bb
;; goal_launch.bb — detached authoring + launch of a NEW goal spec (V1a).
;; Usage: bb goal_launch.bb <workspace>/launch-args.edn
;; args {:name :spec :chat-id :thread-id}. The dispatch (bridge/dispatch-spec!)
;; scaffolds and spawns this BEFORE any authoring, so the poll loop/turn
;; returns in milliseconds; authoring runs here with a live progress
;; message edited in place in the owning topic (bk-4876). Never run this
;; on the poll loop. Same dependency-free curl pattern as goal_watch.bb.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[bb-agent.config :as config]
         '[bb-agent.goal-bridge :as gb]
         '[bb-agent.goal.author-progress :as author-progress]
         '[bb-agent.goal.outcomes :as outcomes])

(def args-file* (first *command-line-args*))

(def args* (when args-file*
             (try (edn/read-string (slurp args-file*)) (catch Exception _ nil))))

;; The dispatching process's home rides the args file — this child does NOT
;; trust the ambient env (a test's redef, a launchd override): it reads
;; config from the SAME home that dispatched it.
(when-let [h (:home args*)]
  (alter-var-root #'config/home (constantly (fn [] h))))

(defn api!
  "One Bot API call; parsed JSON or nil. Receipted to the workspace's
   launch.log — a silent progress surface is still observable. Honors the
   config's :base-url (the e2e fake server and self-hosted gateways)."
  [token method body ws]
  (let [payload (str ws "/.launch-payload.json")
        base (or (get-in (config/load-config) [:telegram :base-url])
                 "https://api.telegram.org")]
    (spit payload (json/generate-string body))
    (let [res (p/shell {:continue true :out :string :err :string}
                       "curl" "-s" "-X" "POST"
                       (str base "/bot" token "/" method)
                       "-H" "Content-Type: application/json"
                       "-d" (str "@" payload))
          r (try (json/parse-string (:out res) true) (catch Exception _ nil))]
      (spit (str ws "/launch.log")
            (str (java.util.Date.) " " method " ok=" (:ok r)
                 " res=" (or (get-in r [:result :message_id])
                             (:description r)) "\n")
            :append true)
      r)))

(defn post!
  "The initial progress message; returns its message_id for later edits."
  [token chat-id thread-id text ws]
  (get-in (api! token "sendMessage"
                (cond-> {:chat_id chat-id :text text}
                  (seq (str thread-id)) (assoc :message_thread_id (parse-long (str thread-id))))
                ws)
          [:result :message_id]))

(defn edit!
  "Update the progress message in place."
  [token chat-id msg-id text ws]
  (api! token "editMessageText"
        {:chat_id chat-id :message_id msg-id :text text} ws))

(let [{:keys [name spec chat-id thread-id]} (if (map? args*) args* {})
      ws (if args-file* (.getParent (io/file args-file*)) nil)]
  (when-not (and name spec chat-id ws)
    (binding [*out* *err*]
      (println (str "goal_launch: bad args " (pr-str *command-line-args*))))
    (System/exit 1))
  ;; one authoring process per topic — the detached twin of the registry guard
  (when-not (gb/authoring-lock! chat-id thread-id)
    (outcomes/queue-outcome! name chat-id thread-id
                             "⏳ Another goal in this topic was already authoring — this launch was dropped.")
    (System/exit 0))
  (let [token (get-in (config/load-config) [:telegram :token])
        progress (when (author-progress/enabled?)
                   (author-progress/make-progress-emit
                    {:send! (fn [text] (when-let [id (post! token chat-id thread-id text ws)]
                                         {:message-id id}))
                     :edit! (fn [message-id text] (edit! token chat-id message-id text ws))}))
        reply (try (gb/launch-scaffolded! name ws spec chat-id thread-id (:emit progress))
                   (catch Exception e
                     (str "🚫 Goal authoring failed — " (.getMessage e)
                          "\nWorkspace kept: `" name "` — reply /goal resume " name " to continue.")))]
    (when (:finish! progress)
      ((:finish! progress) reply))
    (outcomes/queue-outcome! name chat-id thread-id reply)
    (gb/authoring-unlock! chat-id thread-id)
    ;; V5: authoring done — a slot freed; start the next queued goal
    (try (gb/launch-next-queued!) (catch Exception _ nil))))
