(ns bb-agent.datalog-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.datalog :as dl]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(deftest datalog-eav-joins-predicates-negation-and-pull
  (let [schema {:belief/id {:db/unique :db.unique/identity}
                :belief/supersedes {:db/valueType :db.type/ref}
                :belief/tag {:db/cardinality :db.cardinality/many}}
        db (-> (dl/empty-db schema)
               (dl/db-with [{:belief/id "b1" :belief/text "use python" :belief/status :contested
                             :belief/evidence 1 :belief/tag ["legacy" "script"]}
                            {:belief/id "b2" :belief/text "use bb" :belief/status :confirmed
                             :belief/evidence 4 :belief/tag ["clojure" "fast"]
                             :belief/supersedes [:belief/id "b1"]}
                            {:belief/id "b3" :belief/text "avoid npm" :belief/status :held
                             :belief/evidence 2}]))]
    (testing "multi-hop ref join finds confirmed belief and what it superseded"
      (is (= #{["use bb" "use python"]}
             (dl/q '[:find ?t ?old
                     :where
                     [?e :belief/status :confirmed]
                     [?e :belief/text ?t]
                     [?e :belief/supersedes ?o]
                     [?o :belief/text ?old]]
                   db))))
    (testing ":in parameter binding and real Clojure predicates"
      (is (= #{"use bb" "avoid npm"}
             (set (dl/q '[:find [?t ...]
                          :in $ ?min-ev
                          :where
                          [?e :belief/text ?t]
                          [?e :belief/evidence ?ev]
                          [(>= ?ev ?min-ev)]]
                        db 2)))))
    (testing "negation (not ...) excludes contested beliefs"
      (is (= 2 (dl/q '[:find (count ?e) .
                       :where
                       [?e :belief/id _]
                       (not [?e :belief/status :contested])]
                     db))))
    (testing "aggregates sum, min, max"
      (is (= 7 (dl/q '[:find (sum ?ev) .
                       :where [?e :belief/evidence ?ev]]
                     db)))
      (is (= 1 (dl/q '[:find (min ?ev) .
                       :where [?e :belief/evidence ?ev]]
                     db)))
      (is (= 4 (dl/q '[:find (max ?ev) .
                       :where [?e :belief/evidence ?ev]]
                     db))))
    (testing "pull resolves nested ref edges and wildcard :*"
      (let [pulled (dl/pull db [:belief/text :belief/status {:belief/supersedes [:belief/id :belief/text]}]
                            [:belief/id "b2"])
            all-attrs (dl/pull db [:*] [:belief/id "b1"])]
        (is (= "use bb" (:belief/text pulled)))
        (is (= :confirmed (:belief/status pulled)))
        (is (= {:belief/id "b1" :belief/text "use python"} (:belief/supersedes pulled)))
        (is (= "use python" (:belief/text all-attrs)))
        (is (= #{"legacy" "script"} (set (:belief/tag all-attrs))))))))

(deftest datalog-cardinality-one-upsert-and-retraction
  (let [schema {:belief/id {:db/unique :db.unique/identity}
                :belief/tag {:db/cardinality :db.cardinality/many}}
        db0 (-> (dl/empty-db schema)
                (dl/db-with [{:belief/id "b1" :belief/status :held :belief/tag ["a"]}]))
        db1 (dl/db-with db0 [{:belief/id "b1" :belief/status :confirmed :belief/tag ["b"]}])
        db2 (dl/db-with db1 [[:db/retract [:belief/id "b1"] :belief/tag "a"]])]
    (testing "cardinality-one attribute replaces prior value on upsert (original db stays immutable)"
      (is (= :held (:belief/status (dl/pull db0 [:belief/status] [:belief/id "b1"]))))
      (is (= :confirmed (:belief/status (dl/pull db1 [:belief/status] [:belief/id "b1"]))))
      (is (= #{[:confirmed]}
             (dl/q '[:find ?s :where [?e :belief/id "b1"] [?e :belief/status ?s]] db1))))
    (testing "cardinality-many accumulates and lookup-ref :db/retract removes one value"
      (is (= #{"a" "b"} (set (dl/q '[:find [?t ...] :where [?e :belief/id "b1"] [?e :belief/tag ?t]] db1))))
      (is (= #{"b"} (set (dl/q '[:find [?t ...] :where [?e :belief/id "b1"] [?e :belief/tag ?t]] db2)))))))

(deftest datalog-blocks-non-allowlisted-predicates
  (let [db (dl/db-with (dl/empty-db) [{:item/name "secret"}])
        res (dl/q '[:find ?out
                    :where
                    [?e :item/name ?n]
                    [(clojure.core/slurp "/etc/hosts") ?out]]
                  db)]
    (is (empty? res) "arbitrary Clojure functions like slurp must never execute in :where")))

(deftest hydrate-theseus-home-and-mtime-cache-invalidation
  (let [home (fs/create-temp-dir {:prefix "theseus-datalog-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (fs/create-dirs (fs/path home "goals" "demo-goal"))
      (spit (str (fs/path home "state" "memory.edn"))
            (pr-str [{:memory/id "m1" :memory/text "old rule" :memory/status :contested :memory/evidence-count 1}
                     {:memory/id "m2" :memory/text "new rule" :memory/status :confirmed :memory/evidence-count 3
                      :memory/supersedes "m1"}]))
      (spit (str (fs/path home "state" "session-summaries.edn"))
            (pr-str {"s1" {:session/id "s1" :summary "audited webdeck" :updated/at 100}}))
      (spit (str (fs/path home "goals" "demo-goal" "project.edn"))
            (pr-str {:name "Demo Goal" :status :active}))
      (with-redefs [config/home (constantly (str home))]
        (let [db1 (dl/theseus-db)
              db2 (dl/theseus-db)]
          (is (identical? db1 db2) "unchanged EDN files return cached immutable DB by identity")
          (is (= #{["new rule" "old rule"]}
                 (dl/q '[:find ?new ?old
                         :where
                         [?e :memory/status :confirmed]
                         [?e :memory/text ?new]
                         [?e :memory/supersedes ?o]
                         [?o :memory/text ?old]]
                       db1)))
          (is (= #{"audited webdeck"}
                 (set (dl/q '[:find [?s ...]
                              :where [?e :session/summary ?s]]
                            db1))))
          (is (= :active
                 (dl/q '[:find ?st .
                         :where [?e :goal/slug "demo-goal"] [?e :goal/status ?st]]
                       db1)))
          (let [proj (fs/path home "goals" "demo-goal" "project.edn")
                old-ms (.toMillis (fs/last-modified-time proj))]
            (spit (str proj) (pr-str {:name "Demo Goal" :status :fulfilled}))
            (fs/set-last-modified-time proj (+ old-ms 2000))
            (let [db3 (dl/theseus-db)]
              (is (not (identical? db1 db3)) "edited project.edn invalidates cache")
              (is (= :fulfilled
                     (dl/q '[:find ?st .
                             :where [?e :goal/slug "demo-goal"] [?e :goal/status ?st]]
                           db3)))))))
      (finally
        (fs/delete-tree home)))))

(deftest query-graph-tool-wiring-and-execution
  (let [home (fs/create-temp-dir {:prefix "theseus-qgraph-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (fs/create-dirs (fs/path home "goals" "g1"))
      (spit (str (fs/path home "state" "memory.edn"))
            (pr-str [{:memory/id "m1" :memory/text "old rule" :memory/status :contested :memory/evidence-count 1}
                     {:memory/id "m2" :memory/text "new rule" :memory/status :confirmed :memory/evidence-count 3
                      :memory/supersedes "m1"}]))
      (spit (str (fs/path home "goals" "g1" "project.edn"))
            (pr-str {:name "Goal One" :status :fulfilled}))
      (testing "query_graph is advertised and auto-approved"
        (is (some #(= "query_graph" (get-in % [:function :name])) tools/definitions))
        (is (some #(= "query_graph" (get-in % [:function :name])) (tools/active-definitions nil)))
        (is (contains? common/safe-auto-tools "query_graph")))
      (with-redefs [config/home (constantly (str home))]
        (let [cfg {:approval/mode :auto-safe}]
          (testing "Datalog query through handle-tool-request"
            (let [res (tool/handle-tool-request
                       {:tool/name "query_graph"
                        :tool/args {:query "[:find ?new ?old :where [?e :memory/status :confirmed] [?e :memory/text ?new] [?e :memory/supersedes ?o] [?o :memory/text ?old]]"}}
                       cfg)]
              (is (= :ok (:status res)))
              (is (= #{["new rule" "old rule"]} (:result res)))))
          (testing "pull mode resolves nested refs"
            (let [res (tool/handle-tool-request
                       {:tool/name "query_graph"
                        :tool/args {:pull "[:memory/text {:memory/supersedes [:memory/id :memory/text]}]"
                                    :ref "[:memory/id \"m2\"]"}}
                       cfg)]
              (is (= :ok (:status res)))
              (is (= {:memory/text "new rule"
                      :memory/supersedes {:memory/id "m1" :memory/text "old rule"}}
                     (:result res)))))
          (testing "schema introspection mode"
            (let [res (tool/handle-tool-request
                       {:tool/name "query_graph"
                        :tool/args {:query "schema"}}
                       cfg)]
              (is (= :ok (:status res)))
              (is (contains? (:schema res) :memory/id))))
          (testing "limit bounds result rows"
            (let [res (tool/handle-tool-request
                       {:tool/name "query_graph"
                        :tool/args {:query "[:find [?id ...] :where [?e :memory/id ?id]]"
                                    :limit 1}}
                       cfg)]
              (is (= :ok (:status res)))
              (is (= 1 (count (:result res))))))
          (testing "malformed EDN returns clean error result"
            (let [res (tool/handle-tool-request
                       {:tool/name "query_graph"
                        :tool/args {:query "[:find ?x"}}
                       cfg)]
              (is (= :error (:status res)))))))
      (finally
        (fs/delete-tree home)))))
