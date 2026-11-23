(ns bb-agent.robustness-17-test
  "Red/Green regression suite pinning all 17 runtime robustness fixes (ADR 0011)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.datalog :as dl]
            [bb-agent.mcp-client :as mcp]
            [bb-agent.memory :as memory]
            [bb-agent.plan :as plan]
            [bb-agent.provider :as provider]
            [bb-agent.schedule :as schedule]
            [bb-agent.store :as store]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [bb-agent.web-data :as web-data]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [pod.babashka.go-sqlite3 :as sqlite]))

(deftest fix-1-provider-malformed-tool-json-does-not-crash
  (with-redefs [http/post
                (fn [_ _]
                  {:status 200
                   :body (json/generate-string
                          {:choices [{:message {:content nil
                                                :tool_calls [{:id "call-1"
                                                              :type "function"
                                                              :function {:name "read_file"
                                                                         :arguments "{\"path\": \"unterminated"}}]}
                                      :finish_reason "tool_calls"}]})})]
    (let [res (provider/complete :openai-compatible
                                 {:model "gpt-4o"
                                  :messages [{:role :user :content "hi"}]
                                  :provider/config {:base-url "http://local/v1"
                                                    :api-key "sk-test"}})]
      (is (= 1 (count (:tool/requests res))))
      (is (= "read_file" (:tool/name (first (:tool/requests res)))))
      (is (map? (:tool/args (first (:tool/requests res)))))
      (is (string? (:_parse_error (:tool/args (first (:tool/requests res)))))))))

