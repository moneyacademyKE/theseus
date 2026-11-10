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
            [babashka.process :refer [sh]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.provider :as provider]
            [bb-agent.session :as session]
            [bb-agent.schedule :as schedule]
            [bb-agent.web :as web]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]
            [bb-agent.web-lane :as lane]
            [bb-agent.tool :as tool]
            [bb-agent.goal-bridge :as goal-bridge]
            [bb-agent.web-artifacts :as artifacts]
            [bb-agent.web-turn :as turn]))

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
                     :model "m1" :ok true :session/id "s1" :provider :openai-compatible
                     :tokens/total 10 :cost/estimate-usd 0.0}
                    {:usage/event :tool :tool/name :shell
                     :tool/status :ok :ok true :created/at "2026-11-07T10:01:00Z"}
                    {:usage/event :turn :created/at "2026-11-07T10:02:00Z"
                     :model "m2" :ok false :session/id "s1" :provider :openai-compatible
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
                                 (update m token assoc :expires
                                         (- (:expires (get m token))
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
          (is (= "2026-11-07T10:02:00Z" (:last-event st)))))
      (testing "state: the whole deck in one payload — poll-fallback surface"
        (let [st (body-json (web/app (authed "/api/state")))]
          (doseq [k [:status :turns :tools :rsi :sessions :goals
                     :schedules :session-list :jobs]]
            (is (contains? st k) (str "missing " k)))))
      (testing "usage: zero-filled daily buckets over the fixture ledger"
        (let [rows (body-json (web/app (authed "/api/usage")))]
          (is (= 30 (count rows)))
          (is (zero? (get-in rows [29 :turns] 0))
              "today has no fixture turns — zero bar")
          (is (= 2 (reduce + (map :turns rows)))
              "the two fixture turns (Nov 7) land inside the 30-day window")
          (is (every? #(re-matches #"\d{4}-\d{2}-\d{2}" (:date %)) rows)
              "dates are yyyy-mm-dd"))))))

(deftest static-serves-from-whitelist-only
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "the shell and assets serve ungated (they carry no data)"
        (let [html (web/app (req "/"))]
          (is (= 200 (:status html)))
          (is (str/includes? (:body html) "THESEUS"))
          (is (str/includes? (:body html) "master password"))
          (is (str/includes? (:body html) "role=\"status\""))
          (is (str/includes? (:body html) "data-conn=\"connecting\""))
          (is (str/includes? (:body html) "aria-live=\"polite\""))
          (is (str/includes? (:body html) "aria-label=\"push stream offline\""))
          (is (str/includes? (:body html) "<dialog id=\"login\""))
          (is (str/includes? (:body html) "method=\"dialog\""))
          (is (str/includes? (:body html) "aria-labelledby=\"login-title\""))
          (is (str/includes? (:body html) "for=\"login-pass\""))
          (is (str/includes? (:body html) "role=\"alert\""))
          (is (str/includes? (:body html) "for=\"cockpit-session\""))
          (is (str/includes? (:body html) "role=\"log\""))
          (is (str/includes? (:body html) "for=\"cockpit-text\""))
          (is (str/includes? (:body html) "id=\"cockpit-error\""))
          (is (str/includes? (get-in html [:headers "Content-Type"]) "text/html")))
        (doseq [uri ["/css/deck.css" "/css/content.css" "/css/login.css"
                     "/css/cockpit.css" "/js/main.js"]]
          (is (= 200 (:status (web/app (req uri)))) uri)))
      (testing "anything outside the whitelist is 404 — no grammar for traversal"
        (is (= 404 (:status (web/app (req "/etc/passwd")))))
        (is (= 404 (:status (web/app (req "/../../etc/passwd")))))
        (is (= 404 (:status (web/app (req "/api/nope")))))
        (is (= 404 (:status (web/app (req "/logs/usage.edn")))))
        (is (= 404 (:status (web/app (req "/api/login"
                                          {:request-method :get})))))))
      (testing "every module the shell imports serves — the md.js 404 lesson"
        (doseq [uri ["/js/api.js" "/js/columns.js" "/js/cockpit.js"
                     "/js/md.js" "/js/time.js" "/js/usage.js" "/js/search.js" "/js/state.js" "/js/pip.js" "/js/main.js"
                     "/vendor/marked.min.js" "/vendor/purify.min.js"
                     "/vendor/uplot.min.js" "/vendor/uplot.min.css"
                     "/vendor/highlight.min.js" "/vendor/highlight-clojure.min.js"]]
          (let [res (web/app (req uri))]
            (is (= 200 (:status res)) uri)
            (is (not (str/blank? (:body res))) uri))))
      (testing "vendor assets carry the immutable cache contract"
        (is (= "public, max-age=31536000, immutable"
               (get-in (web/app (req "/vendor/uplot.min.js"))
                       [:headers "Cache-Control"]))))))

;; ---------- cockpit: the interactive lane ----------

