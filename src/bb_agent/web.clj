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
  One auth gate, one engine, two transports."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]
            [bb-agent.web-turn :as turn]))

(def ^:private static-files
  "Whitelist of servable static assets: exact paths only — a lookup table,
  so path traversal has no grammar to exploit."
  {"/"             ["web/index.html"    "text/html; charset=utf-8"]
   "/index.html"   ["web/index.html"    "text/html; charset=utf-8"]
   "/css/deck.css" ["web/css/deck.css"  "text/css; charset=utf-8"]
   "/js/api.js"    ["web/js/api.js"     "text/javascript; charset=utf-8"]
   "/js/columns.js" ["web/js/columns.js" "text/javascript; charset=utf-8"]
   "/js/cockpit.js" ["web/js/cockpit.js" "text/javascript; charset=utf-8"]
   "/js/main.js"   ["web/js/main.js"    "text/javascript; charset=utf-8"]})

(def ^:private api-routes
  {"/api/status"    data/status
   "/api/turns"     #(data/recent-turns 60)
   "/api/tools"     data/tool-rows
   "/api/rsi"       data/rsi-snapshot
   "/api/sessions"  data/session-rows
   "/api/goals"     data/goal-rows
   "/api/schedules" #(data/schedule-rows 40)})

(defn- bearer [req]
  (some-> (get-in req [:headers "authorization"])
          (str/split #"\s+" 2)
          (second)))

(defn authorized?
  "A live session token in Authorization: Bearer <token>."
  [req]
  (auth/session-live? (bearer req)))

(defn- respond [code body type]
  {:status code
   :headers {"Content-Type" type "Cache-Control" "no-store"}
   :body body})

(defn- json-res [code obj]
  (respond code (json/generate-string obj) "application/json; charset=utf-8"))

(defn- static-res [uri]
  (let [[rel type] (static-files uri)
        path (fs/path (System/getProperty "user.dir") rel)]
    (if (fs/regular-file? path)
      (respond 200 (slurp (str path)) type)
      (respond 404 "not found" "text/plain; charset=utf-8"))))

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
      (json-res 401 {:error "not-set"
                     :hint "on the host: bb scripts/set_web_password.bb"})

      (not (string? password))
      (json-res 400 {:error "missing-password"})

      :else
      (let [session (auth/login! password)]
        (cond
          (:locked session)
          (json-res 429 {:error "rate-limited"
                         :retry-ms (:retry-ms session)})

          session
          (json-res 200 session)

          :else
          (json-res 401 {:error "bad-password"}))))))

(defn- query-params
  "Flat ?k=v&k2=v2 parse with URL decoding — enough for name/mime/id."
  [req]
  (->> (str/split (str (or (:query-string req) "")) #"&")
       (keep (fn [kv]
               (when (seq kv)
                 (let [[k v] (str/split kv #"=" 2)]
                   [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                    (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
       (into {})))

(defn- parse-json-body
  "Body → keyword-keyed map, or nil on anything unparsable."
  [req]
  (try
    (let [v (json/parse-string (slurp (:body req)) true)]
      (when (map? v) v))
    (catch Exception _ nil)))

(defn- guarded
  "The auth + error envelope every cockpit handler rides: 401 without a
  live token, the handler's data as 200 JSON, ex-info :status for
  deliberate failures, 500 otherwise."
  [req handler]
  (if-not (authorized? req)
    (json-res 401 {:error "unauthorized"})
    (try
      (json-res 200 (handler))
      (catch Exception e
        (json-res (or (get (ex-data e) :status) 500)
                  {:error (str (.getMessage e))})))))

(defn- handle-turn
  "POST /api/turn {session, text, attachments} → the queued job."
  [req]
  (let [payload (parse-json-body req)]
    (if-not payload
      (json-res 400 {:error "invalid-json"})
      (guarded req #(turn/submit-turn!
                     {:session (:session payload)
                      :text (:text payload)
                      :attachments (:attachments payload)})))))

(defn- handle-approval
  "POST /api/approval {id, decision} — the buttons on a pending request."
  [req]
  (let [payload (parse-json-body req)]
    (if-not payload
      (json-res 400 {:error "invalid-json"})
      (guarded req (fn []
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
  (let [q (query-params req)
        bytes (try (.readAllBytes (:body req))
                   (catch Exception _ (byte-array 0)))]
    (guarded req #(turn/save-upload! (get q :name) (get q :mime) bytes))))

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

    (and (= :get request-method) (= "/api/session-list" uri))
    (guarded req data/session-list)

    (and (= :get request-method) (= "/api/transcript" uri))
    (let [sid (get (query-params req) :id)]
      (if-not (not-empty sid)
        (json-res 400 {:error "missing-id"})
        (guarded req #(data/transcript sid 60))))

    (and (= :get request-method) (str/starts-with? uri "/api/job/"))
    (let [id (subs uri (count "/api/job/"))]
      (guarded req (fn []
                     (or (turn/job-view id)
                         (throw (ex-info "no such job" {:status 404}))))))

    (contains? api-routes uri)
    (if (authorized? req)
      (try
        (json-res 200 ((api-routes uri)))
        (catch Exception e
          (json-res 500 {:error (str (.getMessage e))})))
      (json-res 401 {:error "unauthorized"}))

    (contains? static-files uri)
    (static-res uri)

    :else (respond 404 "not found" "text/plain; charset=utf-8")))

(defn -main
  [& _]
  (let [port (Integer/parseInt (or (System/getenv "THESEUS_WEB_PORT") "8787"))]
    (server/run-server app {:ip "127.0.0.1" :port port})
    (println "theseus web deck on http://127.0.0.1:" port)
    (println "auth:" (if (auth/password-set?)
                       "master password set"
                       "NO MASTER PASSWORD — run: bb scripts/set_web_password.bb"))
    (flush)
    (deref (promise))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
