(ns e2e.doctor-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(def ^:private valid-config
  {:provider :openai-compatible
   :model "gpt-4.1-mini"
   :session/id "default"
   :providers {:openai-compatible
               {:base-url "http://localhost:8080/v1"
                :api-key "test-key"}}})

(deftest doctor-reports-error-for-corrupt-config
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-corrupt-"})]
    (try
      (spit (str (fs/path home "config.edn")) "{:provider :fake :model")
      (let [result (shell! home "config" "doctor")]
        (is (= 1 (:exit result)) (:out result))
        (is (str/includes? (:out result) "[ERROR] :config-parse"))
        (is (str/includes? (:out result) "config.edn is not valid EDN")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-reports-error-for-corrupt-memory-store
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-mem-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "memory.edn")) "[{:memory/text")
      (let [result (shell! home "config" "doctor")]
        (is (= 1 (:exit result)) (:out result))
        (is (str/includes? (:out result) "[ERROR] :memory-store"))
        (is (str/includes? (:out result) "memory.edn")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-ok-with-parseable-stores
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-ok-"})]
    (try
      (spit (str (fs/path home "config.edn")) (pr-str valid-config))
      (let [add (shell! home "memory" "add" "anchors help recall")]
        (is (= 0 (:exit add)) (:err add)))
      (let [result (shell! home "config" "doctor")]
        (is (= 0 (:exit result)) (:out result))
        (is (str/includes? (:out result) "[OK] :config-parse"))
        (is (str/includes? (:out result) "[OK] :memory-store")))
      (finally
        (fs/delete-tree home)))))

(deftest apply-rejects-invalid-candidate-untouched
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-apply-bad-"})
        candidate (fs/create-temp-dir {:prefix "opencrabs-bb-doc-cand-"})]
    (try
      (spit (str (fs/path home "config.edn")) (pr-str valid-config))
      (spit (str (fs/path candidate "c.edn"))
            (pr-str {:provider :openai-compatible
                     :model "gpt-5-mini"
                     :providers {:openai-compatible
                                 {:base-url "http://localhost:8080/v1"}}}))
      (let [result (shell! home "config" "apply" (str (fs/path candidate "c.edn")))]
        (is (= 1 (:exit result)) (:out result))
        (is (str/includes? (:out result) "missing config keys: api-key"))
        (is (= (pr-str valid-config)
               (str/trim (slurp (str (fs/path home "config.edn"))))))
        (is (not (fs/regular-file? (fs/path home "config.last-good.edn")))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree candidate)))))

(deftest apply-swaps-atomically-keeping-last-good
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-apply-ok-"})
        candidate (fs/create-temp-dir {:prefix "opencrabs-bb-doc-cand2-"})]
    (try
      (spit (str (fs/path home "config.edn")) (pr-str valid-config))
      (spit (str (fs/path candidate "c.edn"))
            (pr-str (assoc-in valid-config [:model] "gpt-5-mini")))
      (let [result (shell! home "config" "apply" (str (fs/path candidate "c.edn")))]
        (is (= 0 (:exit result)) (:out result))
        (is (str/includes? (:out result) "applied"))
        (is (str/includes? (:out result) "gpt-5-mini"))
        (is (str/includes? (slurp (str (fs/path home "config.edn"))) "gpt-5-mini"))
        (is (str/includes? (slurp (str (fs/path home "config.last-good.edn")))
                           "gpt-4.1-mini")))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree candidate)))))

(deftest restore-last-good-recovers-from-corrupt-config
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-restore-"})
        candidate (fs/create-temp-dir {:prefix "opencrabs-bb-doc-cand3-"})]
    (try
      (spit (str (fs/path home "config.edn")) (pr-str valid-config))
      (spit (str (fs/path candidate "c.edn"))
            (pr-str (assoc-in valid-config [:model] "gpt-5-mini")))
      (is (= 0 (:exit (shell! home "config" "apply" (str (fs/path candidate "c.edn"))))))
      (spit (str (fs/path home "config.edn")) "{:provider :fake :model")
      (let [restore (shell! home "config" "restore-last-good")]
        (is (= 0 (:exit restore)) (:out restore))
        (is (str/includes? (:out restore) "restored")))
      (let [doctor (shell! home "config" "doctor")]
        (is (= 0 (:exit doctor)) (:out doctor))
        (is (str/includes? (slurp (str (fs/path home "config.edn"))) "gpt-4.1-mini")))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree candidate)))))

(deftest doctor-top-level-runs-full-matrix-green
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-matrix-"})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :session/id "default"}))
      (let [result (shell! home "doctor")]
        (is (= 0 (:exit result)) (:out result))
        (doseq [check-id [:config-file :config-parse :home-dir :home-writable
                          :provider-config :session-model-drift :memory-store
                          :semantic-store :usage-store :provider-reachable]]
          (is (str/includes? (:out result) (str "[OK] " check-id))))
        (is (str/includes? (:out result) "Fake provider responds status=ok")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-warns-when-http-provider-unreachable
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doc-unreach-"})]
    (try
      (spit (str (fs/path home "config.edn")) (pr-str valid-config))
      (let [result (shell! home "doctor")]
        (is (= 0 (:exit result)) (:out result))
        (is (str/includes? (:out result) "[WARN] :provider-reachable")))
      (finally
        (fs/delete-tree home)))))
