(ns e2e.goal-deliverables-test
  "V2 (bk-7496): a FULFILLED goal ships its DECLARED artifacts to the
   owning topic via sendDocument. The watcher script runs against a fake
   Bot API server; the multipart upload must arrive with the declared file
   and skip declared-but-missing ones honestly."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def dead-pid 4000000000)

(deftest fulfilled-goal-ships-declared-deliverables
  (testing "fulfilled run.log + declared deliverables → sendDocument hits
            the API for real files; missing declarations are skipped"
    (let [home (str (fs/create-temp-dir {:prefix "theseus-v2-"}))
          port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          calls (atom [])
          stop (server/run-server
                (fn [req]
                  (let [body (when-let [stream (:body req)] (slurp stream))]
                    (swap! calls conj {:uri (:uri req) :body body})
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (json/generate-string
                            {:ok true
                             :result (if (str/includes? (:uri req) "sendDocument")
                                       {:document {:file_name "report.html"}}
                                       {:message_id 90})})}))
                {:port port})
          ws (str home "/goals/ship-it")
          _ (fs/create-dirs (str ws "/logs"))
          _ (spit (str ws "/run.log") "GOAL FULFILLED\nworld {:builds 1}\n")
          _ (spit (str ws "/config.edn")
                  (pr-str {:name "ship-it" :observers {} :goal {:op ">=" :ref "builds" :value 1}
                           :act {:sh "./act.sh"} :deliverables ["report.html" "never-built.txt"]}))
          _ (spit (str ws "/report.html") "<h1>done</h1>")
          _ (spit (str home "/config.edn")
                  (pr-str {:provider :fake
                           :telegram {:token "TESTTOKEN"
                                      :base-url (str "http://127.0.0.1:" port)}}))
          _ (spit (str ws "/watch-args.edn")
                  (pr-str {:name "ship-it" :home home :chat-id -100999
                           :thread-id 4721 :pid dead-pid}))
          _ (p/shell {:dir (str (fs/cwd)) :out :string :err :string}
                     "bb" "scripts/goal_watch.bb" (str ws "/watch-args.edn"))]
      (try
        (let [docs (filter #(str/includes? (:uri %) "sendDocument") @calls)]
          (is (fs/exists? (str home "/goals/ship-it/outcome.edn"))
              "the verdict queued into the DISPATCHED home, not the ambient one:
               config/home must ride the args file (goal_launch.bb and
               goal_resume.bb already pin it; goal_watch.bb did not, so an e2e
               run against a fake Bot API server wrote ship-it into the real
               goals/ and appended a turn to a real session file)")
          (is (= 1 (count docs))
              (str "exactly one document ships — got calls: "
                   (pr-str (mapv :uri @calls))))
          (when (seq docs)
            (is (str/includes? (:body (first docs)) "report.html")
                "the multipart body carries the declared file")
            (is (str/includes? (:body (first docs)) "message_thread_id")
                "topic routing rides the upload")))
        (finally
          (stop)
          (fs/delete-tree home))))))
