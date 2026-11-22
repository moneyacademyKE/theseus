(ns bb-agent.duskbot-tier3-test
  (:require [babashka.fs :as fs]
            [bb-agent.batch-checklist :as bc]
            [bb-agent.checkpoints :as ckpt]
            [bb-agent.config :as config]
            [bb-agent.memory-dream :as dream]
            [bb-agent.state-hygiene :as hygiene]
            [bb-agent.warm-compaction :as warm]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest idle-gap-warm-compaction-lifecycle
  (testing "schedules compaction after idle gap and awaits when turn needs compaction"
    (let [ran (atom 0)
          sched (warm/create-scheduler {:min-tokens 1000 :idle-ms 20})
          _ (warm/schedule-warm! sched "s1" 2000 (fn []
                                                   (Thread/sleep 20)
                                                   (swap! ran inc)
                                                   {:compacted true}))
          res (warm/before-turn! sched "s1" {:needs-compaction? true})]
      (is (= {:compacted true} res))
      (is (= 1 @ran))))
  (testing "skips scheduling below min-tokens and cancels cleanly when not needed"
    (let [ran (atom 0)
          sched (warm/create-scheduler {:min-tokens 100000 :idle-ms 500})]
      (is (nil? (warm/schedule-warm! sched "s2" 500 (fn [] (swap! ran inc)))))
      (warm/schedule-warm! sched "s3" 150000 (fn [] (swap! ran inc)))
      (is (nil? (warm/before-turn! sched "s3" {:needs-compaction? false})))
      (is (= 0 @ran)))))

(deftest monotonic-crash-resumable-batch-checklist
  (let [dir (fs/create-temp-dir {:prefix "theseus-checklist-"})]
    (try
      (with-redefs [config/home (constantly (str dir))]
        (bc/start-checklist! "s1" "Audit files" ["file-a.clj" "file-b.clj" "file-c.clj"])
        (bc/tick! "s1" "file-a.clj" :done)
        (bc/tick! "s1" "file-b.clj" :skipped)
        (testing "restarting checklist after crash preserves ticked/skipped items monotonically"
          (let [merged (bc/start-checklist! "s1" "Audit files"
                                            ["file-a.clj" "file-b.clj" "file-c.clj" "file-d.clj"])]
            (is (= :done (:status (first (:items merged)))))
            (is (= :skipped (:status (second (:items merged)))))
            (is (= :open (:status (nth (:items merged) 2))))
            (is (= :open (:status (nth (:items merged) 3))))
            (is (= "1 done, 1 skipped, 2 open (4 total)" (bc/progress-line merged)))
            (is (empty? (hygiene/violations (fs/path dir "state")))))))
      (finally
        (fs/delete-tree dir)))))

(deftest dream-consolidation-verifies-user-quotes-deterministically
  (let [convs [{:session/id "c1"
                :turns [{:user/input "Always use Babashka instead of Python for scripts."
                         :assistant/final "Got it."}]}
               {:session/id "c2"
                :turns [{:user/input "Never use npm in this workspace."
                         :assistant/final "Understood."}]}]
        fake-json "{\"learned\":[{\"text\":\"Prefer Babashka\",\"confidence\":0.9,\"sessions\":[\"c1\",\"c2\"],\"evidence\":\"use Babashka instead of Python\"},{\"text\":\"Hallucinated pref\",\"confidence\":0.95,\"sessions\":[\"c1\",\"c2\"],\"evidence\":\"loves ruby on rails\"}],\"outdated\":[],\"ideas\":[],\"missed\":[],\"declined\":[]}"
        res (dream/run-dream convs (fn [_prompt] fake-json))]
    (is (= 1 (count (:learned res)))
        "only quote-verified entries survive deterministic verification")
    (is (= "Prefer Babashka" (:text (first (:learned res)))))
    (is (= 1 (:rejected-count res)))))

(deftest shadow-git-checkpoints-snapshot-diff-and-restore-without-touching-repo
  (let [home (fs/create-temp-dir {:prefix "theseus-ckpt-home-"})
        work (fs/create-temp-dir {:prefix "theseus-ckpt-work-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (testing "refuses dangerous root/home directories"
          (is (some? (ckpt/refusal-reason "/")))
          (is (some? (ckpt/refusal-reason (System/getProperty "user.home")))))
        (testing "snapshots, diffs, and restores a plain non-git workspace"
          (spit (str (fs/path work "note.txt")) "v1\n")
          (let [s1 (ckpt/snapshot! (str work) "before turn 1")]
            (is (true? (:ok? s1)))
            (is (not (fs/exists? (fs/path work ".git"))) "never creates .git inside workspace")
            (spit (str (fs/path work "note.txt")) "v2-broken\n")
            (let [d (ckpt/diff-since (str work) (:commit s1))]
              (is (str/includes? d "v2-broken")))
            (let [r (ckpt/restore! (str work) (:commit s1))]
              (is (true? (:ok? r)))
              (is (= "v1\n" (slurp (str (fs/path work "note.txt")))))
              (is (empty? (hygiene/violations (fs/path home "state"))))))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree work)))))
