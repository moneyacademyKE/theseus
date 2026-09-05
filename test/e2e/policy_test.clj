(ns e2e.policy-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]))

(def ^:private rules-allow-shell
  "{:rules [{:name \"allow-shell\"
             :pred (fn [tool _] (= tool \"shell\"))
             :decision :allow}]}")
(def ^:private rules-allow-writes
  "{:rules [{:name \"allow-writes\"
             :pred (fn [tool _] (= tool \"write_file\"))
             :decision :allow}]}")

(def ^:private rules-deny-shell
  "{:rules [{:name \"deny-shell\"
             :pred (fn [tool _] (= tool \"shell\"))
             :decision :deny}]}")

(def ^:private rules-first-deny-then-allow-all
  "{:rules [{:name \"deny-reads\"
             :pred (fn [tool _] (= tool \"read_file\"))
             :decision :deny}
            {:name \"allow-everything\"
             :pred (fn [_ _] true)
             :decision :allow}]}")

(def ^:private rules-runaway
  "{:rules [{:name \"loops-forever\"
             :pred (fn [_ _] (loop [] (recur)))
             :decision :allow}]}")

(def ^:private rules-broken-sandbox
  "{:rules [{:name \"touches-world\"
             :pred (fn [_ _] (spit \"policy-evil-probe.txt\" \"x\"))
             :decision :allow}]}")

(defn- write-rules! [home content]
  (fs/create-dirs (fs/path home "brain"))
  (spit (str (fs/path home "brain" "rules.clj")) content))

(defn- run-tool [home request cfg]
  (with-redefs [config/home (fn [] (str home))]
    (tool/handle-tool-request request cfg)))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"OPENCRABS_HOME" (str home)}}
           "bb" "agent" prompt))

(defn- write-config! [home m]
  (spit (str (fs/path home "config.edn")) (pr-str m)))

(deftest allow-verdict-lifts-default-ask
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-allow-"})
        target (fs/path home "docs" "plan.md")]
    (try
      (write-rules! home rules-allow-writes)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str target)
                                          :content "predicated"
                                          :create-dirs? true}}
                             {:policy {:enabled true}})]
        (is (= :ok (:status result)) (pr-str result))
        (is (fs/exists? target))
        (is (= "predicated" (slurp (str target)))))
      (finally
        (fs/delete-tree home)))))

(deftest deny-verdict-overrides-auto-all
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-deny-"})]
    (try
      (write-rules! home rules-deny-shell)
      (let [result (run-tool home
                             {:tool/name "shell"
                              :approval/policy :auto-all
                              :tool/args {:cmd "touch should-not-run"}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result))
        (is (not (fs/exists? (fs/path home "should-not-run")))))
      (finally
        (fs/delete-tree home)))))

(deftest first-match-wins
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-order-"})
        note (fs/path home "note.txt")]
    (try
      (spit (str note) "readable")
      (write-rules! home rules-first-deny-then-allow-all)
      (let [result (run-tool home
                             {:tool/name "read_file"
                              :approval/policy :auto-safe
                              :tool/args {:path (str note)}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest runaway-pred-times-out-to-baseline
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-loop-"})]
    (try
      (write-rules! home rules-runaway)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str (fs/path home "x.txt"))
                                          :content "no"}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest broken-rules-fail-to-baseline
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-broken-"})
        note (fs/path home "note.txt")]
    (try
      (spit (str note) "readable")
      (write-rules! home rules-broken-sandbox)
      (let [read-result (run-tool home
                                  {:tool/name "read_file"
                                   :approval/policy :auto-safe
                                   :tool/args {:path (str note)}}
                                  {:policy {:enabled true}})
            write-result (run-tool home
                                   {:tool/name "write_file"
                                    :tool/args {:path (str (fs/path home "y.txt"))
                                                :content "no"}}
                                   {:policy {:enabled true}})]
        (is (= :ok (:status read-result)) (pr-str read-result))
        (is (= :denied (:status write-result)) (pr-str write-result))
        (is (not (fs/exists? (fs/path home "policy-evil-probe.txt"))))
        (is (not (fs/exists? "policy-evil-probe.txt"))))
      (finally
        (fs/delete-tree home)))))

(deftest disabled-ignores-rules
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-off-"})]
    (try
      (write-rules! home rules-allow-writes)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str (fs/path home "z.txt"))
                                          :content "no"}}
                             {:policy {:enabled false}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest sandbox-contract-pin
  (is (thrown? Exception (sci/eval-string "(spit \"sandbox-probe\" \"x\")" {})))
  (is (thrown? Exception (sci/eval-string "(require '[clojure.java.io])" {})))
  (is (thrown? Exception (sci/eval-string "(System/currentTimeMillis)" {})))
  (is (thrown? Exception (sci/eval-string "(Thread/sleep 1)" {})))
  (is (thrown? Exception (sci/eval-string "(deref (future 1))" {})))
  (is (= "A" (sci/eval-string "(require '[clojure.string :as str]) (str/upper-case \"a\")" {}))))

(deftest subprocess-policy-deny-beats-auto-all
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-e2e-deny-"})]
    (try
      (write-config! home {:policy {:enabled true}})
      (write-rules! home rules-deny-shell)
      (let [result (run-agent home "try approved shell touch e2e-denied-marker")]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "🚫 shell denied")
            "user-facing denial is a clean sentence, never raw EDN")
        (is (not (fs/exists? (fs/path home "e2e-denied-marker")))))
      (finally
        (fs/delete-tree home)))))

(deftest subprocess-policy-allow-beats-ask
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-e2e-allow-"})]
    (try
      (write-config! home {:policy {:enabled true}})
      (write-rules! home rules-allow-shell)
      (let [result (run-agent home "try denied shell touch e2e-allowed-marker")]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :ok") (:out result))
        (is (fs/exists? (fs/path home "e2e-allowed-marker"))))
      (finally
        (fs/delete-tree home)))))