(defn- wait-for-job
  "Poll the job registry until :done/:failed or timeout. The lane runs
  on a real executor thread; with-redefs rides the root binding, so the
  job sees the fixture home."
  [id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [job (turn/job-view id)]
        (if (or (nil? job) (contains? #{:done :failed} (:status job)))
          job
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 50) (recur))
            ::timeout))))))

(defn- post-turn [token payload]
  (web/app (req "/api/turn"
                {:request-method :post
                 :headers {"authorization" (str "Bearer " token)}
                 :body (java.io.StringReader. (json/generate-string payload))})))

(deftest cockpit-runs-a-real-turn-through-the-engine
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (let [token (:token (body-json (do-login deck-pass)))
            res (post-turn token {:session "web-e2e" :text "say pong"})]
        (testing "submit answers 200 with a job id"
          (is (= 200 (:status res)))
          (is (string? (:job/id (body-json res)))))
        (let [job (wait-for-job (:job/id (body-json res)) 20000)]
          (testing "the turn runs on the shared engine and finishes"
            (is (map? job))
            (is (= :done (:status job)))
            (is (= "pong" (:final job))))
          (testing "the transcript endpoint serves the persisted session turn"
            (let [get-res (web/app (req "/api/transcript"
                                        {:headers {"authorization" (str "Bearer " token)}
                                         :query-string "id=web-e2e"}))
                  env (body-json get-res)]
              (is (= 200 (:status get-res)))
              (is (= 1 (:total env)))
              (is (= 1 (count (:rows env))))
              (is (= "say pong" (:input (first (:rows env)))))
              (is (= "pong" (:final (first (:rows env)))))))
          (testing "the session appears in the live session list"
            (let [rows (body-json (web/app (req "/api/session-list"
                                                {:headers {"authorization" (str "Bearer " token)}})))]
              (is (some #(= "web-e2e" (:id %)) rows))))
          (testing "fake-provider turns stay out of the usage ledger"
            (is (= 3 (:events (body-json (web/app (authed "/api/status"))))))))))))

(deftest cockpit-auth-gates-the-write-routes
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "no token: turn, approval, upload, readers all refuse"
        (is (= 401 (:status (web/app (req "/api/turn"
                                          {:request-method :post
                                           :body (java.io.StringReader. "{}")})))))
        (is (= 401 (:status (web/app (req "/api/approval"
                                          {:request-method :post
                                           :body (java.io.StringReader. "{}")})))))
        (is (= 401 (:status (web/app (req "/api/attachment"
                                          {:request-method :put})))))
        (is (= 401 (:status (web/app (req "/api/session-list")))))
        (is (= 401 (:status (web/app (req "/api/transcript"
                                          {:query-string "id=x"})))))))))

(deftest cockpit-approval-decisions-resolve-pending-requests
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (let [token (:token (body-json (do-login deck-pass)))
            pending (approval/create-pending! "web-appr" :web
                                              {:tool/name "shell"
                                               :tool/args {:cmd "ls"}})]
        (swap! turn/jobs assoc "job-appr"
               {:job/id "job-appr" :session/id "web-appr" :status :running
                :events [] :approval {:id (:approval/id pending)
                                      :tool "shell" :context "ls"}})
        (testing "the decision POST resolves through approvals.edn"
          (let [res (web/app (req "/api/approval"
                                  {:request-method :post
                                   :headers {"authorization" (str "Bearer " token)}
                                   :body (java.io.StringReader.
                                          (json/generate-string
                                           {:id (:approval/id pending)
                                            :decision "approve"}))}))]
            (is (= 200 (:status res)))
            (is (= "approve" (:decision (body-json res)))))
          (is (= :approve (get-in (approval/load-state)
                                  [:decisions (:approval/id pending)])))
          (is (nil? (:approval (turn/job-view "job-appr")))))
        (testing "a second decision on the same id is honestly refused"
          (is (= 404 (:status (web/app (req "/api/approval"
                                            {:request-method :post
                                             :headers {"authorization" (str "Bearer " token)}
                                             :body (java.io.StringReader.
                                                    (json/generate-string
                                                     {:id (:approval/id pending)
                                                      :decision "deny"}))})))))
        (testing "garbage decisions are refused without touching state"
          (is (= 400 (:status (web/app (req "/api/approval"
                                            {:request-method :post
                                             :headers {"authorization" (str "Bearer " token)}
                                             :body (java.io.StringReader.
                                                    (json/generate-string
                                                     {:id "nope" :decision "maybe"}))})))))
        (testing "an unknown approval id maps to 404, not a crash"
          (is (= 404 (:status (web/app (req "/api/approval"
                                            {:request-method :post
                                             :headers {"authorization" (str "Bearer " token)}
                                             :body (java.io.StringReader.
                                                    (json/generate-string
                                                     {:id "ghost" :decision "deny"}))}))))))))))))

