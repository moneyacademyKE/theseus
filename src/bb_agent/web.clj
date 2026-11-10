(ns bb-agent.web
  "Theseus web deck: the read-only columns plus the interactive cockpit
  (bb-agent.web-turn). Run with: bb src/bb_agent/web.clj

  Binds 127.0.0.1 only — reach it through the cloudflared tunnel
  (ops/launchd/com.theseus.tunnel.plist). Auth is the master password
  (bb-agent.web-auth): POST /api/login exchanges it for a session token
  (rate-limited: 5 misses lock a minute), and every /api/* route then
  requires that token as the Bearer credential. The static shell carries
  no data so it serves ungated.

  The cockpit is WRITE surface, deliberately: POST /api/turn queues a
  real turn on the same engine Telegram rides, POST /api/approval
  resolves pending tool approvals, PUT /api/attachment uploads media.
  One auth gate, one engine, two transports.

  Push: GET /api/events streams the whole deck as Server-Sent Events —
  one channel replaces every client poller. The broadcaster re-reads
  the same readers the JSON routes use and pushes on change; a named
  ping event every 15s keeps the cloudflared tunnel from closing a
  silent pipe AND gives the client a heartbeat it can watchdog."
    (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [bb-agent.config :as config]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]
            [bb-agent.web-http :as http]
            [bb-agent.web-routes :as routes]
            [bb-agent.web-search :as search]
            [bb-agent.web-turn :as turn]))

(def ^:private static-files
  "Whitelist of servable static assets: exact paths only — a lookup table,
  so path traversal has no grammar to exploit."
  {"/"             ["web/index.html"    "text/html; charset=utf-8"]
   "/index.html"   ["web/index.html"    "text/html; charset=utf-8"]
   "/css/deck.css" ["web/css/deck.css"  "text/css; charset=utf-8"]
   "/css/content.css" ["web/css/content.css" "text/css; charset=utf-8"]
   "/css/login.css" ["web/css/login.css" "text/css; charset=utf-8"]
   "/css/cockpit.css" ["web/css/cockpit.css" "text/css; charset=utf-8"]
   "/js/api.js"    ["web/js/api.js"     "text/javascript; charset=utf-8"]
   "/js/columns.js" ["web/js/columns.js" "text/javascript; charset=utf-8"]
   "/js/cockpit.js" ["web/js/cockpit.js" "text/javascript; charset=utf-8"]
   "/js/media.js"   ["web/js/media.js"  "text/javascript; charset=utf-8"]
   "/js/paging.js"  ["web/js/paging.js" "text/javascript; charset=utf-8"]
   "/js/schedules.js" ["web/js/schedules.js" "text/javascript; charset=utf-8"]
   "/js/transcript.js" ["web/js/transcript.js" "text/javascript; charset=utf-8"]
   "/js/main.js"   ["web/js/main.js"    "text/javascript; charset=utf-8"]
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
  "Vendored libraries are content-stable and fat (~100KB): cache them
  immutably for a year, keyed by the ?v= query index.html pins. Our own
  modules are small and change freely — no-cache revalidates on every
  load so a deck refresh always runs fresh code."
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
  "POST /api/login {password: ...} — the only place the password is ever
  read. Answers not-set (nothing to check yet), missing-password, a
  fresh session token, rate-limited, or bad-password."
  [{:keys [body] :as _req}]
  (let [payload  (try (json/parse-string (slurp body) true)
                      (catch Exception _ nil))
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
          (http/json-res 401 {:error "bad-password"}))))))

(def ^:private sse-clients
  "Live SSE channels → fingerprint of the last frame each one received.
  A channel that just joined (or reconnected) has no fingerprint, so
  the very next tick sends it the current state: no client ever waits
  for a change to see data. Unchanged clients skip unchanged frames
  for free."
  (atom {}))

(def ^:private sse-pulse-ms
  "How often the broadcaster re-reads the deck looking for a change."
  1000)

(def ^:private sse-keepalive-ms
  "Comment ping interval: tunnels close pipes that stay silent."
  15000)

(def ^:private job-grace-ms
  "How long a finished job stays in the push so the cockpit sees its
  terminal status before it ages out."
  30000)

(def ^:private broadcaster
  "Broadcaster state: run flag, last ping."
  (atom {:running false :last-ping 0}))

(defn- sse-token
  "EventSource cannot set headers, so the stream route accepts the
  session token as ?token= — this route only, never the whole API."
  [req]
  (or (http/bearer req)
      (some-> (http/query-params req) :token not-empty)))

(defn- sse-write! [ch chunk]
  (try (server/send! ch chunk false) (catch Exception _ nil)))

(defn- sse-close! [ch]
  (try (server/close ch) (catch Exception _ nil)))

(defn- snapshot-jobs
  "Jobs worth pushing: queued/running, or finished inside the grace
  window so the cockpit sees the terminal status."
  []
  (let [now (System/currentTimeMillis)]
    (->> (vals @turn/jobs)
         (filter (fn [j]
                   (or (not (contains? #{:done :failed} (:status j)))
                       (> (+ (or (:finished-at j) 0) job-grace-ms) now)))))))

(defn deck-snapshot
  "The whole deck in one map — the same readers the JSON routes use,
  assembled for push instead of per-request. The per-second :now clock
  is shipped in the payload but excluded from change detection, so the
  deck pushes on real change, not on the clock ticking."
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
  "Read-only JSON surface. /api/state is the full snapshot the stream
  pushes — the poll fallback rides it when EventSource can't hold a
  pipe (proxy interference, ancient browser)."
  {"/api/status"    data/status
   "/api/state"     deck-snapshot
   "/api/usage"     data/usage-series
   "/api/turns"     #(data/recent-turns 60)
   "/api/tools"     data/tool-rows
   "/api/rsi"       data/rsi-snapshot
   "/api/sessions"  data/session-rows
   "/api/goals"     data/goal-rows
   "/api/schedules" #(data/schedule-rows 40)})

(defn- fingerprint
  "Change detector: the snapshot minus the per-second clock."
  [snap]
  (hash (update snap :status dissoc :now)))

(defn- sse-frame [event payload]
  (str "event: " event "\ndata: " payload "\n\n"))

(defn- push-snapshot!
  "One `state` event to every channel whose fingerprint is stale. New
  channels (nil fingerprint) always get the current state — joining
  late is the same as reconnecting: the newest snapshot is the whole
  truth, so there is nothing to replay."
  [snap]
  (let [fp    (fingerprint snap)
        frame (sse-frame "state" (json/generate-string snap))]
    (doseq [[ch sent] @sse-clients]
      (when (not= sent fp)
        (sse-write! ch frame)
        (swap! sse-clients assoc ch fp)))))

(defn- broadcaster-loop!
  "The push engine: re-read the deck every pulse and deliver to every
  channel whose fingerprint moved. Subscribers get their first frame
  on the next tick after joining — snapshots are only ever computed
  here, never on a request thread, so the home redefs the tests rely
  on reach the reads. Exits within one pulse of :running going false."
  []
  (loop []
    (when (:running @broadcaster)
      (try
        (when (seq @sse-clients)
          (push-snapshot! (deck-snapshot)))
        (let [now (System/currentTimeMillis)]
          (when (> (- now (:last-ping @broadcaster)) sse-keepalive-ms)
            (swap! broadcaster assoc :last-ping now)
            ;; a NAMED ping, not a comment: comments keep tunnels open but
            ;; are invisible to EventSource, so the client cannot tell a
            ;; quiet deck from a dead pipe. A ping event is a heartbeat
            ;; the browser can watchdog.
            (doseq [ch (keys @sse-clients)]
              (sse-write! ch (sse-frame "ping" "{}")))))
        (catch Exception _ nil))
      (Thread/sleep sse-pulse-ms)
      (recur))))

(defn- handle-events
  "GET /api/events — the deck's single push channel. A dead token gets
  an `unauthorized` event and a close: EventSource cannot read status
  codes, so the verdict rides as an event — the 200 head is a transport
  handshake, not a login verdict."
  [req]
  (let [headers {"Content-Type" "text/event-stream; charset=utf-8"
                 ;; no-transform: Cloudflare's edge compresses/rewrites
                 ;; text it decides to — and a compressor that doesn't
                 ;; flush per chunk holds the whole stream (issue #36:
                 ;; unauthorized frames passed, authed streams stalled
                 ;; from byte one until the connection closed). The
                 ;; directive is the documented opt-out.
                 "Cache-Control" "no-cache, no-transform"
                 "X-Accel-Buffering" "no"}
        live?   (auth/session-live? (sse-token req))]
  (server/with-channel req ch
    (server/send! ch {:status 200 :headers headers} false)
    ;; pin the browser's reconnect delay: the cross-origin default can
    ;; stretch to minutes; 2s keeps a dropped tunnel from feeling dead.
    (sse-write! ch "retry: 2000\n\n")
    (if-not live?
        (do (sse-write! ch (sse-frame "unauthorized" "{}"))
            (sse-close! ch))
        (do (swap! sse-clients assoc ch nil)
            (server/on-close ch (fn [_] (swap! sse-clients dissoc ch))))))))

(defn- handle-turn
  "POST /api/turn {session, text, attachments} → the queued job. Typed
  approval replies short-circuit the queue entirely (approval-bypass!):
  a blocked lane must never deadlock behind the approval it needs."
  [req]
  (let [payload (http/parse-json-body req)]
    (if-not payload
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                     (if-let [bypass (turn/approval-bypass! (:session payload)
                                                           (:text payload))]
                       (assoc bypass :bypassed true)
                      (turn/submit-turn!
                       {:session (:session payload)
                        :text (:text payload)
                        :attachments (:attachments payload)
                        :edit (boolean (:edit payload))})))))))

(defn- handle-job-control
  "POST /api/job/<id>/cancel | /api/job/<id>/retry — the job's own verbs.
  Returns result maps and throws ex-info for the route envelope (http/guarded
  owns status codes); never builds a response itself."
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
  "POST /api/approval {id, decision} — the buttons on a pending request."
  [req]
  (let [payload (http/parse-json-body req)]
    (if-not payload
      (http/json-res 400 {:error "invalid-json"})
      (http/guarded-owner req (fn []
                     (let [result (turn/decide! (:id payload)
                                                (keyword (str (:decision payload))))]
                       (if-let [err (:error result)]
                         (throw (ex-info err
                                         {:status (if (= "invalid-decision" err) 400 404)}))
                         result)))))))

(defn- handle-upload
  "PUT /api/attachment?name=..&mime=.. with raw bytes → the descriptor.
  save-upload! sanitizes the name and enforces the byte cap."
  [req]
  (let [q (http/query-params req)
        bytes (try (.readAllBytes (:body req))
                   (catch Exception _ (byte-array 0)))]
    (http/guarded-owner req #(turn/save-upload! (get q :name) (get q :mime) bytes))))

(defn app
  "The whole HTTP surface as one pure function request->response."
  [{:keys [uri request-method] :as req}]
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
    (let [{:keys [id page size]} (http/query-params req)]
      (if-not (not-empty id)
        (http/json-res 400 {:error "missing-id"})
        (http/guarded req #(data/transcript id
                                           (or (some-> size Long/parseLong) 60)
                                           (or (some-> page Long/parseLong) 1)))))

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

    (contains? api-routes uri)
    (if (http/authorized? req)
      (try
        (http/json-res 200 ((api-routes uri)))
        (catch Exception e
          (http/json-res 500 {:error (str (.getMessage e))})))
      (http/json-res 401 {:error "unauthorized"}))

    (contains? static-files uri)
    (static-res uri)

    :else (http/respond 404 "not found" "text/plain; charset=utf-8")))

(defn start-deck!
  "Boot the deck on 127.0.0.1: the HTTP surface plus the SSE broadcaster
  thread. Returns a 0-arity stop (tests, embedding); -main boots it and
  parks. The broadcaster thread is created via bound-fn so test-time
  config/home redefs are conveyed into it. Durable sessions reload
  FIRST: a deck restart keeps the browser logged in (parity row 18)."
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