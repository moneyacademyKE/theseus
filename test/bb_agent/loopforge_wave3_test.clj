(ns bb-agent.loopforge-wave3-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.gate-scope :as gs]
            [bb-agent.provider :as provider]
            [bb-agent.queue-brake :as qb]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest mid-turn-steer-interrupt-redirects-without-cancelling-test
  (let [home (fs/create-temp-dir {:prefix "theseus-steer-"})
        steer-q (atom "pivot to checking config.edn instead")
        calls (atom 0)
        seen (atom [])]
    (try
      (with-redefs [config/home (constantly (str home))
                    tool/handle-tool-request
                    (fn [_ _] {:tool/name "git_status" :status :ok :output "clean"})
                    provider/complete
                    (fn [_ req]
                      (swap! seen conj (:messages req))
                      (if (= 1 (swap! calls inc))
                        {:role :assistant
                         :tool/requests [{:tool/name "git_status" :tool/args {}}]}
                        {:role :assistant :content "Pivoted and checked config.edn."}))]
        (let [turn (core/run-turn!
                    {:provider :fake :model "m" :session/id "steer-1"
                     :turn/drain-steer #(let [v @steer-q] (reset! steer-q nil) v)}
                    "check git")]
          (is (= 2 @calls))
          (is (true? (:turn/steered? turn)))
          (is (some #(str/includes? (str (:content %)) "pivot to checking config.edn") (second @seen)))
          (is (= "Pivoted and checked config.edn." (:assistant/final turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest queue-depth-brake-prevents-outage-backlog-storms-test
  (let [brake (qb/create-brake {:max-depth 2})]
    (is (true? (qb/try-acquire! brake :hourly-brief)))
    (is (true? (qb/try-acquire! brake :hourly-brief)))
    (is (false? (qb/try-acquire! brake :hourly-brief)) "3rd concurrent/queued job blocked by depth brake")
    (qb/release! brake :hourly-brief)
    (is (true? (qb/try-acquire! brake :hourly-brief)) "slot reopens after release")))

(deftest verification-gate-scope-validator-test
  (is (true? (gs/command-needs-repository? "git status --short")))
  (is (true? (gs/command-needs-repository? "set -e && git diff --exit-code")))
  (is (false? (gs/command-needs-repository? "bb test:fast")))
  (is (true? (:ok? (gs/validate-gate {:id "g1" :mode :command :execution-scope :repositories :commands ["git status"]}))))
  (is (some? (:error (gs/validate-gate {:id "g2" :mode :command :execution-scope :run :commands ["git status"]})))
      "git command in :run scope must fail validation")
  (is (some? (:error (gs/validate-gate {:id "g3" :mode :bogus})))))
