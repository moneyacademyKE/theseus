(ns bb-agent.hot-reload-test
  (:require [bb-agent.hot-reload :as reload]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(deftest snapshot-detects-create-and-change
  (let [root (fs/create-temp-dir {:prefix "reload-"})
        file (fs/path root "src" "x.clj")]
    (try
      (fs/create-dirs (fs/parent file))
      (spit (str file) "one")
      (let [before (reload/snapshot [root])]
        (Thread/sleep 20)
        (spit (str file) "two")
        (is (= [(str (.toPath (fs/file file)))]
               (reload/changed-paths before (reload/snapshot [root])))))
      (finally (fs/delete-tree root)))))

(deftest impact-keeps-reload-surface-narrow
  (let [effect (reload/impact ["/home/theseus/theseus/src/bb_agent/web.clj"
                               "/home/theseus/config.edn"
                               "/home/theseus/theseus/web/js/main.js"])]
    (is (= #{:web :telegram} (:restart effect)))
    (is (empty? (:manual-plists effect)))))

(deftest impact-reports-plists-without-kicking-them
  (let [effect (reload/impact ["/home/theseus/theseus/ops/launchd/com.theseus.web.plist"])]
    (is (empty? (:restart effect)))
    (is (= 1 (count (:manual-plists effect))))))
