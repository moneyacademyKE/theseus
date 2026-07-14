(ns e2e.phase6-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.memory :as memory]
            [bb-agent.slack :as slack]
            [bb-agent.tool :as tool]
            [bb-agent.ui :as ui]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- temp-home []
  (str (fs/create-temp-dir {:prefix "bb-agent-phase6-"})))

(defn- write-config! [home config-map]
  (fs/create-dirs (fs/path home))
  (spit (str (fs/path home "config.edn")) (pr-str config-map)))

(defn- with-temp-home [f]
  (let [home (temp-home)]
    (write-config! home {:provider :fake
                         :model "fake-deterministic"
                         :session/id "phase6-session"})
    (with-redefs [bb-agent.config/home (fn [] home)]
      (try
        (f)
        (finally
          (fs/delete-tree home))))))

(use-fixtures :each with-temp-home)

(deftest sqlite-memory-backend-switches-storage-file
  (let [home (config/home)
        config-path (fs/path home "config.edn")]
    (spit (str config-path)
          (pr-str {:provider :fake
                   :model "fake-deterministic"
                   :session/id "phase6-session"
                   :memory/backend :sqlite}))
    (let [entry (memory/add-memory! "sqlite-backed memory")
          sqlite-path (fs/path home "state" "memory.sqlite")
          default-path (fs/path home "state" "memory.edn")]
      (is (= :sqlite (memory/backend)))
      (is (= "sqlite-backed memory" (:memory/text entry)))
      (is (fs/exists? sqlite-path))
      (is (not (fs/exists? default-path)))
      (is (= "sqlite-backed memory" (:memory/text (first (memory/load-memories)))))
      (is (= "sqlite-backed memory"
             (str/trim (:out (p/shell {:out :string}
                                      "sqlite3" (str sqlite-path)
                                      "SELECT text FROM memories;"))))))))

(deftest browser-and-document-tools-execute
  (let [doc-path (fs/path (config/home) "sample.txt")]
    (spit (str doc-path) "phase six document text")
    (with-redefs [bb-agent.tool.process/browser-command
                  (fn [url]
                    ["bb" "-e" (str "(println (cheshire.core/generate-string {:url \"" url "\" :title \"cli title\" :content \"cli content\"}))")])]
      (let [browser-result (tool/handle-tool-request {:tool/name "browser_cli"
                                                      :approval/policy :auto-all
                                                      :tool/args {:url "https://example.com"}})
            document-result (tool/handle-tool-request {:tool/name "document_read"
                                                       :approval/policy :auto-all
                                                       :tool/args {:path (str doc-path)}})
            turn (core/run-turn! (config/load-config)
                                 (str "try approved document " doc-path))]
        (is (= :ok (:status browser-result)))
        (is (= "https://example.com" (:url browser-result)))
        (is (= "cli title" (get-in browser-result [:page :title])))
        (is (= :ok (:status document-result)))
        (is (= "phase six document text" (get-in document-result [:document :text])))
        (is (str/includes? (:assistant/final turn) "document_read"))))))

(deftest slack-polling-and-ui-status-work
  (let [home (config/home)
        config-path (fs/path home "config.edn")
        posted (atom [])
        get-calls (atom 0)]
    (spit (str config-path)
          (pr-str {:provider :fake
                   :model "fake-deterministic"
                   :session/id "phase6-session"
                   :slack {:token "xoxb-test"
                           :channel-id "C123"
                           :base-url "https://slack.test/api"}}))
    (with-redefs [babashka.http-client/get (fn [_ {:keys [query-params]}]
                                             (swap! get-calls inc)
                                             {:status 200
                                              :body (json/generate-string {:ok true
                                                                           :messages [{:ts "171.1"
                                                                                       :user "U1"
                                                                                       :text "say pong"}]})})
                  babashka.http-client/post (fn [_ {:keys [body]}]
                                              (swap! posted conj (json/parse-string body keyword))
                                              {:status 200
                                               :body (json/generate-string {:ok true})})]
      (let [result (slack/poll-once!)
            session-path (fs/path home "state" "sessions" "slack-C123-U1.edn")
            ui-text (ui/render-status)]
        (is (= 1 (:events result)))
        (is (= 1 @get-calls))
        (is (= [{:channel "C123" :text "pong"}] @posted))
        (is (fs/exists? session-path))
        (is (str/includes? ui-text "Babashka agent status"))
        (is (str/includes? ui-text "memory-backend="))
        (is (str/includes? ui-text "schedule-count="))))))
