(ns bb-agent.web-routes
  "The deck's management HTTP routes, split out of web.clj (2026-11-10):
  one namespace per concern, the app dispatches here.

  - artifact: authenticated download of web_send_file deliveries
  - goal-detail / goal-control: the Telegram /goal verbs over HTTP
  - schedules: browser CRUD over the same schedule registry the
    scheduler daemon reads (parity row 20)
  - account: logout, password rotation, the optional read-only
    password (parity rows 18-19). Write routes demand the owner role."
  (:require [bb-agent.config :as config]
            [bb-agent.cron :as cron]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.goal.control :as goal-control]
            [bb-agent.goal.registry :as goal-registry]
            [bb-agent.schedule :as schedule]
            [bb-agent.web-artifacts :as artifacts]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]
            [bb-agent.web-http :as http]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn handle-artifact
  "GET /api/artifact/<id> — serve one registered artifact's bytes.
  Auth accepts Bearer OR ?token= (a browser download link cannot set
  headers — the EventSource precedent, this route only). Serve-time
  revalidation: the file must still exist and still resolve under the
  delivery roots."
  [req id]
  (let [token (or (http/bearer req)
                  (some-> (http/query-params req) :token not-empty))]
    (if-not (auth/session-live? token)
      (http/json-res 401 {:error "unauthorized"})
      (if-let [art (artifacts/lookup id)]
        (try
          (let [bytes (java.nio.file.Files/readAllBytes
                       (java.nio.file.Paths/get (:path art)
                                                (into-array String [])))]
            {:status 200
             :headers {"Content-Type" (artifacts/content-type (:name art))
                       "Content-Length" (str (alength bytes))
                       "Content-Disposition"
                       (str "attachment; filename=\"" (:name art) "\"")
                       "Cache-Control" "no-store"
                       "X-Content-Type-Options" "nosniff"}
             :body bytes})
          (catch Exception _
            (http/json-res 404 {:error "artifact-vanished"})))
        (http/json-res 404 {:error "no such artifact"})))))

(defn handle-goal-detail
  "GET /api/goal-detail?id=<slug> — one workspace's operator view."
  [req]
  (let [slug (get (http/query-params req) :id)]
    (if-not (not-empty slug)
      (http/json-res 400 {:error "missing-id"})
      (http/guarded req (fn []
                          (or (data/goal-detail slug)
                              (throw (ex-info "no such goal" {:status 404}))))))))

(defn- goal-workdir
  "The goal's RUN workdir from its workspace config.edn — where control
  files land and where the runner looks for them."
  [slug]
  (let [ws (fs/path (config/home) "goals" (str slug))]
    (when (fs/directory? ws)
      (let [cfg (try (edn/read-string (slurp (str (fs/path ws "config.edn"))))
                     (catch Exception _ nil))]
        (or (when (map? cfg) (:workdir cfg)) (str ws))))))

(defn- goal-registry-entry
  "The goal's registry run entry (pid, owning chat/topic), if any."
  [slug]
  (some (fn [[_ r]] (when (= (str slug) (str (:name r))) r))
        (goal-registry/read-runs)))

(defn handle-goal-control
  "POST /api/goal-control {id, action} — the Telegram /goal verbs over
  HTTP. pause/stop write the same control files `bb goal pause` writes
  (goal.control, into the goal's run workdir); resume/cancel route
  through the goal bridge with the goal's own registered chat/topic."
  [req]
  (let [payload (http/parse-json-body req)]
    (if-not payload
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                          (let [slug (str (:id payload))
                                action (keyword (str (:action payload)))]
                            (when (str/blank? slug)
                              (throw (ex-info "missing id" {:status 400})))
                            (case action
                              (:pause :stop)
                              (if-let [workdir (goal-workdir slug)]
                                (let [result (goal-control/apply-command!
                                              {:workdir workdir} action)]
                                  (assoc result :goal slug))
                                (throw (ex-info "no such goal" {:status 404})))
                              (:resume :cancel)
                              (let [entry (goal-registry-entry slug)]
                                (case action
                                  :resume (goal-bridge/resume!
                                           slug
                                           (or (:chat-id entry) (get-in (config/load-config) [:web :goal-chat-id]))
                                           (or (:thread-id entry) (get-in (config/load-config) [:web :goal-thread-id]))
                                           (fn [& _]))
                                  :cancel (if entry
                                            (goal-bridge/cancel! (:chat-id entry)
                                                                 (:thread-id entry))
                                            (str "No registered run for `" slug
                                                 "` — it is not running."))))
                              (throw (ex-info (str "unknown action: " (:action payload))
                                              {:status 400})))))))))

;; ---------- account: logout, rotation, the read-only role ----------

(defn handle-logout
  "POST /api/logout — revoke THIS session. Any role may log itself out."
  [req]
  (http/guarded req (fn []
                      (auth/logout! (http/bearer req))
                      {:logged-out true})))

(defn handle-rotate-password
  "POST /api/password {current, new} — owner-only. Kills every session,
  returns a fresh owner token so the rotating browser stays logged in."
  [req]
  (let [payload (http/parse-json-body req)]
    (if-not payload
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                                (auth/rotate-password!
                                 {:current (:current payload)
                                  :new (:new payload)}))))))

(defn handle-ro-password
  "POST /api/ro-password {password} — owner-only, defines the read-only
  password. DELETE /api/ro-password removes it. Blank password = clear."
  [req clear?]
  (let [payload (http/parse-json-body req)]
    (if (and (not clear?) (not payload))
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                                (if clear?
                                  (do (auth/set-ro-password! nil)
                                      {:ro-password "cleared"})
                                  (do (auth/set-ro-password! (str (:password payload)))
                                      (if (str/blank? (str (:password payload)))
                                        {:ro-password "cleared"}
                                        {:ro-password "set"}))))))))

;; ---------- schedules: browser CRUD over the shared registry ----------

(defn- valid-cron?
  "5 fields that match at least one minute in the coming year. The same
  matcher the scheduler daemon will evaluate — validation can never
  disagree with execution."
  [expr]
  (boolean (when (string? expr)
             (some? (try (cron/next-match expr (java.time.ZonedDateTime/now))
                         (catch Exception _ nil))))))

(defn handle-schedule-create
  "POST /api/schedule {id, prompt, cron} — owner-only. Same
  add-schedule! the CLI/Telegram lanes use; the daemon sees it on its
  next read."
  [req]
  (let [payload (http/parse-json-body req)]
    (if-not payload
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                                (let [id (str/trim (str (or (:id payload) "")))
                                      prompt (str/trim (str (or (:prompt payload) "")))
                                      cron-expr (some-> (:cron payload) str str/trim)]
                                  (cond
                                    (not (re-matches #"[A-Za-z0-9_-]{1,64}" id))
                                    (throw (ex-info "id must be 1-64 chars of [A-Za-z0-9_-]"
                                                    {:status 400}))
                                    (str/blank? prompt)
                                    (throw (ex-info "prompt must not be empty"
                                                    {:status 400}))
                                    (and cron-expr (not (valid-cron? cron-expr)))
                                    (throw (ex-info "cron must be a 5-field expression"
                                                    {:status 400}))
                                    :else
                                    (schedule/add-schedule! id prompt cron-expr))))))))

(defn handle-schedule-delete
  "DELETE /api/schedule/<id> — owner-only."
  [req id]
  (http/guarded-owner req (fn []
                            (schedule/remove-schedule! (str id))
                            {:deleted (str id)})))