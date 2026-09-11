(ns bb-agent.session-test
  (:require [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import (java.nio.file Files)))

(def ^:private ^:dynamic *tmp* nil)

(defn- tmp-home-fixture [f]
  (let [dir (str (Files/createTempDirectory "theseus-session-test"
                                            (into-array java.nio.file.attribute.FileAttribute [])))]
    (with-redefs [config/home (constantly dir)]
      (binding [*tmp* dir]
        (f)))))

(use-fixtures :each tmp-home-fixture)

(deftest append-turn-caps-fat-tool-results
  (let [fat (apply str (repeat 20000 "x"))
        turn {:session/id "s1"
              :assistant/final "done"
              :tool/results [{:name "shell" :status :ok :stdout fat :stderr "tiny"}]}]
    (session/append-turn! "s1" turn)
    (let [persisted (first (session/load-turns "s1"))
          stdout (-> persisted :tool/results first :stdout)]
      (is (<= (count stdout) 8400) "the fat field is capped at persistence")
      (is (clojure.string/includes? stdout "truncated") "the marker keeps the cut visible")
      (is (clojure.string/includes? stdout "11808") "the marker reports the dropped count")
      (is (= "tiny" (-> persisted :tool/results first :stderr)) "small fields pass through")
      (is (= "done" (:assistant/final persisted)) "non-result fields are untouched"))))

(deftest append-turn-leaves-small-results-alone
  (let [turn {:session/id "s2"
              :tool/results [{:name "shell" :status :ok :stdout "hello"}]}]
    (session/append-turn! "s2" turn)
    (is (= "hello" (-> (session/load-turns "s2") first :tool/results first :stdout)))))

(deftest cap-handles-empty-and-missing-results
  (session/append-turn! "s3" {:session/id "s3" :assistant/final "no tools"})
  (is (= 1 (count (session/load-turns "s3"))) "a turn without results persists fine"))