(deftest cockpit-uploads-sanitize-and-derive-kind
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (let [img (turn/save-upload! "../../secrets/token.png" "image/png"
                                  (.getBytes "PNGDATA"))]
        (testing "traversal is stripped to a harmless basename under web/"
          (is (= :photo (:kind img)))
          (is (str/includes? (:path img) "channel_attachments/web"))
          (is (str/ends-with? (:path img) "token.png"))
          (is (not (str/includes? (:path img) "..")))
          (is (fs/exists? (fs/path (:path img))))
          (is (= 7 (:bytes img)))))
      (testing "kind derivation: audio is a voice note, else a document"
        (is (= :voice (:kind (turn/save-upload! "note.ogg" "audio/ogg"
                                                (.getBytes "OGG")))))
        (is (= :document (:kind (turn/save-upload! "notes.md" "text/markdown"
                                                   (.getBytes "# hi"))))))
      (testing "the byte cap throws for the route to map"
        (is (thrown? Exception
                     (turn/save-upload! "big.bin" "application/octet-stream"
                                        (byte-array (inc (* 20 1024 1024)))))))
      (testing "the upload route answers 200 with a descriptor (authed)"
        (let [token (:token (body-json (do-login deck-pass)))
              res (web/app (req "/api/attachment"
                                {:request-method :put
                                 :query-string "name=shot.png&mime=image%2Fpng"
                                 :headers {"authorization" (str "Bearer " token)}
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "PNG2"))}))]
          (is (= 200 (:status res)))
          (is (= "photo" (:kind (body-json res)))))))))

(deftest cockpit-chat-commands-answer-through-the-lane
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (let [token (:token (body-json (do-login deck-pass)))
            res (post-turn token {:session "web-cmd" :text "/usage"})]
        (is (= 200 (:status res)))
        (let [job (wait-for-job (:job/id (body-json res)) 20000)]
          (testing "/usage answers without an LLM turn, same as Telegram"
            (is (map? job))
            (is (= :done (:status job)))
            (is (str/starts-with? (str (:final job)) "📊 Usage:")))
          (testing "command turns are recorded with :source command"
            (let [get-res (web/app (req "/api/transcript"
                                        {:headers {"authorization" (str "Bearer " token)}
                                         :query-string "id=web-cmd"}))
                  env (body-json get-res)]
              (is (= 200 (:status get-res)))
              (is (= 1 (:total env)))
              (is (= "command" (:source (first (:rows env))))))))))))

(deftest login-rate-limits-after-five-failures
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (with-redefs [config/home (fn [] (str home))]
      (dotimes [_ 5]
        (is (= 401 (:status (do-login "wrong-pass")))))
      (testing "the sixth attempt is locked out — even with the right password"
        (let [res (do-login deck-pass)]
          (is (= 429 (:status res)))
          (is (= "rate-limited" (:error (body-json res))))
          (is (pos? (long (:retry-ms (body-json res)))))))
      (testing "the lockout is per-process memory: a reset clears it"
        (auth/reset-login-failures!)
        (is (= 200 (:status (do-login deck-pass))))))))


;; ---------- durable lane: recovery, cancel/retry, approval bypass ----------

(deftest jobs-persist-and-recover-honestly
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (testing "a finished job persists without its ephemeral events"
        (let [token (:token (body-json (do-login deck-pass)))
              res (post-turn token
                             {:session "web-dur" :text "say pong"})
              first-id (:job/id (body-json res))]
          (is (= :done (:status (wait-for-job first-id 20000))))
          (let [disk (lane/recover!)]
            (is (string? (get-in disk [:jobs first-id :final]))
                "the done job (with its final) survives on disk")
            (is (nil? (get-in disk [:jobs first-id :events]))
                "ephemeral flow events never persist"))))
      (testing "restart recovery: interrupted, never silently re-run"
        (spit (str (fs/path home "state" "web-lane.edn"))
              (pr-str {:jobs {"job-9" {:job/id "job-9" :session/id "web-dur"
                                        :status :running :text "say pong"
                                        :attachment-ids [] :submitted-at 1}}
                       :uploads {"up-1" {:kind :document :path "/x"
                                         :file-name "x.md" :bytes 2}}}))
        (let [report (turn/recover-lane!)]
          (is (= ["job-9"] (:interrupted report)))
          (is (= :interrupted (get-in (turn/job-view "job-9") [:status])))
          (is (str/includes? (str (get-in (turn/job-view "job-9") [:error]))
                             "restarted"))
          (is (= {"up-1" {:kind :document :path "/x" :file-name "x.md" :bytes 2}}
                 @turn/uploads)
              "upload descriptors rehydrate — a retry can find its bytes"))
        (testing "the job sequence continues past recovered ids"
          (let [token (:token (body-json (do-login deck-pass)))
                res (post-turn token
                               {:session "web-dur" :text "say pong"})
                id (:job/id (body-json res))]
            (is (string? id))
            (is (not= "job-9" id) "new ids do not collide with recovered job-9")
            (is (= :done (:status (wait-for-job id 20000))))))))))

