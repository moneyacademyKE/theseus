(ns bb-agent.web
  "Theseus web deck: read-only JSON API over live state plus the static
  column UI in web/. Run with: bb src/bb_agent/web.clj

  Binds 127.0.0.1 only — reach it through the cloudflared tunnel
  (ops/launchd/com.theseus.tunnel.plist). Auth is the master password
  (bb-agent.web-auth): POST /api/login exchanges it for a session token,
  and every /api/* route then requires that token as the Bearer
  credential. The static shell carries no data so it serves ungated. The
  API is strictly read-only: there is no code path from here to a write."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]))

(def ^:private static-files
  "Whitelist of servable static assets: exact paths only — a lookup table,
  so path traversal has no grammar to exploit."
  {"/"             ["web/index.html"    "text/html; charset=utf-8"]
   "/index.html"   ["web/index.html"    "text/html; charset=utf-8"]
   "/css/deck.css" ["web/css/deck.css"  "text/css; charset=utf-8"]
   "/js/api.js"    ["web/js/api.js"     "text/javascript; charset=utf-8"]
   "/js/columns.js" ["web/js/columns.js" "text/javascript; charset=utf-8"]
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
  fresh session token, or bad-password."
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
      (if-let [session (auth/login! password)]
        (json-res 200 session)
        (json-res 401 {:error "bad-password"})))))

(defn app
  "The whole HTTP surface as one pure function request->response."
  [{:keys [uri request-method] :as req}]
  (cond
    (and (= :post request-method) (= "/api/login" uri))
    (handle-login req)

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
