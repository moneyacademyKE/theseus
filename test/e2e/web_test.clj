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
            [bb-agent.approval :as approval]
            [bb-agent.config :as config]
            [bb-agent.provider :as provider]
            [bb-agent.web :as web]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-data :as data]
            [bb-agent.web-lane :as lane]
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
                  rows (body-json get-res)]
              (is (= 200 (:status get-res)))
              (is (= 1 (count rows)))
              (is (= "say pong" (:input (first rows))))
              (is (= "pong" (:final (first rows))))))
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
                  rows (body-json get-res)]
              (is (= 200 (:status get-res)))
              (is (= 1 (count rows)))
              (is (= "command" (:source (first rows)))))))))))

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
