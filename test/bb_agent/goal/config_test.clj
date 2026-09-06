(ns bb-agent.goal.config-test
  "Regression for bk-2105: atomic config writes must land 0600.
  config.edn and config.last-good.edn both carry secrets (bot token,
  halt-notification HMAC) — umask-wide files are a live exposure."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- mode
  "POSIX permission string (e.g. \"rw-------\") for a path."
  [p]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (Paths/get (str p) (make-array String 0))
                                  (make-array LinkOption 0))))

(def ^:private ^:dynamic *tmp-home* nil)

(defn- fresh-home
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "goal-config-test"}))]
    (try
      (binding [*tmp-home* dir]
        (with-redefs [config/home (constantly dir)]
          (f)))
      (finally
        (fs/delete-tree dir)))))

(use-fixtures :each fresh-home)

(deftest config-writes-are-owner-only
  (testing "write-config! and restore-last-good! land 0600 on every secret-bearing file"
    (config/write-config! {:token "secret-a"})
    (is (= "rw-------" (mode (config/config-file)))
        "config.edn itself must be owner-only")
    (config/write-config! {:token "secret-b"})
    (is (= "rw-------" (mode (config/last-good-file)))
        "last-good snapshot carries the previous secret too")
    (is (= "rw-------" (mode (config/config-file))))
    (is (= "secret-a" (:token (config/restore-last-good!)))
        "restore returns the previous (merged) config, previous secret intact")
    (is (= "rw-------" (mode (config/config-file)))
        "restore path must not regress permissions")))
