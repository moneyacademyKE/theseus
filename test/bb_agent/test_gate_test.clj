(ns bb-agent.test-gate-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [bb-agent.test-gate :as gate]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest isolated-home-is-passed-to-a-real-child-process
  (let [visited (atom nil)
        {:keys [exit out]}
        (gate/with-isolated-home
         (fn [home]
           (reset! visited home)
           (is (fs/directory? home))
           (process/sh {:extra-env {"THESEUS_HOME" home}
                        :continue true}
                       "bb" "-e"
                       "(require '[bb-agent.config :as c]) (print (c/home))")))]
    (is (zero? exit))
    (is (= @visited (str/trim out)))
    (is (not (fs/exists? @visited)) "the disposable home is removed afterwards")))

(deftest isolated-home-is-cleaned-after-failure
  (let [visited (atom nil)]
    (testing "a failing command is not converted into a successful gate"
      (is (thrown-with-msg? Exception #"fixture failure"
                            (gate/with-isolated-home
                             (fn [home]
                               (reset! visited home)
                               (spit (str (fs/path home "fixture")) "data")
                               (throw (ex-info "fixture failure" {})))))))
    (is (not (fs/exists? @visited)))))

(deftest isolated-command-propagates-failure
  (let [result (gate/run-isolated!
                ["bb" "-e" "(System/exit 17)"])]
    (is (= 17 (:exit result)))))

(deftest isolated-command-uses-a-fresh-home
  (let [record-dir (fs/create-temp-dir {:prefix "test-gate-record-"})
        record (str (fs/path record-dir "home.edn"))]
    (try
      (let [code (str "(require '[bb-agent.config :as c]) "
                      "(spit " (pr-str record) " (pr-str [(c/home) (System/getenv \"HOME\")]))")
            {:keys [exit]} (gate/run-isolated! ["bb" "-e" code])
            [home shell-home] (read-string (slurp record))]
        (is (zero? exit))
        (is (= home shell-home))
        (is (not= home (System/getenv "THESEUS_HOME")))
        (is (not (fs/exists? home))))
      (finally (fs/delete-tree record-dir)))))
