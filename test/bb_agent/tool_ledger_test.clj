(ns bb-agent.tool-ledger-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest executor-records-wall-time-without-arguments
  (let [home (fs/create-temp-dir {:prefix "tool-ledger-test-"})]
    (with-redefs [config/home (fn [] (str home))]
      (let [result (tool/handle-tool-request
                    {:tool/name "definitely-not-a-tool"
                     :tool/args {:secret-argument "never-record-this"}
                     :session/id "tool-ledger-test"}
                    {:approval/mode :auto-all})
            events (usage/load-events)
            event (first events)]
        (is (= :error (:status result)))
        (is (= 1 (count events)))
        (is (= "definitely-not-a-tool" (:tool/name event)))
        (is (number? (:tool/ms event)))
        (is (<= 0 (:tool/ms event)))
        (is (contains? event :tool/executed?))
        (is (not-any? #(str/includes? (name %) "argument") (keys event)))
        (is (not (str/includes? (pr-str event) "never-record-this")))))))

(deftest summary-separates-outcomes-and-tolerates-legacy-events
  (let [events [{:usage/event :tool :tool/name "shell" :tool/status :ok
                 :ok true :tool/ms 10}
                {:usage/event :tool :tool/name "shell" :tool/status :error
                 :ok false :tool/ms 30}
                {:usage/event :tool :tool/name "shell" :tool/status :denied
                 :ok false :tool/ms 5}
                {:usage/event :tool :tool/name :web_search :tool/status :ok
                 :ok true}
                {:usage/event :turn :tokens/total 100}]
        rows (usage/tool-summary events)
        shell (first rows)
        search (second rows)]
    (testing "calls sort by volume; status classes do not blur together"
      (is (= 2 (count rows)))
      (is (= "shell" (:tool shell)))
      (is (= 3 (:calls shell)))
      (is (= 1 (:ok shell)))
      (is (= 1 (:error shell)))
      (is (= 1 (:denied shell)))
      (is (= 15 (:avg-ms shell)))
      (is (= 30 (:max-ms shell))))
    (testing "legacy rows have counts but an honest missing timing value"
      (is (= "web_search" (:tool search)))
      (is (= 1 (:calls search)))
      (is (nil? (:avg-ms search)))
      (is (str/includes? (usage/render-tool-summary events) "AVG-MS")))))
