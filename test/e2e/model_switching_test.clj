(ns e2e.model-switching-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(deftest session-scoped-model-switching
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-model-e2e-"})
        model-file (fs/path home "state" "session-models" "default.edn")
        default-session (fs/path home "state" "sessions" "default.edn")
        other-session (fs/path home "state" "sessions" "other.edn")]
    (try
      (let [set-result (shell! home "model" "set" "default" "fake" "switched-model")
            current-result (shell! home "model" "current" "default")
            agent-result (shell! home "agent" "say pong")]
        (is (= 0 (:exit set-result)) (:err set-result))
        (is (= 0 (:exit current-result)) (:err current-result))
        (is (= 0 (:exit agent-result)) (:err agent-result))
        (is (fs/regular-file? model-file))
        (is (= "switched-model" (:model (edn/read-string (:out current-result)))))
        (is (= "switched-model"
               (:model (first (edn/read-string (slurp (str default-session))))))))
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :session/id "other"}))
      (let [other-result (shell! home "agent" "say pong")]
        (is (= 0 (:exit other-result)) (:err other-result))
        (is (= "fake-deterministic"
               (:model (first (edn/read-string (slurp (str other-session)))))))
        (is (not (str/includes? (slurp (str other-session)) "switched-model"))))
      (finally
        (fs/delete-tree home)))))
