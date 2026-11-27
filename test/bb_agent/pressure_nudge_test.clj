(ns bb-agent.pressure-nudge-test
  (:require [bb-agent.truncation :as trunc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest pre-compaction-pressure-band-test
  (testing "fires only inside the 55-64% band, silent below 55% and at/above 65%"
    (is (nil? (trunc/context-pressure-nudge 5000 10000)))
    (is (nil? (trunc/context-pressure-nudge 5499 10000)))
    (let [nudge (trunc/context-pressure-nudge 5500 10000)]
      (is (string? nudge))
      (is (str/includes? (str/lower-case nudge) "persist"))
      (is (not (re-find #"\d+%" nudge))
          "behavioral nudge must not cite a noisy percentage"))
    (is (string? (trunc/context-pressure-nudge 6400 10000)))
    (is (nil? (trunc/context-pressure-nudge 6500 10000)))
    (is (nil? (trunc/context-pressure-nudge 1000 0)))))