(deftest queued-jobs-cancel-instantly-and-retry-as-new-jobs
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      ;; a queued job that never reached the executor (synthetic, like the
      ;; approval fixture): cancel must not need the lane to drain.
      (swap! turn/jobs assoc "job-q"
             {:job/id "job-q" :session/id "web-cx" :status :queued
              :text "say pong" :attachment-ids [] :events []
              :submitted-at 1})
      (let [token (:token (body-json (do-login deck-pass)))
            route (fn [id verb]
                    (web/app (req (str "/api/job/" id "/" verb)
                                  {:request-method :post
                                   :headers {"authorization" (str "Bearer " token)}
                                   :body (java.io.StringReader. "{}")})))]
        (testing "cancel answers the job's own verb"
          (is (= 200 (:status (route "job-q" "cancel"))))
          (is (= :canceled (:status (turn/job-view "job-q"))))
          (testing "a retry re-queues the same inputs as a NEW job"
            (let [res (route "job-q" "retry")
                  new-id (:job/id (body-json res))]
              (is (= 200 (:status res)))
              (is (and (string? new-id) (not= "job-q" new-id)))
              (is (= :done (:status (wait-for-job new-id 20000))))
              (is (= "pong" (:final (turn/job-view new-id)))
                  "the retried job ran the real turn")
              (testing "done jobs refuse retry honestly; unknown jobs 404"
                (is (= 409 (:status (route new-id "retry"))))
                (is (= 404 (:status (route "job-zz" "cancel")))))))
          (testing "canceled jobs are not re-run by the executor"
            (is (= :canceled (:status (turn/job-view "job-q"))))))))))

(deftest typed-approvals-bypass-the-blocked-lane
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (let [token (:token (body-json (do-login deck-pass)))
            pending (approval/create-pending! "web-byp" :web
                                              {:tool/name "shell"
                                               :tool/args {:cmd "ls"}})]
        ;; the blocked lane: a running job waiting on that approval
        (swap! turn/jobs assoc "job-block"
               {:job/id "job-block" :session/id "web-byp" :status :running
                :text "do the thing" :attachment-ids [] :events []
                :approval {:id (:approval/id pending) :tool "shell"}
                :submitted-at 1})
        (testing "'approve' resolves on the request thread, not behind the job"
          (let [res (web/app (req "/api/turn"
                                  {:request-method :post
                                   :headers {"authorization" (str "Bearer " token)}
                                   :body (java.io.StringReader.
                                          (json/generate-string
                                           {:session "web-byp" :text "approve"}))}))
                payload (body-json res)]
            (is (= 200 (:status res)))
            (is (true? (:bypassed payload)))
            (is (true? (:resolved payload)))
            (is (= "approve" (:decision payload)))
            (is (string? (:reply payload))))
          (is (= :approve (get-in (approval/load-state)
                                  [:decisions (:approval/id pending)])))
          (is (= 1 (count @turn/jobs))
              "the lane registry still holds exactly the blocked job —
               the approval itself never enqueued one"))
        (testing "an approval reply with nothing pending queues a normal turn"
          (turn/reset-lane!)
          (let [res (post-turn token {:session "web-byp" :text "deny"})]
            (is (string? (:job/id (body-json res))))))))))

;; a provider that fails once with an infra error, then succeeds —
;; exercises the REAL retry path (core → retry/with-retries → emit)
(def flaky-counter (atom 0))

(defmethod provider/complete :flaky-fake [_ _request]
  ;; CAS won = first call = the infra failure; the retry then succeeds
  (if (compare-and-set! flaky-counter 0 1)
    (throw (ex-info "503 service unavailable" {}))
    {:role :assistant :content "recovered"}))

(deftest retry-announcements-land-in-the-flow
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (reset! flaky-counter 0)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :flaky-fake})]
      (let [token (:token (body-json (do-login deck-pass)))
            res (post-turn token
                           {:session "web-flaky" :text "anything"})
            job (wait-for-job (:job/id (body-json res)) 20000)]
        (is (map? job))
        (is (= :done (:status job)))
        (is (= "recovered" (:final job)))
        (let [retries (filter #(= "retry" (:type %)) (:events job))]
          (is (= 1 (count retries)) "exactly one retry announcement")
          (is (= 1 (:attempt (first retries))))
          (is (= "infra" (:kind (first retries))))
          (is (str/includes? (str (:reason (first retries))) "503")))))))


;; ---------- artifacts: web_send_file + authenticated download ----------