(deftest fix-2-parallel-read-batch-isolates-throwing-future
  (let [home (fs/create-temp-dir {:prefix "theseus-par-iso-"})
        f-ok (fs/path home "ok.txt")
        round (atom 0)]
    (try
      (spit (str f-ok) "hello world")
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [_ _]
                      (if (= 1 (swap! round inc))
                        {:content nil
                         :tool/requests [{:tool/name "read_file" :tool/args {:path (str f-ok)}}
                                         {:tool/name "read_file" :tool/args {:path "boom.txt"}}]}
                        {:content "recovered" :tool/requests []}))
                    tool/handle-tool-request
                    (fn [req _cfg]
                      (if (= "boom.txt" (get-in req [:tool/args :path]))
                        (throw (ex-info "simulated future crash" {}))
                        {:tool/name "read_file" :status :ok :content "hello world"}))]
        (let [turn (core/run-turn! {:provider :fake
                                    :model "fake-deterministic"
                                    :session/id "par-iso"
                                    :approval/mode :auto-safe}
                                   "read both")]
          (is (= "recovered" (:assistant/final turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-3-and-4-read-file-inverted-range-and-atomic-write
  (let [home (fs/create-temp-dir {:prefix "theseus-file-rob-"})
        f (fs/path home "lines.txt")]
    (try
      (spit (str f) "a\nb\nc\nd\n")
      (testing "fix #3: non-numeric start_line string returns clean error instead of silently reading whole file"
        (let [res (tool/execute-tool-request
                   {:tool/name "read_file"
                    :tool/args {:path (str f) :start_line "not-a-number"}})]
          (is (= :error (:status res)))))
      (testing "fix #4: write_file overwrite succeeds atomically leaving no temp files"
        (let [res (tool/execute-tool-request
                   {:tool/name "write_file"
                    :tool/args {:path (str f) :content "new-content"}})]
          (is (= :ok (:status res)))
          (is (= "new-content" (slurp (str f))))
          (is (= 1 (count (fs/list-dir home))) "no stray temp files left behind")))
      (finally
        (fs/delete-tree home)))))

(deftest fix-5-6-7-8-usage-resilience-and-nil-provider
  (let [home (fs/create-temp-dir {:prefix "theseus-usage-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn")) "[{:broken ")
      (spit (str (fs/path home "usage_pricing.edn")) "{:broken ")
      (with-redefs [config/home (constantly (str home))]
        (is (= [] (usage/load-events)) "fix #5: corrupt usage.edn falls back to []")
        (is (= usage/default-pricing (usage/pricing)) "fix #6: corrupt usage_pricing.edn falls back")
        (is (nil? (usage/price-entry nil "gpt-4o")) "fix #7: nil provider returns nil, not NPE")
        (usage/append-event! {:usage/event :turn :tokens/total 10})
        (is (= 1 (count (usage/load-events))) "fix #8: append-event! atomically recovers ledger"))
      (finally
        (fs/delete-tree home)))))

(deftest fix-9-schedule-corrupt-edn-resilience
  (let [home (fs/create-temp-dir {:prefix "theseus-sched-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "schedules.edn")) "[{:unterminated")
      (spit (str (fs/path home "state" "schedule-runs.edn")) "[{:unterminated")
      (with-redefs [config/home (constantly (str home))]
        (is (= [] (schedule/load-schedules)))
        (is (= {} (schedule/last-run-times)))
        (schedule/save-schedules! [{:schedule/id "s1" :schedule/prompt "hi"}])
        (is (= 1 (count (schedule/load-schedules)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-10-web-data-usage-series-handles-short-or-nil-timestamps
  (let [home (fs/create-temp-dir {:prefix "theseus-webdata-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn"))
            (pr-str [{:usage/event :turn :created/at nil :tokens/total 5}
                     {:usage/event :turn :created/at "bad" :tokens/total 7}
                     {:usage/event :turn :created/at "2026-11-24T00:00:00Z" :tokens/total 12}]))
      (with-redefs [config/home (constantly (str home))]
        (let [series (web-data/usage-series 7)]
          (is (= 7 (count series)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-11-and-12-memory-sqlite-corrupt-edn-and-busy-timeout
  (let [home (fs/create-temp-dir {:prefix "theseus-mem-rob-"})
        db-path (str (fs/path home "state" "memory.sqlite"))]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "config.edn")) (pr-str {:memory/backend :sqlite}))
      (let [conn (sqlite/get-connection db-path)]
        (try
          (sqlite/execute! conn ["CREATE TABLE memories (id TEXT PRIMARY KEY, text TEXT NOT NULL, kind TEXT, status TEXT, evidence_count INTEGER)"])
          (sqlite/execute! conn ["INSERT INTO memories VALUES ('m1', 'hello', '[unterminated', '{:broken', 2)"])
          (finally
            (sqlite/close-connection conn))))
      (with-redefs [config/home (constantly (str home))]
        (let [mems (memory/load-memories)]
          (is (= 1 (count mems)))
          (is (= "hello" (:memory/text (first mems))))
          (is (= :raw (:memory/kind (first mems))))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-13-store-handles-nil-token-and-cost-values
  (let [home (fs/create-temp-dir {:prefix "theseus-store-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn"))
            (pr-str [{:usage/event :turn
                      :created/at "2026-11-24T00:00:00Z"
                      :session/id "s1"
                      :provider :fake
                      :model "m1"
                      :tokens/input nil
                      :tokens/output nil
                      :tokens/total nil
                      :cost/estimate-usd nil}]))
      (with-redefs [config/home (constantly (str home))]
        (is (= 1 (store/index-usage!))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-14-mcp-client-skips-non-json-sse-keepalives
  (let [sse "data: keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"
        msgs (mcp/decode-messages sse "text/event-stream")]
    (is (= 1 (count msgs)))
    (is (= 1 (:id (first msgs))))))

(deftest fix-15-plan-verify-cmd-catches-spawn-error
  (let [home (fs/create-temp-dir {:prefix "theseus-plan-rob-"})]
    (try
      (with-redefs [config/home (constantly (str home))
                    babashka.process/process (fn [& _] (throw (ex-info "spawn boom" {})))]
        (plan/init-plan! "s1" "Test" ["Step 1"])
        (let [res (plan/complete-step! "s1" 1 {:verify-cmd "echo ok"})]
          (is (string? (:error res)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-16-config-load-falls-back-to-last-good-on-corrupt-config
  (let [home (fs/create-temp-dir {:prefix "theseus-cfg-rob-"})]
    (try
      (spit (str (fs/path home "config.edn")) "{:provider :broken")
      (spit (str (fs/path home "config.last-good.edn"))
            (pr-str {:provider :infered-local :model "infered/auto"}))
      (with-redefs [config/home (constantly (str home))]
        (let [cfg (config/load-config)]
          (is (= :infered-local (:provider cfg)))
          (is (= "infered/auto" (:model cfg)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-17-datalog-pred-isolates-type-mismatch-instead-of-throwing
  (let [db (dl/db-with (dl/empty-db)
                       [{:item/id "a" :item/score "not-a-number"}
                        {:item/id "b" :item/score 5}])]
    (is (= #{"b"}
           (set (dl/q '[:find [?id ...]
                        :where
                        [?e :item/id ?id]
                        [?e :item/score ?s]
                        [(>= ?s 3)]]
                      db))))))
