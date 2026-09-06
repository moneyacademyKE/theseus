(ns bb-agent.goal.lock-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [bb-agent.goal.lock :as lock])
  (:import (java.nio.file Files)))

(def ^:dynamic *lock-dir* nil)

(defn- tmp-dir []
  (str (Files/createTempDirectory "goal-lock-test"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(use-fixtures :each (fn [f] (binding [*lock-dir* (tmp-dir)] (f))))

(defn- lock-path []
  (str (io/file *lock-dir* "test.lock")))

(deftest acquire-creates-lockfile-with-pid
  (let [p (lock-path)]
    (is (lock/acquire! p))
    (is (.exists (io/file p)))
    (is (= (.pid (java.lang.ProcessHandle/current))
           (Long/parseLong (slurp p))))
    (lock/release! p)))

(deftest second-acquire-while-live-refused
  (let [p (lock-path)]
    (lock/acquire! p)
    ;; The lockfile holds our own pid, which is definitely live.
    (is (thrown-with-msg? Exception #"Lock held by a live process"
                          (lock/acquire! p)))
    (lock/release! p)))

(deftest stale-lock-is-stolen
  (let [p (lock-path)]
    (spit p "999999999") ;; pid essentially never alive
    (is (lock/acquire! p) "stale lock should be stolen")
    (lock/release! p)))

(deftest corrupt-lockfile-is-stolen
  (let [p (lock-path)]
    (spit p "not-a-pid")
    (is (lock/acquire! p) "unparseable lock should be stolen")
    (lock/release! p)))

(deftest release-removes-lockfile
  (let [p (lock-path)]
    (lock/acquire! p)
    (lock/release! p)
    (is (not (.exists (io/file p))))
    (lock/release! p) ; idempotent
    (is (not (.exists (io/file p))))))

(deftest re-acquire-after-release-succeeds
  (let [p (lock-path)]
    (lock/acquire! p)
    (lock/release! p)
    (is (lock/acquire! p))
    (lock/release! p)))

(deftest creates-parent-dirs
  (let [p (str (io/file *lock-dir* "nested" "deeper" "x.lock"))]
    (is (lock/acquire! p))
    (lock/release! p)))