(deftest web-send-file-registers-and-serves-artifacts
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (reset! artifacts/registry {})
    (with-redefs [config/home (fn [] (str home))]
      (let [report-dir (do (fs/create-dirs (fs/path home "reports"))
                           (fs/path home "reports"))
            report-path (str (fs/path report-dir "report.md"))
            _ (spit report-path "# the report\n\nzanzibar\n")]
        (testing "the tool handler registers against the running web job"
          (let [result (tool/handle-tool-request
                        {:tool/name "web_send_file"
                         :tool/args {:path report-path :caption "the report"}}
                        {:web/job-id "job-art" :approval/mode :auto-all})]
            (is (:executed? result) (str "handler result: " (pr-str result)))
            (is (string? (:artifact/id result)))
            (is (= "report.md" (:name result)))
            (is (pos? (:bytes result)))))
        (testing "job-view carries the artifacts of its job"
          (swap! turn/jobs assoc "job-art"
                 {:job/id "job-art" :session/id "web-art" :status :done
                  :text "make a report" :attachment-ids [] :events []
                  :final "here" :submitted-at 1 :finished-at 2})
          (is (= 1 (count (:artifacts (turn/job-view "job-art")))))))
      (testing "outside a web session the tool refuses, telegram-style"
        (let [result (tool/handle-tool-request
                      {:tool/name "web_send_file"
                       :tool/args {:path "/etc/hosts"}}
                      {:approval/mode :auto-all})]
          (is (false? (:executed? result)))
          (is (str/includes? (str (:error/message result))
                             "web cockpit session"))))
      (let [token (:token (body-json (do-login deck-pass)))
            entry (first (vals @artifacts/registry))
            art-id (:artifact/id entry)
            on-disk (:path entry)
            authed (fn [uri opts]
                     (web/app (req uri (assoc opts :headers
                                              {"authorization" (str "Bearer " token)}))))]
        (testing "the download route serves bytes with a safe content type"
          (let [res (authed (str "/api/artifact/" art-id) {})]
            (is (= 200 (:status res)))
            (is (str/includes? (String. ^bytes (:body res) "UTF-8") "zanzibar"))
            (is (= "text/markdown; charset=utf-8"
                   (get-in res [:headers "Content-Type"])))
            (is (= "attachment; filename=\"report.md\""
                   (get-in res [:headers "Content-Disposition"])))))
        (testing "?token= works on the artifact route only (download links)"
          (is (= 200 (:status (web/app (req (str "/api/artifact/" art-id)
                                            {:query-string (str "token=" token)})))))
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:query-string (str "token=" token)}))))))
        (testing "no auth → 401; unknown id → 404"
          (is (= 401 (:status (web/app (req (str "/api/artifact/" art-id) {})))))
          (is (= 404 (:status (authed "/api/artifact/deadbeef" {})))))
        (testing "a path outside the delivery roots is refused at registration"
          (is (thrown-with-msg? Exception #"roots"
                                (artifacts/register! "/etc/hosts" "job-art" nil))))
        (testing "a symlink swap after registration is refused at serve"
          (let [outside (fs/create-temp-dir {:prefix "web-outside-"})
                secret (str (fs/path outside "secret.txt"))
                _ (spit secret "SECRETS")
                link-dir (fs/create-temp-dir {:prefix "web-link-"})
                link (str (fs/path link-dir "innocent.md"))
                _ (fs/create-sym-link link (fs/path on-disk))
                art (artifacts/register! link "job-art" nil)]
            (fs/delete link)
            (fs/create-sym-link (fs/path link) (fs/path secret))
            (is (nil? (artifacts/lookup (:artifact/id art)))
                "serve-time revalidation refuses the swapped path")))))))

;; ---------- goals: detail reader + control verbs ----------

(deftest goal-detail-and-control-verbs
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (let [ws (fs/path home "goals" "goal-web-x")
            workdir (fs/create-temp-dir {:prefix "goal-workdir-"})
            _ (do (fs/create-dirs ws)
                  (spit (str (fs/path ws "project.edn"))
                        (pr-str {:name "goal-web-x"}))
                  (spit (str (fs/path ws "config.edn"))
                        (pr-str {:name "goal-web-x" :workdir (str workdir)}))
                  (spit (str (fs/path ws "run.log"))
                        "iter 1 started\niter 1 ok\nHALT: stall\n"))
            token (:token (body-json (do-login deck-pass)))
            control-route (fn [id action]
                            (web/app (req "/api/goal-control"
                                          {:request-method :post
                                           :headers {"authorization" (str "Bearer " token)}
                                           :body (java.io.StringReader.
                                                  (json/generate-string
                                                   {:id id :action action}))})))]
        (testing "detail: verdict, control state, bounded log tail"
          (let [res (web/app (req "/api/goal-detail"
                                  {:headers {"authorization" (str "Bearer " token)}
                                   :query-string "id=goal-web-x"}))
                detail (body-json res)]
            (is (= 200 (:status res)))
            (is (= "halted" (:verdict detail)))
            (is (= "goal-web-x" (:dir detail)))
            (is (= "running" (get-in detail [:control :state])))
            (is (= ["iter 1 started" "iter 1 ok" "HALT: stall"] (:log detail))))
          (is (= 404 (:status (web/app (req "/api/goal-detail"
                                            {:headers {"authorization" (str "Bearer " token)}
                                             :query-string "id=ghost"}))))))
        (testing "pause writes the control file into the RUN workdir, stop wins"
          (let [res (control-route "goal-web-x" "pause")]
            (is (= 200 (:status res)))
            (is (= :paused (keyword (:state (body-json res))))))
          (is (fs/exists? (fs/path workdir ".goal-control" "pause")))
          (control-route "goal-web-x" "stop")
          (is (fs/exists? (fs/path workdir ".goal-control" "stop")))
          (let [res (web/app (req "/api/goal-detail"
                                  {:headers {"authorization" (str "Bearer " token)}
                                   :query-string "id=goal-web-x"}))]
            (is (= "stop-requested" (get-in (body-json res) [:control :state])))))
        (testing "unknown goal / unknown action refuse honestly"
          (is (= 404 (:status (control-route "ghost" "pause"))))
          (is (= 400 (:status (control-route "goal-web-x" "detonate")))))
        (testing "resume/cancel route through the goal bridge"
          (with-redefs [goal-bridge/resume! (fn [slug chat thread _emit]
                                              [slug chat thread])
                        goal-bridge/cancel! (fn [chat thread] [chat thread])]
            (is (= 200 (:status (control-route "goal-web-x" "resume"))))
            (is (= 200 (:status (control-route "goal-web-x" "cancel"))))))))))

