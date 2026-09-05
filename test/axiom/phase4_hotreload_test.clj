(ns axiom.phase4-hotreload-test
  "Phase 4b -- hot-reload / config-swap tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [axiom.config :as config]
            [axiom.core :as core]
            [babashka.fs :as fs]))

(defn- valid-cfg
  [dir overrides]
  (merge
   {:name "hotreload-fixture"
    :workdir dir
    :lock (str dir "/.axiom.lock")
    :log-dir (str dir "/logs")
    :observers {:n {:sh "cat n 2>/dev/null || echo 0" :parse :int}}
    :goal {:op :>= :ref :n :value 99}
    :progress :n
    :integrity []
    :act {:sh "echo act >> acts.log; sleep 0.05; n=$(cat n 2>/dev/null || echo 0); echo $((n+1)) > n"}
    :stall-after 99
    :max-rollbacks 0}
   overrides))

(defn- write-config!
  [path cfg]
  (spit path (pr-str (dissoc cfg :workdir :lock :log-dir))))

(deftest maybe-reload-keeps-current-config-when-mtime-unchanged
  (let [dir (str (fs/create-temp-dir))
        path (str dir "/config.edn")]
    (try
      (write-config! path (valid-cfg dir {}))
      (let [cfg (config/load-config path)
            mt  (config/file-mtime path)
            [cfg' mt'] (config/maybe-reload cfg path mt)]
        (is (= cfg cfg'))
        (is (nil? mt') "nil mtime means no swap happened"))
      (finally (fs/delete-tree dir)))))

(deftest maybe-reload-loads-and-validates-when-file-changes
  (let [dir (str (fs/create-temp-dir))
        path (str dir "/config.edn")]
    (try
      (write-config! path (valid-cfg dir {:goal {:op :>= :ref :n :value 99}}))
      (let [cfg (config/load-config path)
            mt  (config/file-mtime path)]
        (Thread/sleep 1100)
        (write-config! path (valid-cfg dir {:goal {:op :>= :ref :n :value 2}}))
        (let [[cfg' mt'] (config/maybe-reload cfg path mt)]
          (is (= {:op :>= :ref :n :value 2} (:goal cfg')))
          (is (integer? mt'))
          (is (not= mt mt'))))
      (finally (fs/delete-tree dir)))))

(deftest maybe-reload-throws-when-changed-config-is-invalid
  (let [dir (str (fs/create-temp-dir))
        path (str dir "/config.edn")]
    (try
      (write-config! path (valid-cfg dir {}))
      (let [cfg (config/load-config path)
            mt  (config/file-mtime path)]
        (Thread/sleep 1100)
        (spit path (pr-str (dissoc (valid-cfg dir {}) :progress)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (config/maybe-reload cfg path mt))))
      (finally (fs/delete-tree dir)))))

(deftest run-hot-reloads-config-mid-flight-without-losing-state
  (testing "a config file rewrite swaps the goal and logs hot-reload"
    (let [dir (str (fs/create-temp-dir))
          path (str dir "/config.edn")]
      (try
        (spit (str dir "/n") "0\n")
        (let [initial (valid-cfg dir {:hot-reload true
                                      :identical-retry-guard true
                                      :goal {:op :>= :ref :n :value 99}})
              swapped (valid-cfg dir {:hot-reload true
                                      :identical-retry-guard true
                                      :goal {:op :>= :ref :n :value 2}})]
          (write-config! path initial)
          (let [cfg (merge (config/load-config path)
                           (select-keys initial [:workdir :lock :log-dir]))
                rewriter (future
                           (Thread/sleep 180)
                           (write-config! path swapped))
                res (core/run! cfg {:max-iters 20 :config-path path})
                events (slurp (str dir "/logs/events.log"))]
            @rewriter
            (is (= :done (:status res)))
            (is (<= 2 (:iterations res)) "the run needed pre-swap attempts")
            (is (str/includes? events "hot-reload") "swap is logged")
            (is (<= 2 (count (str/split-lines (slurp (str dir "/acts.log")))))
                "attempt state survived long enough to keep acting after the swap")))
        (finally (fs/delete-tree dir))))))

(defn -main
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.phase4-hotreload-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
