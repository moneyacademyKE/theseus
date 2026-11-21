(ns bb-agent.web
  "Theseus web deck: the read-only columns plus the interactive cockpit
  (bb-agent.web-turn). Run with: bb src/bb_agent/web.clj"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-a2a :as a2a]
            [bb-agent.web-data :as data]
            [bb-agent.web-http :as http]
            [bb-agent.web-routes :as routes]
            [bb-agent.web-search :as search]
            [bb-agent.web-turn :as turn]))

(def ^:private max-login-bytes 65536)
(def ^:private max-upload-bytes (* 20 1024 1024))

(def ^:private static-files
  "Whitelist of servable static assets: exact paths only — a lookup table,
  so path traversal has no grammar to exploit."
  {"/"             ["web/index.html"    "text/html; charset=utf-8"]
   "/index.html"   ["web/index.html"    "text/html; charset=utf-8"]
   "/css/deck.css" ["web/css/deck.css"  "text/css; charset=utf-8"]
   "/css/content.css" ["web/css/content.css" "text/css; charset=utf-8"]
   "/css/login.css" ["web/css/login.css" "text/css; charset=utf-8"]
   "/css/cockpit.css" ["web/css/cockpit.css" "text/css; charset=utf-8"]
   "/css/cyberpunk.css" ["web/css/cyberpunk.css" "text/css; charset=utf-8"]
   "/js/api.js"    ["web/js/api.js"     "text/javascript; charset=utf-8"]
   "/js/columns.js" ["web/js/columns.js" "text/javascript; charset=utf-8"]
   "/js/cockpit.js" ["web/js/cockpit.js" "text/javascript; charset=utf-8"]
   "/js/media.js"   ["web/js/media.js"  "text/javascript; charset=utf-8"]
   "/js/paging.js"  ["web/js/paging.js" "text/javascript; charset=utf-8"]
   "/js/schedules.js" ["web/js/schedules.js" "text/javascript; charset=utf-8"]
   "/js/transcript.js" ["web/js/transcript.js" "text/javascript; charset=utf-8"]
   "/js/main.js"   ["web/js/main.js"    "text/javascript; charset=utf-8"]
   "/js/layout.js" ["web/js/layout.js"  "text/javascript; charset=utf-8"]
   "/js/md.js"    ["web/js/md.js"      "text/javascript; charset=utf-8"]
   "/js/search.js" ["web/js/search.js" "text/javascript; charset=utf-8"]
   "/js/state.js" ["web/js/state.js" "text/javascript; charset=utf-8"]
   "/js/pip.js"   ["web/js/pip.js" "text/javascript; charset=utf-8"]
   "/js/time.js"  ["web/js/time.js"    "text/javascript; charset=utf-8"]
   "/js/usage.js" ["web/js/usage.js"   "text/javascript; charset=utf-8"]
   "/vendor/marked.min.js" ["web/vendor/marked.min.js" "text/javascript; charset=utf-8"]
   "/vendor/purify.min.js" ["web/vendor/purify.min.js" "text/javascript; charset=utf-8"]
   "/vendor/highlight.min.js" ["web/vendor/highlight.min.js" "text/javascript; charset=utf-8"]
   "/vendor/highlight-clojure.min.js" ["web/vendor/highlight-clojure.min.js" "text/javascript; charset=utf-8"]
   "/vendor/uplot.min.js"  ["web/vendor/uplot.min.js" "text/javascript; charset=utf-8"]
   "/vendor/uplot.min.css" ["web/vendor/uplot.min.css" "text/css; charset=utf-8"]})

(defn- static-cache-control
  [uri]
  (if (str/starts-with? uri "/vendor/")
    "public, max-age=31536000, immutable"
    "no-cache"))

(defn- static-res [uri]
  (let [[rel type] (static-files uri)
        path (fs/path (System/getProperty "user.dir") rel)]
    (if (fs/regular-file? path)
      (-> (http/respond 200 (slurp (str path)) type)
          (assoc-in [:headers "Cache-Control"] (static-cache-control uri)))
      (http/respond 404 "not found" "text/plain; charset=utf-8"))))