;; ---------- task 10 pins: pagination, untruncated, edit, cross-channel ----------

(deftest transcript-pagination-and-untruncated-fields
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      ;; 130 turns, one of them carrying a 9000-char field: pagination
      ;; has real pages to slice and truncation caps have something to
      ;; betray them.
      (let [long-input (apply str (repeat 9000 "z"))
            turns (mapv (fn [i]
                          {:session/id "web-page"
                           :user/input (if (= i 129) long-input (str "q" i))
                           :assistant/final (str "a" i)
                           :turn/ok true})
                        (range 130))]
        (session/save-turns! "web-page" turns)
        (session/touch-metadata! "web-page" {}))
      (let [token (:token (body-json (do-login deck-pass)))
            get-page (fn [q]
                       (body-json
                        (web/app (req "/api/transcript"
                                      {:headers {"authorization" (str "Bearer " token)}
                                       :query-string q}))))]
        (testing "page 1: the newest 60, envelope says more exists"
          (let [env (get-page "id=web-page")]
            (is (= 130 (:total env)))
            (is (= 1 (:page env)))
            (is (= 60 (count (:rows env))))
            (is (true? (:has-more env)))
            (is (= "a129" (:final (last (:rows env)))))))
        (testing "page 2: the middle 60; page 3: the oldest 10, no more"
          (let [env (get-page "id=web-page&page=2")]
            (is (= 2 (:page env)))
            (is (= 60 (count (:rows env))))
            (is (true? (:has-more env)))
            (is (= "a69" (:final (last (:rows env))))))
          (let [env (get-page "id=web-page&page=3")]
            (is (= 3 (:page env)))
            (is (= 10 (count (:rows env))))
            (is (false? (:has-more env)))
            (is (= "a0" (:final (first (:rows env)))))
            (is (= "a9" (:final (last (:rows env)))))))
        (testing "beyond the end: empty rows, honest envelope"
          (let [env (get-page "id=web-page&page=9")]
            (is (= 9 (:page env)))
            (is (zero? (count (:rows env))))
            (is (false? (:has-more env)))))
        (testing "fields arrive UNTRUNCATED (parity row 12)"
          (let [env (get-page "id=web-page")
                long-row (last (:rows env))]
            (is (= 9000 (count (:input long-row))))
            (is (str/ends-with? (:input long-row) "zzzz"))))))))

(deftest edit-last-re-runs-and-replaces
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (session/save-turns! "web-edit"
                           [{:session/id "web-edit"
                             :user/input "original"
                             :assistant/final "pong-original"
                             :turn/ok true}])
      (session/touch-metadata! "web-edit" {})
      (let [token (:token (body-json (do-login deck-pass)))
            res (post-turn token {:session "web-edit" :text "corrected" :edit true})
            job (wait-for-job (:job/id (body-json res)) 20000)
            env (body-json (web/app (req "/api/transcript"
                                         {:headers {"authorization" (str "Bearer " token)}
                                          :query-string "id=web-edit"})))]
        (testing "the edit job runs"
          (is (= :done (:status job)))
          (is (= "fake: corrected" (:final job))))
        (testing "one replacement, never a duplicate (parity row 16)"
          (is (= 1 (:total env)))
          (is (= "corrected" (:input (first (:rows env)))))
          (is (= "fake: corrected" (:final (first (:rows env)))))))
      (testing "a blank edit is refused BEFORE any turn is dropped"
        (let [token (:token (body-json (do-login deck-pass)))
              res (post-turn token {:session "web-edit" :text "   " :edit true})]
          (is (= 400 (:status res)))
          (is (str/includes? (str (:error (body-json res))) "edit requires text")))
        (let [env (body-json (web/app (req "/api/transcript"
                                           {:headers {"authorization"
                                                      (str "Bearer "
                                                           (:token (body-json (do-login deck-pass))))}
                                           :query-string "id=web-edit"})))]
          (is (= 1 (:total env))
              "the history survived the refused edit"))))))

