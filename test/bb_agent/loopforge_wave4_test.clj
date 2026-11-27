(ns bb-agent.loopforge-wave4-test
  (:require [babashka.fs :as fs]
            [bb-agent.gate-scope :as gs]
            [bb-agent.human-context :as hc]
            [bb-agent.progress-pub :as pp]
            [bb-agent.retention :as ret]
            [bb-agent.rsi-canary :as canary]
            [bb-agent.rsi-triage :as triage]
            [bb-agent.run-timing :as rt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest empirical-bayes-decay-triage-and-tiered-prefs-test
  (let [now-ms 1795800000000
        day-ms (* 24 3600 1000)]
    (testing "7-day half-life decay halves weight at 7 days"
      (is (< (Math/abs (- 1.0 (triage/decay-weight now-ms now-ms))) 1e-6))
      (is (< (Math/abs (- 0.5 (triage/decay-weight now-ms (- now-ms (* 7 day-ms))))) 1e-6)))

    (testing "empirical Bayes shrinks small-sample alarm toward baseline"
      (let [small-sample (triage/posterior-rate {:failures 1 :trials 1 :baseline 0.05})
            large-sample (triage/posterior-rate {:failures 40 :trials 50 :baseline 0.05})]
        (is (< small-sample 0.20) "1/1 failure shrinks toward 5% baseline instead of screaming 100%")
        (is (> large-sample 0.60) "40/50 failures stays clearly elevated")))

    (testing "tiered preferences format P1 > P2 > P3 priority guidance"
      (let [txt (triage/preference-priority-text [:clean :tokens :duration])
            guide (triage/preference-guidance [:clean :tokens])]
        (is (str/includes? txt "P1"))
        (is (str/includes? txt "P2"))
        (is (str/includes? guide "P1"))))))

(deftest canary-scoring-early-stop-and-post-release-watch-test
  (testing "3-point run scoring: 2 clean, 1 reworked, 0 failed, nil non-terminal"
    (is (= 2 (canary/score-run {:status :succeeded :rework-count 0})))
    (is (= 1 (canary/score-run {:status :succeeded :rework-count 2})))
    (is (= 0 (canary/score-run {:status :failed :rework-count 0})))
    (is (nil? (canary/score-run {:status :running}))))

  (testing "early-stops clearly worse canary at 3 runs"
    (let [verdict (canary/evaluate-canary {:candidate-scores [0 0 0]
                                           :control-scores [2 2 2 2 2]
                                           :min-runs 5})]
      (is (= :early-stop (:verdict verdict)))))

  (testing "post-release watch flags regression when post-release avg drops sharply"
    (is (= :degraded (:verdict (canary/evaluate-post-release-watch
                                {:baseline-scores [2 2 2 2 2]
                                 :post-scores [0 0 1 0 0]}))))
    (is (= :healthy (:verdict (canary/evaluate-post-release-watch
                               {:baseline-scores [2 2 1 2 2]
                                :post-scores [2 2 2 2 1]}))))))

(deftest true-execution-time-interval-union-test
  (let [t0 1000
        ;; Node A runs [1000, 4000], overlapping Node B [2000, 5000] -> union is 4000ms (not 6000ms!)
        ;; Then idle/waiting until 15000, then Node C [15000, 17000] -> +2000ms = 6000ms execution
        events [{:type :node/started :node "A" :at 1000}
                {:type :node/started :node "B" :at 2000}
                {:type :node/succeeded :node "A" :at 4000}
                {:type :node/succeeded :node "B" :at 5000}
                {:type :node/started :node "C" :at 15000}
                {:type :node/succeeded :node "C" :at 17000}
                {:type :run/succeeded :at 17000}]
        res (rt/compute-timing {:created-at t0} events 20000)]
    (is (= 6000 (:execution-ms res)))
    (is (= 16000 (:wall-ms res)))
    (is (= 10000 (:waiting-ms res)))))

(deftest atomic-human-context-and-align-conflict-test
  (let [dir (fs/create-temp-dir {:prefix "theseus-hc-"})]
    (try
      (let [note (hc/compose-feedback "retry" "Keep the public API backwards compatible.")
            path (hc/write-human-context! (str dir) note)]
        (is (fs/regular-file? path))
        (is (str/includes? (slurp path) "backwards compatible"))
        (is (str/includes? (hc/read-human-context (str dir)) "backwards compatible")))
      (is (true? (hc/baseline-align-paused? {:conflict-blocks [{:file "a.clj" :ours "1" :theirs "2"}]})))
      (is (false? (hc/baseline-align-paused? {:conflict-blocks []})))
      (finally
        (fs/delete-tree dir)))))

(deftest two-horizon-retention-and-nonblocking-progress-and-whitelist-test
  (testing "retention enforces record-days >= body-days and trims old bodies"
    (let [pol (ret/normalize-policy {:body-days 90 :record-days 30})]
      (is (= 90 (:body-days pol)))
      (is (= 90 (:record-days pol)) "record horizon raised to body horizon"))
    (let [now-ms 1795800000000
          day-ms (* 24 3600 1000)
          rows [{:id 1 :ts-ms (- now-ms (* 10 day-ms)) :body "fresh"}
                {:id 2 :ts-ms (- now-ms (* 100 day-ms)) :body "old-body"}
                {:id 3 :ts-ms (- now-ms (* 200 day-ms)) :body "expired-row"}]
          {:keys [kept bodies-stripped records-dropped]}
          (ret/apply-retention rows now-ms {:body-days 90 :record-days 180})]
      (is (= 1 bodies-stripped))
      (is (= 1 records-dropped))
      (is (= 2 (count kept)))
      (is (nil? (:body (second kept))))))

  (testing "non-blocking 1-slot progress publisher coalesces without blocking caller"
    (let [seen (atom [])
          pub (pp/create-publisher (fn [s] (swap! seen conj s)))]
      (try
        (pp/publish! pub "Working on step 1")
        (Thread/sleep 40)
        (is (some #(= "Working on step 1" %) @seen))
        (is (not (str/includes? (pp/sanitize-public-status "sk-airo-secret1234567890") "sk-airo-secret")))
        (finally
          (pp/close! pub)))))

  (testing "file-whitelist scope gate flags any out-of-scope changed file"
    (is (true? (:ok? (gs/check-files-whitelist ["src/a.clj" "test/a_test.clj"]
                                               ["src/a.clj" "test/a_test.clj" "bb.edn"]))))
    (let [bad (gs/check-files-whitelist ["src/a.clj" "src/unplanned.clj"] ["src/a.clj"])]
      (is (false? (:ok? bad)))
      (is (= ["src/unplanned.clj"] (:out-of-scope bad))))))