(defn- handle-login
  "POST /api/login {password: ...} — bounded body read, fail-closed until
  password set."
  [req]
  (let [parsed (try {:ok (http/parse-json-body req max-login-bytes)}
                    (catch Exception e
                      {:status (or (get (ex-data e) :status) 400)
                       :error (.getMessage e)}))]
    (if (:status parsed)
      (http/json-res (:status parsed) {:error (or (:error parsed) "bad-request")})
      (let [payload (:ok parsed)
            password (get payload :password)]
        (cond
          (not (auth/password-set?))
          (http/json-res 401 {:error "not-set"
                              :hint "on the host: bb scripts/set_web_password.bb"})

          (not (string? password))
          (http/json-res 400 {:error "missing-password"})

          :else
          (let [session (auth/login! password)]
            (cond
              (:locked session)
              (http/json-res 429 {:error "rate-limited"
                                  :retry-ms (:retry-ms session)})

              session
              (http/json-res 200 session)

              :else
              (http/json-res 401 {:error "bad-password"}))))))))

(def ^:private sse-clients
  "Live SSE channels -> {:token bearer-token :fp last-snapshot-fingerprint}."
  (atom {}))

(def ^:private sse-pulse-ms 1000)
(def ^:private sse-keepalive-ms 15000)
(def ^:private job-grace-ms 30000)

(def ^:private broadcaster
  (atom {:running false :last-ping 0}))

(defn- sse-token
  [req]
  (or (http/bearer req)
      (some-> (http/query-params req) :token not-empty)))

(defn- sse-write! [ch chunk]
  (try (server/send! ch chunk false) (catch Exception _ nil)))

(defn- sse-close! [ch]
  (try (server/close ch) (catch Exception _ nil)))

(defn- sse-frame [event payload]
  (str "event: " event "\ndata: " payload "\n\n"))

(defn- check-sse-client!
  "Re-verify session liveness on an open SSE channel; revoke & close when dead."
  [ch token]
  (if (auth/session-live? token)
    true
    (do (sse-write! ch (sse-frame "unauthorized" "{}"))
        (sse-close! ch)
        (swap! sse-clients dissoc ch)
        false)))