(deftest cross-channel-append-moves-the-session-and-grows-the-transcript
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (session/save-turns! "web-xch"
                           [{:session/id "web-xch"
                             :user/input "from the web lane"
                             :assistant/final "ack"
                             :turn/ok true}])
      (session/touch-metadata! "web-xch" {})
      (let [token (:token (body-json (do-login deck-pass)))
            before (body-json (web/app (req "/api/session-list"
                                            {:headers {"authorization" (str "Bearer " token)}})))
            stamp-before (:updated (some #(when (= "web-xch" (:id %)) %) before))]
        (testing "the Telegram lane appends + touches metadata (what run-turn! does)"
          (session/append-turn! "web-xch"
                                {:session/id "web-xch"
                                 :user/input "from the telegram lane"
                                 :assistant/final "ack2"
                                 :turn/ok true})
          (session/touch-metadata! "web-xch" {}))
        (let [after (body-json (web/app (req "/api/session-list"
                                             {:headers {"authorization" (str "Bearer " token)}})))
              stamp-after (:updated (some #(when (= "web-xch" (:id %)) %) after))
              env (body-json (web/app (req "/api/transcript"
                                           {:headers {"authorization" (str "Bearer " token)}
                                            :query-string "id=web-xch"})))]
          (testing "session-list :updated moved — the reload signal (parity row 13)"
            (is (not= stamp-before stamp-after)))
          (testing "the transcript grew for the other channel's turn"
            (is (= 2 (:total env)))
            (is (= "from the telegram lane"
                   (:input (last (:rows env)))))))))))
;; ---------- task 11 pins: schedules CRUD, roles, rotation, durability ----------

(deftest schedule-crud-routes-manage-the-shared-registry
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (let [token (:token (body-json (do-login deck-pass)))
            post-route (fn [payload]
                         (web/app (req "/api/schedule"
                                       {:request-method :post
                                        :headers {"authorization" (str "Bearer " token)}
                                        :body (java.io.StringReader.
                                               (json/generate-string payload))})))
            delete-route (fn [id]
                           (web/app (req (str "/api/schedule/" id)
                                         {:request-method :delete
                                          :headers {"authorization" (str "Bearer " token)}})))
            schedules (fn []
                        (body-json (web/app (req "/api/schedules"
                                                 {:headers {"authorization" (str "Bearer " token)}}))))]
        (testing "create: same registry the daemon reads"
          (is (= 200 (:status (post-route {:id "web-sched" :prompt "say hi" :cron "*/5 * * * *"}))))
          (is (= "web-sched" (:schedule/id (body-json (post-route {:id "web-sched" :prompt "say hi" :cron "*/5 * * * *"})))))
          (let [entry (schedule/find-schedule "web-sched")]
            (is (= "say hi" (:schedule/prompt entry)))
            (is (= "*/5 * * * *" (:schedule/cron entry))))
          (is (schedule/find-schedule "web-sched"))
        (testing "validation: bad id, empty prompt, impossible cron refuse"
          (is (= 400 (:status (post-route {:id "no spaces!" :prompt "x"}))))
          (is (= 400 (:status (post-route {:id "ok-id" :prompt "   "}))))
          (is (= 400 (:status (post-route {:id "ok-id" :prompt "x" :cron "not a cron"})))))
        (testing "delete removes it from the registry"
          (is (= 200 (:status (delete-route "web-sched"))))
          (is (nil? (schedule/find-schedule "web-sched"))))
        (testing "no auth → 401"
          (is (= 401 (:status (web/app (req "/api/schedule"
                                            {:request-method :post
                                             :body (java.io.StringReader. "{}")})))))))))
)

(deftest read-only-role-reads-but-never-writes
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))
                  config/load-config (fn [] {:provider :fake})]
      (auth/set-ro-password! "deck-ro-7")
      (let [ro (:token (body-json (do-login "deck-ro-7")))
            ro-call (fn [method uri body]
                      (web/app (req uri
                                    {:request-method method
                                     :headers {"authorization" (str "Bearer " ro)}
                                     :body (when body
                                             (java.io.StringReader.
                                              (json/generate-string body)))})))
            writes [["turn" :post "/api/turn" {}]
                    ["approval" :post "/api/approval" {}]
                    ["upload" :put "/api/attachment" nil]
                    ["job-control" :post "/api/job/x/cancel" nil]
                    ["goal-control" :post "/api/goal-control" {}]
                    ["schedule" :post "/api/schedule" {}]]]
        (testing "the ro login carries the ro role"
          (is (= "ro" (:role (body-json (do-login "deck-ro-7"))))))
        (testing "reads pass for the ro token"
          (is (= 200 (:status (ro-call :get "/api/status" nil))))
          (is (= 200 (:status (ro-call :get "/api/session-list" nil)))))
        (testing "every write route refuses with 403, never 401"
          (doseq [[desc method uri body] writes]
            (is (= 403 (:status (ro-call method uri body)))
                (str desc " must 403 for ro")))))
      (auth/set-ro-password! nil))))

