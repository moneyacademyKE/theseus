(ns e2e.config-test
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

(deftest doctor-reports-ok-for-default-config
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doctor-ok-"})]
    (try
      (let [result (shell! home "config" "doctor")]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "[WARN] :config-file"))
        (is (str/includes? (:out result) "[OK] :provider-config"))
        (is (str/includes? (:out result) ":fake")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-reports-error-for-missing-provider-keys
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doctor-missing-"})
        config-file (fs/path home "config.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :openai-compatible
                     :model "gpt-4.1-mini"
                     :providers {:openai-compatible {:base-url "http://localhost:8080/v1"}}}))
      (let [result (shell! home "config" "doctor")]
        (is (= 1 (:exit result)))
        (is (str/includes? (:out result) "[ERROR] :provider-config"))
        (is (str/includes? (:out result) "missing config keys: api-key")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-reports-warning-for-session-model-drift
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doctor-drift-"})
        config-file (fs/path home "config.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :session/id "default"}))
      (shell! home "model" "set" "default" "fake" "switched-model")
      (let [result (shell! home "config" "doctor")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "[WARN] :session-model-drift"))
        (is (str/includes? (:out result) "switched-model")))
      (finally
        (fs/delete-tree home)))))

(deftest doctor-reports-ok-for-complete-openai-config
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-doctor-complete-"})
        config-file (fs/path home "config.edn")]
    (try
      (spit (str config-file)
            (pr-str {:provider :openai-compatible
                     :model "gpt-4.1-mini"
                     :session/id "default"
                     :providers {:openai-compatible
                                 {:base-url "http://localhost:8080/v1"
                                  :api-key "test-key"}}}))
      (let [result (shell! home "config" "doctor")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "[OK] :provider-config"))
        (is (str/includes? (:out result) "config complete")))
      (finally
        (fs/delete-tree home)))))
