(ns bb-agent.atomic-io-test
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [bb-agent.outbox :as outbox]
            [bb-agent.subagent :as sa]
            [bb-agent.telegram-notes :as notes]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(deftest write-private-file-is-born-0600-and-atomic
  (let [dir (fs/create-temp-dir {:prefix "theseus-aio-"})
        target (fs/path dir "secret.edn")]
    (try
      (aio/write-private-file! target (pr-str {:token "abc"}))
      (is (fs/regular-file? target))
      (is (= {:token "abc"} (edn/read-string (slurp (str target)))))
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions target))))
      (finally
        (fs/delete-tree dir)))))

(deftest concurrent-update-edn-preserves-every-update
  (let [dir (fs/create-temp-dir {:prefix "theseus-aio-race-"})
        target (fs/path dir "counter.edn")]
    (try
      (let [futs (mapv (fn [i]
                         (future
                           (aio/update-edn! target [] #(conj % i))))
                       (range 20))]
        (doseq [f futs] @f)
        (let [final (aio/read-edn-safe target [])]
          (is (= 20 (count final)))
          (is (= (set (range 20)) (set final)))))
      (finally
        (fs/delete-tree dir)))))

(deftest config-write-and-restore-preserve-0600-permissions
  (let [home (fs/create-temp-dir {:prefix "theseus-cfg-perm-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (config/write-config! {:provider :fake :telegram {:token "111:secret"}})
        (config/write-config! {:provider :fake :telegram {:token "222:secret"}})
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (config/config-file)))))
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (config/last-good-file)))))
        (config/restore-last-good!)
        (is (= "111:secret" (get-in (config/load-config) [:telegram :token])))
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (config/config-file))))))
      (finally
        (fs/delete-tree home)))))

(deftest outbox-dead-letter-replaces-existing-collision
  (let [dir (fs/create-temp-dir {:prefix "theseus-outbox-dead-"})
        c {:outbox-root (str dir)}]
    (try
      (fs/create-dirs (fs/path dir "dead"))
      (spit (str (fs/path dir "dead" "broken.edn")) "{old poison")
      (spit (str (fs/path dir "broken.edn")) "{new poison")
      (let [res (outbox/drain! c (fn [_] (throw (ex-info "must not run" {}))))]
        (is (= 1 (:poison res)))
        (is (= "{new poison" (slurp (str (fs/path dir "dead" "broken.edn"))))))
      (finally
        (fs/delete-tree dir)))))

(deftest telegram-notes-survives-corrupt-edn-and-concurrent-adds
  (let [home (fs/create-temp-dir {:prefix "theseus-notes-race-"})]
    (try
      (testing "corrupt existing notes file recovers cleanly instead of throwing"
        (let [p (fs/path home "state" "telegram-edits" "s1.edn")]
          (fs/create-dirs (fs/parent p))
          (spit (str p) "[unterminated")
          (is (some? (notes/add-raw! (str home) "s1" "fresh note")))
          (is (= ["fresh note"] (notes/take! (str home) "s1")))))
      (testing "concurrent add-raw! calls do not lose updates"
        (let [futs (mapv (fn [i]
                           (future (notes/add-raw! (str home) "s2" (str "n-" i))))
                         (range 10))]
          (doseq [f futs] @f)
          (let [taken (notes/take! (str home) "s2")]
            (is (= 10 (count taken)))
            (is (= (set (map #(str "n-" %) (range 10))) (set taken))))))
      (finally
        (fs/delete-tree home)))))

(deftest subagent-concurrent-spawns-preserve-all-records
  (let [home (fs/create-temp-dir {:prefix "theseus-subagent-race-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (let [futs (mapv (fn [i]
                           (future (sa/spawn! (str "job-" i) (str "work-" i))))
                         (range 12))]
          (doseq [f futs] @f)
          (let [ledger (edn/read-string (slurp (str (fs/path home "state" "subagents.edn"))))]
            (is (= 12 (count ledger)))
            (is (= (set (map #(str "job-" %) (range 12)))
                   (set (map :subagent/id ledger)))))))
      (finally
        (fs/delete-tree home)))))