(deftest logout-rotation-and-durable-sessions
  (let [home (fixture-home!)]
    (auth/reset-sessions!)
    (auth/reset-login-failures!)
    (turn/reset-lane!)
    (with-redefs [config/home (fn [] (str home))]
      (testing "logout kills THIS token only"
        (let [t1 (:token (body-json (do-login deck-pass)))
              t2 (:token (body-json (do-login deck-pass)))
              res (web/app (req "/api/logout"
                                {:request-method :post
                                 :headers {"authorization" (str "Bearer " t1)}}))]
          (is (= 200 (:status res)))
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:headers {"authorization" (str "Bearer " t1)}})))))
          (is (= 200 (:status (web/app (req "/api/status"
                                            {:headers {"authorization" (str "Bearer " t2)}}))))))
      (testing "durable: a restart (load-sessions!) keeps the browser logged in"
        (let [t (:token (body-json (do-login deck-pass)))]
          (auth/load-sessions!)
          (is (= 200 (:status (web/app (req "/api/status"
                                            {:headers {"authorization" (str "Bearer " t)}}))))))
      (testing "rotation: old secret dies, new token works, wrong current refuses"
        (let [t-old (:token (body-json (do-login deck-pass)))
              res (web/app (req "/api/password"
                                {:request-method :post
                                 :headers {"authorization" (str "Bearer " t-old)}
                                 :body (java.io.StringReader.
                                        (json/generate-string {:current deck-pass
                                                               :new "rotated-77"}))}))
              body (body-json res)]
          (is (= 200 (:status res)))
          (is (string? (:token body)))
          (is (= "owner" (:role body)))
          (is (= 401 (:status (web/app (req "/api/status"
                                            {:headers {"authorization" (str "Bearer " t-old)}})))))
          (is (= 200 (:status (web/app (req "/api/status"
                                            {:headers {"authorization"
                                                       (str "Bearer " (:token body))}})))))
          (is (= 401 (:status (do-login deck-pass))))
          (is (map? (body-json (do-login "rotated-77"))))
          (let [wrong (web/app (req "/api/password"
                                    {:request-method :post
                                     :headers {"authorization"
                                               (str "Bearer " (:token (body-json (do-login "rotated-77"))))}
                                     :body (java.io.StringReader.
                                            (json/generate-string {:current "nope"
                                                                   :new "x2"}))}))]
            (is (= 400 (:status wrong))))
          ;; restore the fixture password for any later tests
          (auth/set-password! deck-pass)))))))
))

(def ^:private repo-root
  ;; bound at load time, when *file* is reliable; "." is the fallback
  ;; for runners that don't bind it (bb.edn tasks always run at root)
  (if (and *file* (fs/exists? *file*))
    (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))
    "."))

(deftest module-graph-resolves-test
  (testing "every web/js import resolves to a real export"
    ;; Incident 2026-11-10: media.js imported api.js's missing `put`.
    ;; The browser throws a SyntaxError while RESOLVING the ES module
    ;; graph — before any code runs — so the deck sat on the static
    ;; "connecting…" shell with no login overlay, while every gate
    ;; stayed green: node --check is per-file syntax, curl never
    ;; executes JS. This is the one gate that sees the seam.
    (let [{:keys [exit out]} (sh "bb" "scripts/check_web_graph.bb"
                                 (str (fs/path repo-root "web")))]
      (is (zero? exit))
      (is (str/includes? out "0 missing exports, 0 unparsed forms")
          (str "graph checker said: " out)))))

(deftest every-disk-module-is-served-test
  (testing "the static whitelist covers every asset on disk"
    ;; The other half of incident 2026-11-10: feature work added
    ;; media/paging/schedules/transcript.js but the whitelist never
    ;; learned them — the browser's module fetch 404'd (Chrome reports
    ;; it as "failed to fetch dynamically imported module") and the
    ;; deck sat on the static connecting shell. The existing whitelist
    ;; test compared the table against itself; this one compares it
    ;; against the disk, where feature work actually adds files.
    (let [on-disk (fn [dir]
                    (->> (fs/glob (fs/path repo-root "web" dir) "*.{js,css}")
                         (map #(str dir "/" (fs/file-name %)))))
          served? (fn [p]
                    (= 200 (:status (web/app {:uri (str "/" p)
                                              :request-method :get}))))
          missing (remove served? (mapcat on-disk ["js" "css" "vendor"]))]
      (is (empty? missing)
          (str "files on disk the whitelist refuses to serve: "
               (pr-str (vec missing)))))))


(deftest login-dialog-has-readable-theme-color
  (testing "native dialog text explicitly overrides the browser's black default"
    (let [res (web/app {:uri "/css/login.css" :request-method :get})
          css (:body res)
          rule (re-find #"(?s)#login\s*\{(.*?)\}" css)]
      (is (= 200 (:status res)))
      (is (some? rule) "login dialog style is present")
      (is (re-find #"(?m)(?:^|;)\s*color\s*:\s*var\(--text\)\s*;" (or (second rule) ""))
          "the dialog must use the deck foreground rather than the native black default"))))


(deftest session-picker-shrinks-within-the-toolbar
  (testing "long session labels must not push controls into the next column"
    (let [res (web/app {:uri "/css/cockpit.css" :request-method :get})
          rule (re-find #"(?s)\.cockpit-sessions\s+select\s*\{(.*?)\}" (:body res))]
      (is (= 200 (:status res)))
      (is (some? rule) "session picker style is present")
      (is (re-find #"(?m)(?:^|;)\s*min-width\s*:\s*0\s*;" (or (second rule) ""))
          "flex item must be allowed to shrink below long session labels"))))