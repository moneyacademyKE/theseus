(ns bb-agent.web-deck-test
  "The deck's HTTP surface end to end, in-process: the SSE push channel
  (unauthorized close, first snapshot, change-triggered push) plus
  regression on the JSON routes the push composes over.

  The home dir is redef'd to a temp dir for the whole suite; the SSE
  broadcaster thread is created via bound-fn inside that scope, so the
  push reads the sandbox too. The JSON routes are exercised through
  web/app directly — it is one pure function request->response — while
  the stream goes over a real socket, so the frames are the wire format
  EventSource actually consumes."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [bb-agent.config :as config]
            [bb-agent.web :as web]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-turn :as turn])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net Socket)))

;; ---------- a minimal SSE client over a raw socket ----------

(defn- open-stream!
  "GET /api/events on a raw socket. Consumes the HTTP head and returns
  a BufferedReader positioned at the first SSE frame. 8s so-timeout so
  a missing push fails the test instead of hanging it."
  [port query]
  (let [sock (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 8000)
    (let [os (.getOutputStream sock)]
      (.write os (.getBytes (str "GET /api/events" query " HTTP/1.1\r\n"
                                 "Host: localhost\r\n"
                                 "Accept: text/event-stream\r\n\r\n")
                            "UTF-8"))
      (.flush os))
    (let [rdr (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))]
      (loop []
        (when-not (str/blank? (try (.readLine rdr) (catch Exception _ "")))
          (recur)))
      rdr)))

(defn- read-event!
  "The next data-carrying SSE frame; ping heartbeats are skipped.
  {:event name :data string}, or nil at EOF/timeout."
  [rdr]
  (loop [event nil data nil]
    (let [line (try (.readLine rdr) (catch Exception _ ::eof))]
      (cond
        (or (nil? line) (= ::eof line)) nil
        (str/starts-with? line "event:") (recur (str/trim (subs line 6)) data)
        (str/starts-with? line "data:")  (recur event (str/trim (subs line 5)))
        (str/blank? line) (if (and event data)
                            (if (= event "ping")
                              (recur nil nil)
                              {:event event :data data})
                            (recur nil nil))
        :else (recur event data)))))

;; ---------- fixture: sandboxed home, live token, booted deck ----------

(def ^:private token (atom nil))
(def ^:private deck-port (atom nil))
(def ^:private deck-stop (atom nil))

(use-fixtures :once
  (fn [f]
    (let [dir (fs/create-temp-dir {:prefix "theseus-web-test"})]
      (fs/create-dirs (fs/path (str dir) "state" "rsi"))
      (spit (str (fs/path (str dir) "state" "rsi" "digest.md")) "initial digest\n")
      ;; a running synthetic job so the jobs map is non-empty in pushes
      (swap! turn/jobs assoc "job-test-1"
             {:job/id "job-test-1" :session/id "s" :status :running
              :events [] :submitted-at (System/currentTimeMillis)})
      (with-redefs [config/home (constantly (str dir))]
        (auth/reset-sessions!)
        (auth/reset-login-failures!)
        (auth/set-password! "deck-pass")
        (reset! token (:token (auth/login! "deck-pass")))
        (reset! deck-port (+ 21000 (rand-int 30000)))
        (reset! deck-stop (web/start-deck! @deck-port))
        (try
          (f)
          (finally
            (@deck-stop)
            (swap! turn/jobs dissoc "job-test-1")
            (fs/delete-tree dir)))))))

;; ---------- the stream ----------

(deftest unauthorized-stream-gets-event-and-close
  (testing "a dead token is told why, then the pipe closes"
    (let [rdr (open-stream! @deck-port "?token=not-a-token")]
      (let [ev (read-event! rdr)]
        (is (= "unauthorized" (:event ev)))
        (is (map? (json/parse-string (:data ev) false))))
      (is (nil? (read-event! rdr)) "server should close after the verdict"))))

(deftest stream-delivers-the-whole-deck-on-change
  (let [rdr (open-stream! @deck-port (str "?token=" @token))
        ev1 (read-event! rdr)
        st1 (json/parse-string (:data ev1) false)]
    (testing "first frame: the full snapshot, jobs included"
      (is (= "state" (:event ev1)))
      (is (map? st1))
      (doseq [k ["status" "turns" "tools" "rsi" "sessions" "goals"
                 "schedules" "session-list" "jobs"]]
        (is (contains? st1 k) (str "missing " k)))
      (is (= "running" (get-in st1 ["jobs" "job-test-1" "status"])))
      (is (= "initial digest\n" (get-in st1 ["rsi" "md"]))))
    (testing "a state-file change pushes a new frame within seconds"
      (spit (str (fs/path (str (config/home)) "state" "rsi" "digest.md"))
            "changed digest\n")
      (let [ev2 (read-event! rdr)]
        (is (= "state" (:event ev2)))
        (is (= "changed digest\n"
               (get-in (json/parse-string (:data ev2) false) ["rsi" "md"])))))))

;; ---------- the routes the push composes over (regression) ----------

(deftest json-routes-keep-answering
  (testing "/api/status with a live Bearer token"
    (let [res (web/app {:request-method :get :uri "/api/status"
                        :headers {"authorization" (str "Bearer " @token)}})]
      (is (= 200 (:status res)))
      (is (contains? (json/parse-string (:body res) false) "now"))))
  (testing "without a token the JSON routes stay gated"
    (let [res (web/app {:request-method :get :uri "/api/status" :headers {}})]
      (is (= 401 (:status res)))
      (is (= "unauthorized" (get (json/parse-string (:body res) false) "error"))))))

(deftest state-route-serves-the-full-snapshot
  (testing "/api/state — the poll-fallback surface, token-gated"
    (let [res (web/app {:request-method :get :uri "/api/state"
                        :headers {"authorization" (str "Bearer " @token)}})
          st  (json/parse-string (:body res) false)]
      (is (= 200 (:status res)))
      (doseq [k ["status" "turns" "tools" "rsi" "sessions" "goals"
                 "schedules" "session-list" "jobs"]]
        (is (contains? st k) (str "missing " k)))))
  (testing "same gate as the rest of the API"
    (let [res (web/app {:request-method :get :uri "/api/state" :headers {}})]
      (is (= 401 (:status res))))))

(deftest static-shell-serves
  (let [res (web/app {:request-method :get :uri "/" :headers {}})]
    (is (= 200 (:status res)))
    (is (str/includes? (:body res) "THESEUS"))))

(deftest vendor-assets-serve
  (testing "vendored markdown libs serve through the whitelist, banners intact"
    (doseq [[uri banner] [["/vendor/marked.min.js" "marked v15"]
                          ["/vendor/purify.min.js" "DOMPurify"]]]
      (let [res (web/app {:request-method :get :uri uri :headers {}})]
        (is (= 200 (:status res)) uri)
        (is (str/includes? (:body res) banner) uri))))
  (testing "a path outside the whitelist still 404s"
    (let [res (web/app {:request-method :get :uri "/vendor/evil.js" :headers {}})]
      (is (= 404 (:status res)))))
  (testing "cache policy: vendors immutable for a year, our modules revalidate"
    (let [vendor (web/app {:request-method :get :uri "/vendor/marked.min.js" :headers {}})
          own    (web/app {:request-method :get :uri "/js/main.js" :headers {}})]
      (is (= "public, max-age=31536000, immutable"
             (get-in vendor [:headers "Cache-Control"])))
      (is (= "no-cache" (get-in own [:headers "Cache-Control"]))))))
