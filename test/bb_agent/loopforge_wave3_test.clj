(ns bb-agent.loopforge-wave3-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.gate-scope :as gs]
            [bb-agent.goal.config :as goal-config]
            [bb-agent.knowledge-usage :as ku]
            [bb-agent.provider :as provider]
            [bb-agent.queue-brake :as qb]
            [bb-agent.rsi :as rsi]
            [bb-agent.secret-mask :as sm]
            [bb-agent.tool :as tool]
            [bb-agent.web-data :as web-data]
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

(deftest loopforge-live-wiring-integration-test
  (let [home (fs/create-temp-dir {:prefix "theseus-lf-wire-"})
        kfile (fs/path home "brain" "knowledge" "wired.md")
        cfile (fs/path home "conflict.clj")]
    (try
      (fs/create-dirs (fs/parent kfile))
      (spit (str kfile) "# Wired Knowledge\nhello\n")
      (spit (str cfile) "<<<<<<< HEAD\n(def a 1)\n=======\n(def a 2)\n>>>>>>> feat\n")
      (with-redefs [config/home (constantly (str home))]
        (config/write-config! {:provider :openai-compatible
                               :providers {:openai-compatible {:base-url "https://x" :api-key "sk-secret-999"}}
                               :telegram {:token "bot-secret-123"}})
        ;; 1. read_file records :read in knowledge-usage and rsi/digest surfaces it
        (let [r1 (tool/handle-tool-request {:tool/name "read_file" :tool/args {:path (str kfile)}}
                                           {:session/id "lw-1" :approval/mode :auto-all})]
          (is (= :ok (:status r1)))
          (is (= 1 (get-in (ku/aggregate) ["wired.md" :read])))
          (is (= 1 (get-in (rsi/digest []) [:knowledge-usage "wired.md" :read]))))
        ;; 2. read_file extracts :conflict/blocks on git conflict markers
        (let [r2 (tool/handle-tool-request {:tool/name "read_file" :tool/args {:path (str cfile)}}
                                           {:session/id "lw-1" :approval/mode :auto-all})]
          (is (= :ok (:status r2)))
          (is (= 1 (count (:conflict/blocks r2)))))
        ;; 3. web-data/safe-config-view masks provider & telegram secrets
        (let [safe (web-data/safe-config-view)]
          (is (= sm/masked-sentinel (get-in safe [:providers :openai-compatible :api-key])))
          (is (= sm/masked-sentinel (get-in safe [:telegram :token]))))
        ;; 4. goal/config validate-config flags git command in :run scope
        (let [errs (goal-config/validate-config
                    {:goal :ok :progress :sig :act {:sh "echo hi"}
                     :verification-gates [{:id "g1" :mode :command :execution-scope :run :commands ["git status"]}]})]
          (is (some #(= :verification-gates (:key %)) errs))))
      (finally
        (fs/delete-tree home)))))
