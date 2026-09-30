(ns e2e.rsi-tools-test
  "The tool-outcome funnel (goal make-rsi-do-this): every tool call lands
  in the usage ledger as :usage/event :tool, recorded at exactly one site
  (tool.clj/handle-tool-request); the RSI digest turns those events into
  the per-tool table and the tool-failures opportunity. Fixture isolation
  mirrors e2e.rsi-test: temp home via config/home redef."
  (:require [babashka.fs :as fs]
            [bb-agent.rsi :as rsi]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-home! []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "state"))
    dir))

(defn- tool-call!
  "One tool call inside `home`'s ledger. Defaults to a real git_status
  execution (auto-safe: read-only tools execute without approval)."
  [home & {:as request}]
  (with-redefs [bb-agent.config/home (constantly home)]
    (tool/handle-tool-request (merge {:tool/name "git_status"
                                      :approval/policy :auto-safe
                                      :tool/args {:cwd "/Users/moe/theseus/theseus"}
                                      :session/id "funnel-test"}
                                     request)
                              {})))

(deftest funnel-records-every-outcome-at-the-single-site
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (testing "an executed call records ok"
        (let [ok (tool-call! home)]
          (is (= :ok (:status ok)))
          (let [events (usage/load-events)]
            (is (= 1 (count events)))
            (is (= {:usage/event :tool :tool/name "git_status" :ok true}
                   (select-keys (first events) [:usage/event :tool/name :ok]))))))
      (testing "a denied call records its denial source"
        (let [denied (tool-call! home :tool/name "shell"
                                 :approval/policy :ask
                                 :tool/args {:cmd "echo hi"})]
          (is (= :denied (:status denied)))
          (let [events (usage/load-events)]
            (is (= 2 (count events)))
            (is (= {:tool/name "shell" :ok false :denial/source :approval}
                   (select-keys (second events) [:tool/name :ok :denial/source])))))))))

(deftest attribution-is-the-price-of-admission
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (tool/handle-tool-request {:tool/name "git_status"
                                 :approval/policy :auto-safe
                                 :tool/args {:cwd "/Users/moe/theseus/theseus"}}
                                {})
      (is (= [] (usage/load-events))
          "no session id anywhere — a fixture, not signal; the live ledger
           must not accumulate contextless test traffic"))))

(deftest digest-separates-turns-from-tool-events
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event {:session-id "s1" :provider :test-provider
                                         :model "m" :prompt "p" :final "f" :ok true}))
      (usage/tool-event! {:tool "git_status" :session-id "s1" :status :ok :ok true})
      (let [d (rsi/digest)]
        (is (= 1 (:events d)) "turn count, not raw event count")
        (is (= 1 (get-in d [:tools "git_status" :calls])))
        (is (= {:test-provider {:turns 1 :ok 1 :fail 0 :fallback-hits 0}}
               (:providers d))
            "tool events never leak into the provider table as a nil row")))))

(deftest write-digest-renders-the-tool-table
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/tool-event! {:tool "git_status" :session-id "s1" :status :ok :ok true})
      (let [path (rsi/write-digest!)]
        (is (str/includes? (slurp (str path)) "| git_status | 1 | 1 | 0 | 0 |"))))))

(deftest tool-failure-opportunity-flags-errors-not-denials
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [turn (usage/event {:session-id "s" :provider :test-provider
                               :model "m" :prompt "p" :final "f" :ok true})
            shell-err (repeat 2 {:usage/event :tool :tool/name "shell"
                                 :ok false :tool/status :error})
            shell-ok (repeat 3 {:usage/event :tool :tool/name "shell"
                                :ok true :tool/status :ok})
            denied (repeat 2 {:usage/event :tool :tool/name "write_file"
                              :ok false :tool/status :denied
                              :denial/source :approval})
            analysis (rsi/analyze {:min-events 0
                                   :events (cons turn
                                                 (concat shell-err shell-ok denied))})]
        (is (some #(and (= :tool-failures (:kind %)) (= "shell" (:tool %)))
                  (:opportunities analysis))
            "5 calls, 2 errors = 40% — flagged")
        (is (not-any? #(and (= :tool-failures (:kind %)) (= "write_file" (:tool %)))
                      (:opportunities analysis))
            "denials are decisions, not defects — and under 5 calls besides")))))
