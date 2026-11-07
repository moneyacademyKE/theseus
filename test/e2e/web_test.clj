(ns e2e.web-test
  "Web deck: read-only JSON over live state. Pure handler tests — the
  whole HTTP surface is one function, request map -> response map, so no
  port and no server are involved. Auth is the master password: POST
  /api/login exchanges it for an in-memory session token, and every
  /api/* route then requires that token. Pinned: fail-closed until the
  password record exists, bad password 401, login mints a usable token,
  wrong / expired / rotated-away sessions 401, real default-iteration
  hashing works in bb, data shapes, static whitelist, 404 elsewhere."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.config :as config]
            [bb-agent.web :as web]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]))

(def ^:private deck-pass "deckpass-9")

(defn- fixture-home!
  ([] (fixture-home! true))
  ([with-password?]
   (let [home (fs/create-temp-dir {:prefix "theseus-web-e2e-"})]
     (fs/create-dirs (fs/path home "state" "rsi"))
     (fs/create-dirs (fs/path home "goals" "goal-a"))
     (when with-password?
       ;; cheap iterations keep the suite fast; login honors the record
       (spit (str (fs/path home ".web-pass.edn"))
             (pr-str (auth/hash-password deck-pass 1000))))
     (spit (str (fs/path home "state" "usage.edn"))
           (pr-str [{:usage/event :turn :created/at "2026-11-07T10:00:00Z"
                     :model "m1" :ok true :session/id "s1"
                     :tokens/total 10 :cost/estimate-usd 0.0}
                    {:usage/event :tool :tool/name :shell
                     :tool/status :ok :ok true :created/at "2026-11-07T10:01:00Z"}
                    {:usage/event :turn :created/at "2026-11-07T10:02:00Z"
                     :model "m2" :ok false :session/id "s1"
                     :tokens/total 20 :cost/estimate-usd 0.0}]))
     (spit (str (fs/path home "state" "schedule-runs.edn"))
           (pr-str [{:schedule/id "sched-x" :status :ok
                     :at "2026-11-07T09:00:00Z" :assistant/final "did the thing"}]))
     (spit (str (fs/path home "state" "rsi" "digest.md") ) "# digest\n\n| tool | calls |\n")
     (spit (str (fs/path home "state" "session-summaries.edn"))
           (pr-str {"s1" {:session/id "s1" :summary "hello  world"
                          :updated/at 2}}))
     (spit (str (fs/path home "goals" "goal-a" "project.edn"))
           (pr-str {:name "goal-a-name"}))
     (spit (str (fs/path home "goals" "goal-a" "run.log")) "line1\nrunning fine\n")
     home)))

(defn- req
  ([uri] (req uri nil))
  ([uri opts]
   (merge {:request-method :get :uri uri :headers {} :query-string nil} opts)))

(defn- body-json [res] (json/parse-string (:body res) true))

(defn- do-login [password]
  (web/app (req "/api/login"
                {:request-method :post
                 :body (java.io.StringReader.
                        (json/generate-string {:password password}))})))

(defn- authed
  "A GET with a freshly minted session token. Must run inside the
  caller's with-redefs: the login reads the fixture home."
  [uri]
  (let [token (:token (body-json (do-login deck-pass)))]
    (req uri {:headers {"authorization" (str "Bearer " token)}})))

(deftest login-fails-closed-until-a-password-is-set
  (let [home (fixture-home! false)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "no record on disk: every login attempt answers not-set"
        (let [res (do-login "anything-at-all")]
          (is (= 401 (:status res)))
          (is (= "not-set" (:error (body-json res))))
          (is (str/includes? (str (:hint (body-json res)))
                             "set_web_password"))))
      (testing "and the API stays locked"
        (is (= 401 (:status (web/app (req "/api/status")))))))))

(deftest login-rejects-wrong-and-malformed-input
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "wrong password"
        (let [res (do-login "wrong-pass-1")]
          (is (= 401 (:status res)))
          (is (= "bad-password" (:error (body-json res))))))
      (testing "missing/garbage body"
        (is (= 400 (:status (web/app (req "/api/login"
                                          {:request-method :post})))))
        (is (= 400 (:status (web/app (req "/api/login"
                                          {:request-method :post
                                           :body (java.io.StringReader. "{oops")})))))))))

(deftest login-mints-a-usable-session-token
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (let [{:keys [token expires] :as res} (body-json (do-login deck-pass))]
        (testing "login answers a 64-hex token with a future expiry"
          (is (= 200 (:status (do-login deck-pass) ) ))
          (is (re-matches #"[0-9a-f]{64}" (str token)))
          (is (> (long expires) (System/currentTimeMillis))))
        (testing "the token opens the API"
          (is (= 200 (:status (web/app (req "/api/status"
                                            {:headers {"authorization"
                                                       (str "Bearer " token)}}))))))
        (testing "a wrong token does not"
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:headers {"authorization"
                                                       "Bearer deadbeef"}}))))))))))

(deftest real-iteration-hashing-works-in-bb
  (let [home (fs/create-temp-dir {:prefix "theseus-web-realiter-"})]
    (auth/reset-sessions!)
    (spit (str (fs/path home ".web-pass.edn"))
          (pr-str (auth/hash-password "realpass-99"))) ; 600000, the default
    (with-redefs [config/home (fn [] (str home))]
      (is (= 200 (:status (do-login "realpass-99"))))
      (is (= 401 (:status (do-login "realpass-98")))))))

(deftest api-requires-a-live-session
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (is (= 401 (:status (web/app (req "/api/turns")))))
      (is (= 401 (:status (web/app (req "/api/tools")))))
      (is (= "unauthorized" (:error (body-json (web/app (req "/api/status"))))))
      (testing "the old ?token= convenience is gone — query params do nothing"
        (is (= 401 (:status (web/app (req "/api/status"
                                          {:query-string "token=anything"})))))))))

(deftest expired-and-rotated-sessions-are-rejected
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "expiry: age the token past its TTL and it dies"
        (let [token (:token (body-json (do-login deck-pass)))]
          (swap! auth/sessions (fn [m]
                                 (assoc m token (- (get m token)
                                                   (* 2 auth/session-ttl-ms)))))
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:headers {"authorization"
                                                       (str "Bearer " token)}})))))))
      (testing "rotation: a new password kills every old session"
        (let [token (:token (body-json (do-login deck-pass)))]
          (auth/set-password! "new-pass-99")
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:headers {"authorization"
                                                       (str "Bearer " token)}})))))
          (is (= 401 (:status (do-login deck-pass))))
          (is (= 200 (:status (do-login "new-pass-99"))))
          (let [perms (str (fs/posix-file-permissions (fs/path home ".web-pass.edn")))]
            (is (str/includes? perms "OWNER_READ"))
            (is (str/includes? perms "OWNER_WRITE"))
            (is (not (str/includes? perms "GROUP")))))))))

(deftest api-serves-live-state-with-a-session
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "turns: newest first, tool events excluded"
        (let [turns (body-json (web/app (authed "/api/turns")))]
          (is (= 200 (:status (web/app (authed "/api/turns")))))
          (is (= 2 (count turns)))
          (is (= "m2" (:model (first turns))))
          (is (false? (:ok (first turns))))
          (is (= "m1" (:model (second turns))))))
      (testing "tools: funnel aggregate rows"
        (let [rows (body-json (web/app (authed "/api/tools")))]
          (is (= 1 (count rows)))
          (is (= "shell" (:tool (first rows))))
          (is (= 1 (:calls (first rows))))
          (is (= 1 (:ok (first rows))))
          (is (= 0 (:fail (first rows))))
          (is (= 0 (:denied (first rows))))))
      (testing "goals: name from project.edn, last log line"
        (let [rows (body-json (web/app (authed "/api/goals")))]
          (is (= 1 (count rows)))
          (is (= "goal-a-name" (:name (first rows))))
          (is (= "running fine" (:last (first rows))))
          (is (false? (:active (first rows))))))
      (testing "schedules"
        (let [rows (body-json (web/app (authed "/api/schedules")))]
          (is (= 1 (count rows)))
          (is (= "sched-x" (:id (first rows))))
          (is (= "ok" (:status (first rows))))
          (is (= "did the thing" (:final (first rows))))))
      (testing "sessions"
        (let [rows (body-json (web/app (authed "/api/sessions")))]
          (is (= 1 (count rows)))
          (is (= "s1" (:id (first rows))))
          (is (= "hello world" (:summary (first rows))))))
      (testing "rsi digest snapshot"
        (let [snap (body-json (web/app (authed "/api/rsi")))]
          (is (str/includes? (:md snap) "# digest"))
          (is (number? (:ms snap)))))
      (testing "status: poller down (no pid file), ledger counts"
        (let [st (body-json (web/app (authed "/api/status")))]
          (is (false? (get-in st [:poller :alive])))
          (is (= 3 (:events st)))
          (is (= 1 (:tool-events st)))
          (is (= "2026-11-07T10:02:00Z" (:last-event st))))))))

(deftest static-serves-from-whitelist-only
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "the shell and assets serve ungated (they carry no data)"
        (let [html (web/app (req "/"))]
          (is (= 200 (:status html)))
          (is (str/includes? (:body html) "THESEUS"))
          (is (str/includes? (:body html) "master password"))
          (is (str/includes? (get-in html [:headers "Content-Type"]) "text/html")))
        (is (= 200 (:status (web/app (req "/css/deck.css")))))
        (is (= 200 (:status (web/app (req "/js/main.js"))))))
      (testing "anything outside the whitelist is 404 — no grammar for traversal"
        (is (= 404 (:status (web/app (req "/etc/passwd")))))
        (is (= 404 (:status (web/app (req "/../../etc/passwd")))))
        (is (= 404 (:status (web/app (req "/api/nope")))))
        (is (= 404 (:status (web/app (req "/logs/usage.edn")))))
        (is (= 404 (:status (web/app (req "/api/login"
                                          {:request-method :get})))))))))
