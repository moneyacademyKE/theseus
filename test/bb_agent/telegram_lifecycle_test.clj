(ns bb-agent.telegram-lifecycle-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-lifecycle :as lifecycle]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import (java.nio.file Files)))

(defn- tmp-home-fixture [f]
  (let [dir (str (Files/createTempDirectory "theseus-lifecycle-test"
                                            (into-array java.nio.file.attribute.FileAttribute [])))]
    (with-redefs [config/home (constantly dir)]
      (f))))

(use-fixtures :each tmp-home-fixture)

(deftest fresh-home-acquires-the-lock
  (is (true? (lifecycle/acquire-instance-lock!)))
  (is (= (str (.pid (java.lang.ProcessHandle/current)))
         (str/trim (slurp (str (fs/path (config/home) "state" "poller.pid")))))
      "the file carries the live pid"))

(deftest a-live-foreign-pid-refuses-the-boot
  (let [path (str (fs/path (config/home) "state" "poller.pid"))
        ;; a real live pid owned by this user (kill -0 on pid 1 fails with
        ;; EPERM for non-root — permission errors read as "dead")
        holder (.. (ProcessBuilder. ["sleep" "30"]) start)
        live-pid (.pid holder)]
    (try
      (fs/create-dirs (fs/parent path))
      (spit path (str live-pid))
      (is (false? (lifecycle/acquire-instance-lock!))
          "a second poller must refuse to start")
      (is (= (str live-pid) (str/trim (slurp path)))
          "a refused boot never overwrites the holder")
      (finally
        (.destroy holder)))))

(deftest a-stale-pid-is-taken-over
  (let [path (str (fs/path (config/home) "state" "poller.pid"))]
    (fs/create-dirs (fs/parent path))
    ;; 99999999 is past any realistic pid ceiling — dead by construction
    (spit path "99999999")
    (is (true? (lifecycle/acquire-instance-lock!)) "a crashed poller's lock heals itself")
    (is (= (str (.pid (java.lang.ProcessHandle/current)))
           (str/trim (slurp path))))))

(deftest reacquiring-own-lock-is-idempotent
  (is (true? (lifecycle/acquire-instance-lock!)))
  (is (true? (lifecycle/acquire-instance-lock!)) "a kickstart mid-shutdown must not deadlock"))
