(ns e2e.web-test
  "Web deck: read-only JSON over live state. Pure handler tests — the
  whole HTTP surface is one function, request map -> response map, so no
  port and no server are involved. Auth is the home-dotfile token; static
  serving is a whitelist lookup, which is what makes traversal a
  non-grammar. Pinned: 401 without token, 200 via bearer and via
  ?token=, data shapes, static whitelist, 404 elsewhere."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.config :as config]
            [bb-agent.web :as web]
            [bb-agent.web-data :as data]))

(defn- fixture-home! []
  (let [home (fs/create-temp-dir {:prefix "theseus-web-e2e-"})]
    (fs/create-dirs (fs/path home "state" "rsi"))
    (fs/create-dirs (fs/path home "goals" "goal-a"))
    (spit (str (fs/path home ".gui-token")) "test-token-123")
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
    home))

(defn- req
  ([uri] (req uri nil))
  ([uri opts]
   (merge {:request-method :get :uri uri :headers {} :query-string nil} opts)))

(defn- body-json [res] (json/parse-string (:body res) true))

(defn- authed [uri]
  (req uri {:headers {"authorization" "Bearer test-token-123"}}))

(deftest api-requires-the-token
  (let [home (fixture-home!)]
    (with-redefs [config/home (fn [] (str home))]
      (is (= 401 (:status (web/app (req "/api/turns")))))
      (is (= 401 (:status (web/app (req "/api/status")))))
      (is (= 401 (:status (web/app (req "/api/tools")))))
      (is (= "unauthorized"
             (:error (body-json (web/app (req "/api/status")))))))))

(deftest api-serves-live-state-with-bearer-token
  (let [home (fixture-home!)]
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

(deftest token-also-accepted-via-query-param
  (let [home (fixture-home!)]
    (with-redefs [config/home (fn [] (str home))]
      (is (= 200 (:status (web/app (req "/api/status"
                                        {:query-string "token=test-token-123"})))))
      (is (= 401 (:status (web/app (req "/api/status"
                                        {:query-string "token=wrong"}))))))))

(deftest static-serves-from-whitelist-only
  (let [home (fixture-home!)]
    (with-redefs [config/home (fn [] (str home))]
      (testing "the shell and assets serve ungated (they carry no data)"
        (let [html (web/app (req "/"))]
          (is (= 200 (:status html)))
          (is (str/includes? (:body html) "THESEUS"))
          (is (str/includes? (get-in html [:headers "Content-Type"]) "text/html")))
        (is (= 200 (:status (web/app (req "/css/deck.css")))))
        (is (= 200 (:status (web/app (req "/js/main.js"))))))
      (testing "anything outside the whitelist is 404 — no grammar for traversal"
        (is (= 404 (:status (web/app (req "/etc/passwd")))))
        (is (= 404 (:status (web/app (req "/../../etc/passwd")))))
        (is (= 404 (:status (web/app (req "/api/nope")))))
        (is (= 404 (:status (web/app (req "/logs/usage.edn")))))))))