(defn- snapshot-jobs
  []
  (let [now (System/currentTimeMillis)]
    (->> (vals @turn/jobs)
         (filter (fn [j]
                   (or (not (contains? #{:done :failed} (:status j)))
                       (> (+ (or (:finished-at j) 0) job-grace-ms) now)))))))

(defn deck-snapshot
  []
  {:status       (data/status)
   :turns        (data/recent-turns 60)
   :tools        (data/tool-rows)
   :rsi          (data/rsi-snapshot)
   :sessions     (data/session-rows)
   :goals        (data/goal-rows)
   :schedules    (data/schedule-rows 40)
   :session-list (data/session-list)
   :jobs         (into {} (keep (fn [j]
                                  (when-let [v (turn/job-view (:job/id j))]
                                    [(str (:job/id j)) v]))
                                (snapshot-jobs)))})

(def ^:private api-routes
  {"/api/status"    data/status
   "/api/state"     deck-snapshot
   "/api/usage"     data/usage-series
   "/api/turns"     #(data/recent-turns 60)
   "/api/tools"     data/tool-rows
   "/api/rsi"       data/rsi-snapshot
   "/api/sessions"  data/session-rows
   "/api/goals"     data/goal-rows
   "/api/schedules" #(data/schedule-rows 40)})

(defn- fingerprint [snap]
  (hash (update snap :status dissoc :now)))

(defn- prune-and-verify-sse-clients!
  "Drop any SSE channel whose token was logged out, expired, or rotated away."
  []
  (doseq [[ch {:keys [token]}] @sse-clients]
    (check-sse-client! ch token)))

(defn- push-snapshot!
  [snap]
  (let [fp    (fingerprint snap)
        frame (sse-frame "state" (json/generate-string snap))]
    (doseq [[ch {:keys [token fp-sent]}] @sse-clients]
      (when (and (check-sse-client! ch token)
                 (not= fp-sent fp))
        (sse-write! ch frame)
        (swap! sse-clients (fn [m]
                             (if (contains? m ch)
                               (assoc-in m [ch :fp-sent] fp)
                               m)))))))

(defn- broadcaster-loop!
  []
  (loop []
    (when (:running @broadcaster)
      (try
        (when (seq @sse-clients)
          (prune-and-verify-sse-clients!)
          (when (seq @sse-clients)
            (push-snapshot! (deck-snapshot))))
        (let [now (System/currentTimeMillis)]
          (when (> (- now (:last-ping @broadcaster)) sse-keepalive-ms)
            (swap! broadcaster assoc :last-ping now)
            (doseq [[ch {:keys [token]}] @sse-clients]
              (when (check-sse-client! ch token)
                (sse-write! ch (sse-frame "ping" "{}"))))))
        (catch Exception _ nil))
      (Thread/sleep sse-pulse-ms)
      (recur))))

(defn- handle-events
  [req]
  (let [headers {"Content-Type" "text/event-stream; charset=utf-8"
                 "Cache-Control" "no-cache, no-transform"
                 "X-Accel-Buffering" "no"}
        tok     (sse-token req)
        live?   (auth/session-live? tok)]
    (server/with-channel req ch
      (server/send! ch {:status 200 :headers headers} false)
      (sse-write! ch "retry: 2000\n\n")
      (if-not live?
        (do (sse-write! ch (sse-frame "unauthorized" "{}"))
            (sse-close! ch))
        (do (swap! sse-clients assoc ch {:token tok :fp-sent nil})
            (server/on-close ch (fn [_] (swap! sse-clients dissoc ch))))))))

(defn- handle-turn
  [req]
  (http/guarded-owner
   req
   (fn []
     (let [payload (http/parse-json-body req)]
       (when-not payload
         (throw (ex-info "invalid-json" {:status 400})))
       (if-let [bypass (turn/approval-bypass! (:session payload)
                                             (:text payload))]
         (assoc bypass :bypassed true)
         (turn/submit-turn!
          {:session (:session payload)
           :text (:text payload)
           :attachments (:attachments payload)
           :edit (boolean (:edit payload))}))))))

(defn- handle-job-control
  [id verb]
  (let [result (case verb
                 "cancel" (turn/cancel! id)
                 "retry" (turn/retry! id)
                 (throw (ex-info "unknown verb" {:status 404})))]
    (if-let [err (:error result)]
      (throw (ex-info err
                      {:status (case err
                                 "unknown-job" 404
                                 "not-retryable" 409
                                 500)}))
      result)))

(defn- handle-approval
  [req]
  (http/guarded-owner
   req
   (fn []
     (let [payload (http/parse-json-body req)]
       (when-not payload
         (throw (ex-info "invalid-json" {:status 400})))
       (let [result (turn/decide! (:id payload)
                                  (keyword (str (:decision payload))))]
         (if-let [err (:error result)]
           (throw (ex-info err
                           {:status (if (= "invalid-decision" err) 400 404)}))
           result))))))

(defn- handle-upload
  [req]
  (http/guarded-owner
   req
   (fn []
     (let [q (http/query-params req)
           bytes (http/read-bounded-bytes (:body req) max-upload-bytes)]
       (turn/save-upload! (get q :name) (get q :mime) bytes)))))

(defn- parse-long-or
  [s default]
  (if (nil? s)
    default
    (try
      (Long/parseLong (str s))
      (catch NumberFormatException _ ::invalid))))

(defn app
  "The whole HTTP surface as one pure function request->response."
  [{:keys [uri request-method] :as req}]
  (or (a2a/routes req)
      (cond
        (and (= :post request-method) (= "/api/login" uri))
        (handle-login req)

        (and (= :post request-method) (= "/api/turn" uri))
        (handle-turn req)

        (and (= :post request-method) (= "/api/approval" uri))
        (handle-approval req)

        (and (= :put request-method) (= "/api/attachment" uri))
        (handle-upload req)

        (and (= :post request-method) (= "/api/logout" uri))
        (routes/handle-logout req)

        (and (= :post request-method) (= "/api/password" uri))
        (routes/handle-rotate-password req)

        (and (= :post request-method) (= "/api/ro-password" uri))
        (routes/handle-ro-password req false)

        (and (= :delete request-method) (= "/api/ro-password" uri))
        (routes/handle-ro-password req true)

        (and (= :post request-method) (= "/api/schedule" uri))
        (routes/handle-schedule-create req)

        (and (= :delete request-method) (str/starts-with? uri "/api/schedule/"))
        (routes/handle-schedule-delete req (subs uri (count "/api/schedule/")))

        (and (= :get request-method) (= "/api/session-list" uri))
        (http/guarded req data/session-list)

        (and (= :get request-method) (= "/api/search" uri))
        (let [q (get (http/query-params req) :q)]
          (if-not (not-empty q)
            (http/json-res 400 {:error "missing-query"})
            (http/guarded req #(search/search q))))

        (and (= :get request-method) (= "/api/transcript" uri))
        (let [{:keys [id page size]} (http/query-params req)
              parsed-size (parse-long-or size 60)
              parsed-page (parse-long-or page 1)]
          (cond
            (not (not-empty id))
            (http/json-res 400 {:error "missing-id"})

            (or (= ::invalid parsed-size) (= ::invalid parsed-page))
            (http/json-res 400 {:error "invalid-pagination"})

            :else
            (http/guarded req #(data/transcript id parsed-size parsed-page))))

        (and (= :get request-method) (= "/api/goal-detail" uri))
        (routes/handle-goal-detail req)

        (and (= :post request-method) (= "/api/goal-control" uri))
        (routes/handle-goal-control req)

        (and (= :get request-method) (str/starts-with? uri "/api/job/"))
        (let [id (subs uri (count "/api/job/"))]
          (http/guarded req (fn []
                              (or (turn/job-view id)
                                  (throw (ex-info "no such job" {:status 404}))))))

        (and (= :get request-method) (str/starts-with? uri "/api/artifact/"))
        (routes/handle-artifact req (subs uri (count "/api/artifact/")))

        (and (= :post request-method) (re-matches #"/api/job/[^/]+/(cancel|retry)" uri))
        (let [id (subs uri (count "/api/job/") (str/last-index-of uri "/"))
              verb (subs uri (inc (str/last-index-of uri "/")))]
          (http/guarded-owner req #(handle-job-control id verb)))

        (and (= :get request-method) (= "/api/events" uri))
        (handle-events req)

        (and (= :get request-method) (contains? api-routes uri))
        (if (http/authorized? req)
          (try
            (http/json-res 200 ((api-routes uri)))
            (catch Exception e
              (http/json-res 500 {:error (str (.getMessage e))})))
          (http/json-res 401 {:error "unauthorized"}))

        (contains? static-files uri)
        (static-res uri)

        :else (http/respond 404 "not found" "text/plain; charset=utf-8"))))

(defn start-deck!
  [port]
  (auth/load-sessions!)
  (let [stop-http (server/run-server app {:ip "127.0.0.1" :port port})
        thread    (doto (Thread. (bound-fn [] (broadcaster-loop!))
                                 "theseus-sse-broadcast")
                    (.setDaemon true))]
    (reset! broadcaster {:running true
                         :last-ping (System/currentTimeMillis)})
    (.start thread)
    (fn []
      (swap! broadcaster assoc :running false)
      (doseq [ch (keys @sse-clients)] (sse-close! ch))
      (reset! sse-clients {})
      (stop-http))))

(defn -main
  [& _]
  (let [port (Integer/parseInt (or (System/getenv "THESEUS_WEB_PORT") "8787"))
        recovery (turn/recover-lane!)]
    (when (seq (:interrupted recovery))
      (println (str "web lane recovery: " (count (:interrupted recovery))
                    " interrupted job(s) marked — see the cockpit")))
    (start-deck! port)
    (println "theseus web deck on http://127.0.0.1:" port)
    (println "auth:" (if (auth/password-set?)
                       "master password set"
                       "NO MASTER PASSWORD — run: bb scripts/set_web_password.bb"))
    (flush)
    (deref (promise))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
