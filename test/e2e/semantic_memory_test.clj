(ns e2e.semantic-memory-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.semantic-memory :as sm]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- run-agent [home prompt]
  (shell! home "agent" prompt))

(def ^:private day-ms 86400000)

;; ---------- pure ranking (BM25/IDF + decay) ----------

(deftest rank-prefers-doc-matching-more-query-terms
  (let [records [{:session/id "a" :summary "cron schedule schedule schedule"}
                 {:session/id "b" :summary "timezone dst catchup rules"}]]
    (is (= ["b"] (mapv :session/id (sm/rank-summaries "timezone rules" records 0)))))

  ;; tf weighs: three schedule hits beat one midnight hit
  (let [records [{:session/id "a" :summary "schedule schedule schedule midnight"}
                 {:session/id "b" :summary "timezone midnight"}]]
    (is (= "a" (:session/id (first (sm/rank-summaries "schedule midnight" records 0)))))))

(deftest rank-excludes-records-with-no-token-overlap
  (let [records [{:session/id "a" :summary "cron schedule"}
                 {:session/id "b" :summary "timezone dst"}]]
    (is (= ["b"] (mapv :session/id (sm/rank-summaries "timezone" records 0))))
    (is (= [] (sm/rank-summaries "kubernetes" records 0)))))

(deftest rank-applies-recency-decay
  (let [now 1800000000000
        records [{:session/id "old" :summary "codename zanzibar" :updated/at (- now (* 90 day-ms))}
                 {:session/id "new" :summary "codename zanzibar" :updated/at now}]
        ranked (sm/rank-summaries "codename zanzibar" records now)]
    (is (= "new" (:session/id (first ranked))))
    (is (= 2 (count ranked)))))

(deftest rank-treats-missing-timestamp-as-fresh
  (let [records [{:session/id "legacy" :summary "codename zanzibar"}
                 {:session/id "dated" :summary "codename zanzibar" :updated/at 0}]]
    (is (= 2 (count (sm/rank-summaries "codename" records 0))))
    (is (pos? (sm/recency-decay {:updated/at nil} 0)))))

;; ---------- transcript + summary ----------

(deftest transcript-keeps-user-and-final-only
  (let [lines (sm/transcript [{:user/input "q1" :assistant/final "a1"}
                              {:tool/requests [{:tool/name "shell"}]}])]
    (is (= ["user: q1" "assistant: a1"] lines))))

(deftest plain-summary-caps-oversized-transcripts
  (let [turns [{:user/input (apply str (repeat 5000 "q")) :assistant/final "a"}]
        summary (sm/plain-summary turns 100)]
    (is (<= (count summary) 100))))

;; ---------- indexing (real store seam, temp home) ----------

(defn- with-temp-home [test-fn]
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-sm-"})]
    (try
      (with-redefs [config/home (fn [] home)]
        (test-fn home))
      (finally
        (fs/delete-tree home)))))

(defn- write-turns! [home session-id turns]
  (let [path (fs/path home "state" "sessions" (str session-id ".edn"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str turns))))

(deftest index-session-stores-replaceable-record
  (with-temp-home
    (fn [home]
      (write-turns! home "s1" [{:user/input "the codename is zanzibar"
                                :assistant/final "fake: noted"}])
      (let [record (sm/index-session! "s1" {:now (constantly 42)})]
        (is (map? record))
        (is (= "s1" (:session/id record)))
        (is (= 42 (:updated/at record)))
        (is (str/includes? (:summary record) "zanzibar"))
        (write-turns! home "s1" [{:user/input "second turn"
                                  :assistant/final "fake: ok"}])
        (sm/index-session! "s1" {:now (constantly 43)})
        (let [store (sm/load-summaries)]
          (is (= 1 (count store)) "one record per session, replaced not appended")
          (is (str/includes? (get-in store ["s1" :summary]) "second turn")))))))

(deftest index-empty-session-indexes-nothing
  (with-temp-home
    (fn [_home]
      (is (nil? (sm/index-session! "ghost" {:now (constantly 0)}))
          "no turns file -> no record")
      (is (= {} (sm/load-summaries))))))

(deftest semantic-search-finds-stored-session
  (with-temp-home
    (fn [home]
      (write-turns! home "s1" [{:user/input "vault codename zanzibar"
                                :assistant/final "fake: noted"}])
      (sm/index-session! "s1" {:now (constantly 0)})
      (let [hits (sm/semantic-search "zanzibar" {:top-k 3})]
        (is (= 1 (count hits)))
        (is (= "s1" (:session/id (first hits))))
        (is (pos? (:score (first hits)))))
      (is (str/includes? (sm/semantic-context "zanzibar") "s1")))))

;; ---------- e2e: gate, auto-index, cross-session recall ----------

(deftest agent-auto-indexes-and-recalls-across-sessions-when-enabled
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-sm-e2e-"})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:semantic-memory {:enabled true}}))
      (let [a (run-agent home "the vault codename is zanzibar")]
        (is (= 0 (:exit a)) (:err a))
        (let [summaries (fs/path home "state" "session-summaries.edn")]
          (is (fs/regular-file? summaries) "auto-index ran after the turn")
          (is (str/includes? (slurp (str summaries)) "zanzibar"))
          (let [b (run-agent home "what was the vault codename again")]
            (is (= 0 (:exit b)) (:err b))
            (let [turns (edn/read-string
                         (slurp (str (fs/path home "state" "sessions" "default.edn"))))
                  ctx (:semantic/context (second turns))]
              (is (some? ctx) "second turn received historical context")
              (is (str/includes? (or ctx "") "zanzibar")
                  "the context names session one's content")))))
      (finally
        (fs/delete-tree home)))))

(deftest semantic-memory-stays-off-without-config
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-sm-off-"})]
    (try
      (let [a (run-agent home "the vault codename is zanzibar")]
        (is (= 0 (:exit a)) (:err a))
        (is (not (fs/exists? (fs/path home "state" "session-summaries.edn")))
            "no auto-index without :semantic-memory {:enabled true}")
        (let [turns (edn/read-string
                     (slurp (str (fs/path home "state" "sessions" "default.edn"))))]
          (is (nil? (:semantic/context (first turns))))))
      (finally
        (fs/delete-tree home)))))

(deftest cli-commands-index-and-search
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-sm-cli-"})]
    (try
      (let [a (run-agent home "the vault codename is zanzibar")
            _ (is (= 0 (:exit a)) (:err a))
            idx (shell! home "memory" "index-session" "default")
            _ (is (= 0 (:exit idx)) (:err idx))
            search (shell! home "memory" "semantic-search" "vault")]
        (is (= 0 (:exit search)) (:err search))
        (is (str/includes? (:out search) "zanzibar")))
      (finally
        (fs/delete-tree home)))))
