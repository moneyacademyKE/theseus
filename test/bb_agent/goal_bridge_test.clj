(ns bb-agent.goal-bridge-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.goal-bridge :as bridge]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn with-tmp-goals [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "goal-bridge-test"}))]
    (binding [*tmp* tmp]
      (with-redefs [bridge/goals-root tmp
                    bridge/active-file (str tmp "/active.edn")]
        (f)))))

(use-fixtures :each with-tmp-goals)

(deftest slugify-test
  (testing "lowercases, dashes, caps length, uniquifies"
    (let [s (bridge/slugify "Build a Fancy CLI Tool!!")]
      (is (str/starts-with? s "build-a-fancy-cli-tool"))
      (is (re-matches #"[a-z0-9\-]+" s)))
    (is (str/starts-with? (bridge/slugify "!!!") "goal-"))))

(defn- live-pid
  "A definitely-signalable pid: a live `sleep` process. (kill -0 1 is
   permission-denied for users on macOS; bash-backgrounded children die with
   their session here — so spawn directly and read the raw java pid.)"
  []
  (.pid (:proc (p/process "sleep" "30"))))

(deftest active-run-test
  (testing "no registry file → nil"
    (is (nil? (bridge/active-run))))
  (testing "dead pid → nil and registry cleared"
    (spit bridge/active-file (pr-str {:pid 99999999 :name "stale"}))
    (is (nil? (bridge/active-run)))
    (is (not (.exists (io/file bridge/active-file)))))
  (testing "live pid → the run"
    (let [pid (live-pid)]
      (spit bridge/active-file (pr-str {:pid pid :name "live"}))
      (is (= "live" (:name (bridge/active-run))))
      (p/shell {:continue true} "kill" (str pid)))))

(deftest scaffold-test
  (let [ws (bridge/scaffold! "scaffolded")]
    (testing "workspace and git repo exist"
      (is (.exists (io/file ws)))
      (is (.exists (io/file ws ".git")))))
  (testing "the default goal methodology rides into references/ (owner directive 2026-09-07)"
    (let [home (str *tmp* "/home")]
      (fs/create-dirs (str home "/brain/knowledge"))
      (spit (str home "/brain/knowledge/goal-methodology.md") "# methodology")
      (with-redefs [config/home (constantly home)]
        (let [ws (bridge/scaffold! "methodology-ride")]
          (is (= "# methodology"
                 (slurp (str ws "/references/goal-methodology.md")))))))))

(defn- write-cfg [ws body]
  (fs/create-dirs ws)
  (spit (str ws "/config.edn") body))

(deftest validate-test
  (testing "broken config → problems as string"
    (let [ws (str *tmp* "/broken")]
      (write-cfg ws "{:name \"broken\" :workdir \".\"}")
      (is (string? (bridge/validate! (str ws "/config.edn"))))))
  (testing "structurally valid config → nil"
    (let [ws (str *tmp* "/valid")]
      (write-cfg ws (pr-str {:name "valid" :workdir ws :lock "./l.lock"
                             :log-dir "./logs"
                             :observers {:ok {:sh "echo pass" :parse :string}}
                             :goal {:op := :ref :ok :value "pass"}
                             :progress :ok
                             :act {:sh "./act.sh {{attempt}}"}
                             :stall-after 3 :max-rollbacks 2}))
      (is (nil? (bridge/validate! (str ws "/config.edn"))))))
  (testing "missing file → error string"
    (is (string? (bridge/validate! (str *tmp* "/nope/config.edn"))))))

(deftest run-status-test
  (testing "missing log → :authored"
    (is (= :authored (bridge/run-status "never-was"))))
  (let [ws (str *tmp* "/st")]
    (fs/create-dirs ws)
    (spit (str ws "/run.log") "act 1 ok\nGOAL FULFILLED {:x 0}")
    (is (= :fulfilled (bridge/run-status "st")))
    (spit (str ws "/run.log") "HALT: integrity {:check :format}")
    (is (= :halted (bridge/run-status "st")))
    (spit (str ws "/run.log") "act 1 ok")
    (is (= :running (bridge/run-status "st")))))

(deftest list-goals-test
  (testing "formats one line per workspace with status"
    (fs/create-dirs (str *tmp* "/alpha"))
    (spit (str *tmp* "/alpha/run.log") "GOAL FULFILLED {}")
    (fs/create-dirs (str *tmp* "/beta"))
    (let [lines (bridge/list-goals)]
      (is (some #(str/includes? % "alpha — fulfilled") lines))
      (is (some #(str/includes? % "beta — authored") lines)))))

(deftest handle-request-test
  (testing "non-goal text → nil"
    (is (nil? (bridge/handle-request! "hello there" 1 2 nil))))
  (testing "bare /goal → usage"
    (is (str/includes? (bridge/handle-request! "/goal" 1 2 nil) "Usage:")))
  (testing "/goals is not hijacked by the /goal seam"
    (is (nil? (bridge/handle-request! "/goals" 1 2 nil))))
  (testing "active run → refusal naming it"
    (let [pid (live-pid)]
      (spit bridge/active-file (pr-str {:pid pid :name "busy-goal"}))
      (is (str/includes? (bridge/handle-request! "/goal build x" 1 2 nil) "busy-goal"))
      (p/shell {:continue true} "kill" (str pid))))
  (testing "blank spec → usage (a trimmed blank IS bare /goal)"
    (is (str/includes? (bridge/handle-request! "/goal    " 1 2 nil) "Usage:"))
    (is (str/includes? (bridge/handle-request! (str "/goal " (apply str (repeat 500 "x"))) 1 2 nil)
                       "characters")))
  (testing "authoring failure envelope: a turn that writes an invalid config
            surfaces the validator's verdict, never launches"
    (with-redefs [core/run-turn! (fn [_cfg _prompt]
                                   (let [d (str bridge/goals-root "/author-fail")]
                                     (fs/create-dirs d)
                                     (spit (str d "/project.edn")
                                           "{:name \"bad\" :workdir \".\"}")))]
      (let [reply (bridge/handle-request! "/goal build something nice" 7 9 nil)]
        (is (str/includes? reply "🚫 Goal authoring failed"))
        (is (not (.exists (io/file bridge/active-file))))))))

(deftest progress-line-test
  (testing "act lines show progress movement and no-progress flag"
    (is (= "▸ it 1 act 6→4 · 14:47:00"
           (bridge/progress-line {:event :act :iteration 1 :progress-before 6
                                  :progress-after 4 :progressed? true
                                  :ts "2026-09-06T14:47:00.814992Z"})))
    (is (str/includes? (bridge/progress-line {:event :act :iteration 0 :progress-before 6
                                              :progress-after 6 :progressed? false
                                              :ts "2026-09-06T14:47:00Z"})
                       "(no progress)")))
  (testing "halt lines carry reason + world; done carries the satisfied world"
    (is (str/includes? (bridge/progress-line {:event :halt :iteration 2 :reason :integrity
                                              :world {:defects 4}})
                       "halt:integrity"))
    (is (str/includes? (bridge/progress-line {:event :done :world {:ok "pass"}})
                       "fulfilled")))
  (testing "malformed ts degrades, never throws"
    (is (str/includes? (bridge/progress-line {:event :act :iteration 3 :ts nil})
                       "??:??:??"))))

(deftest progress-text-test
  (testing "header carries the name + event count"
    (let [t (bridge/progress-text "demo" [{:event :done :world {:x 1}}])]
      (is (str/starts-with? t "🎯 goal `demo` — running · 1 events"))
      (is (str/includes? t "fulfilled"))))
  (testing "caps to the last 10 events with an explicit showing note"
    (let [evs (mapv #(hash-map :event :act :iteration % :ts "2026-09-06T10:00:00Z")
                    (range 14))
          t (bridge/progress-text "big" evs)]
      (is (str/includes? t "14 events")
          "header shows the full count")
      (is (str/includes? t "showing last 10"))
      (is (str/includes? t "it 13") "keeps the newest event")
      (is (not (str/includes? t "it 3")) "drops the oldest beyond the cap"))))

(deftest ledger-events-test
  (testing "reads logs/iter-*.edn in order; unparseable files are skipped"
    (let [ws (str *tmp* "/ledger-ws")]
      (fs/create-dirs (str ws "/logs"))
      (spit (str ws "/logs/iter-000.edn") "{:event :act :iteration 0}")
      (spit (str ws "/logs/iter-001.edn") "{:event :done :iteration 1 :world {}}")
      (spit (str ws "/logs/junk.edn") "not-edn{{{")
      (let [evs (bridge/ledger-events ws)]
        (is (= 2 (count evs)))
        (is (= :act (:event (first evs))))
        (is (= :done (:event (second evs))))))
    (is (nil? (bridge/ledger-events (str *tmp* "/no-such-ws"))))))

(deftest build-intent-test
  (testing "first-word production verbs route; everything else doesn't"
    (doseq [s ["Build a todo CLI" "build me a snake game" "CREATE x"
               "make a small lib" "Generate the report module" "build"]]
      (is (bridge/build-intent? s) (str "should route: " s)))
    (doseq [s ["make sure the tests pass" "make it faster"
               "what should I build today?" "fix the bug" "building stuff"
               "hi" "" nil]]
      (is (not (bridge/build-intent? s)) (str "should NOT route: " (pr-str s)))))
  (testing ":goal-auto-route false is the kill-switch"
    (with-redefs [config/load-config (constantly {:goal-auto-route false})]
      (is (not (bridge/build-intent? "build a thing"))))))

(deftest promote-config-test
  (with-redefs [config/load-config (constantly {:notify {:hmac-secret "s3cr3t"}})]
    (let [cfg (bridge/promote-config {:goal {:op := :ref :x :value 1}} "my-slug")]
      (is (= "my-slug" (:name cfg)) "bridge injects its own slug as :name")
      (is (= "http://127.0.0.1:7787/halt" (-> cfg :notify :url)) "runner-shaped notify")
      (is (= "s3cr3t" (-> cfg :notify :hmac-secret)) "secret rides from runtime config"))
    (let [cfg (bridge/promote-config {:name "llm-guess" :goal {}} "bridge-slug")]
      (is (= "bridge-slug" (:name cfg)) "bridge data wins over anything the LLM wrote"))))
