(ns e2e.stats-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bb-agent.usage :as usage]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- base-event [provider]
  {:usage/event :turn
   :session/id "s1"
   :provider provider
   :model "m"
   :tokens/input 10
   :tokens/output 5
   :tokens/cache-read 0
   :tokens/cache-write 0
   :tokens/total 15
   :cost/estimate-usd 0.0
   :created/at "2026-08-29T00:00:00Z"})

(deftest event-carries-fallback-fields
  (let [e (usage/event {:session-id "s1"
                        :provider :b
                        :model "m"
                        :prompt "hi"
                        :final "fake: hi"
                        :usage nil
                        :fallback-tried [:a :b]
                        :fallback-served :b})]
    (is (= [:a :b] (:fallback/tried e)))
    (is (= :b (:fallback/served e)))))

(deftest event-omits-fallback-fields-when-absent
  (let [e (usage/event {:session-id "s1" :provider :fake :model "m"
                        :prompt "hi" :final "fake: hi" :usage nil})]
    (is (nil? (:fallback/tried e)))
    (is (nil? (:fallback/served e)))))

(deftest report-aggregates-fallback-stats
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-stats-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn"))
            (pr-str [(assoc (base-event :b)
                            :fallback/tried [:a :b]
                            :fallback/served :b)
                     (base-event :a)]))
      (let [result (shell! home "usage" "report")]
        (is (= 0 (:exit result)) (:err result))
        (let [report (edn/read-string (:out result))
              fb (:fallback report)]
          (is (some? fb) (pr-str report))
          (is (= 1 (:hits fb)))
          (is (= 0.5 (:rate fb)))
          (is (= {:b 1} (:by-served fb)))
          (is (= 2 (:usage/events report)))))
      (finally
        (fs/delete-tree home)))))

(deftest report-zero-fallbacks-for-legacy-events
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-stats-legacy-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "usage.edn"))
            (pr-str [(base-event :a) (base-event :a)]))
      (let [result (shell! home "usage" "report")]
        (is (= 0 (:exit result)) (:err result))
        (let [report (edn/read-string (:out result))
              fb (:fallback report)]
          (is (some? fb) (pr-str report))
          (is (= 0 (:hits fb)))
          (is (= 0.0 (:rate fb)))
          (is (= {} (:by-served fb)))))
      (finally
        (fs/delete-tree home)))))
