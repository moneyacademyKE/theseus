(ns bb-agent.goal-bridge-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.goal-bridge :as bridge]
            [bb-agent.goal.progress :as progress]
            [bb-agent.goal.registry :as registry]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn with-tmp-goals [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "goal-bridge-test"}))]
    (binding [*tmp* tmp]
      (with-redefs [registry/goals-root (constantly tmp)
                    registry/active-file (constantly (str tmp "/active.edn"))]
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

(deftest registry-test
  (testing "no registry file → empty"
    (is (= {} (registry/read-runs)))
    (is (nil? (registry/active-run-for 7 9))))
  (testing "live pid → the topic's run (legacy single-run shape re-keyed)"
    (let [pid (live-pid)]
      (spit (registry/active-file) (pr-str {:pid pid :name "live" :chat-id 7 :thread-id 9}))
      (is (= "live" (:name (registry/active-run-for 7 9))))
      (is (nil? (registry/active-run-for 7 10)) "a different topic sees nothing")
      (p/shell {:continue true} "kill" (str pid))))
  (testing "dead pid → nil and the entry is cleared"
    (spit (registry/active-file)
          (pr-str {[7 9] {:pid 99999999 :name "stale" :chat-id 7 :thread-id 9}}))
    (is (nil? (registry/active-run-for 7 9)))
    (is (not (.exists (io/file (registry/active-file)))))))

(deftest parallel-topics-test
  "The registry is keyed by [chat-id thread-id] so topics run goals in
   parallel (owner directive 2026-09-08: linkcheck + mdtoc simultaneously,
   no context pollution, one goal per topic)."
  (testing "two live topics coexist in the registry"
    (let [pid-a (live-pid)
          pid-b (live-pid)]
      (registry/register-run! 1 196 {:pid pid-a :name "goal-a" :chat-id 1 :thread-id 196})
      (registry/register-run! 1 200 {:pid pid-b :name "goal-b" :chat-id 1 :thread-id 200})
      (is (= "goal-a" (:name (registry/active-run-for 1 196))))
      (is (= "goal-b" (:name (registry/active-run-for 1 200))))
      (testing "a finishing run unregisters by name, leaving the other topic alone"
        (registry/unregister-run! "goal-a")
        (is (nil? (registry/active-run-for 1 196)))
        (is (= "goal-b" (:name (registry/active-run-for 1 200)))))
      (p/shell {:continue true} "kill" (str pid-b))
      (is (nil? (registry/active-run-for 1 200)) "dead pid self-clears")
      (is (not (.exists (io/file (registry/active-file))))
          "last entry cleared → registry file removed")))
  (testing "dead entries stay visible to the pure read — recovery owns them"
    (spit (registry/active-file)
          (pr-str {[1 5] {:pid 99999999 :name "dead" :chat-id 1 :thread-id 5}}))
    (is (= 1 (count (registry/read-runs))))
    (registry/active-run-for 1 5)
    (is (not (.exists (io/file (registry/active-file)))))))

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
    (is (string? (bridge/validate! (str *tmp* "/nope/config.edn")))))
  (testing ":skills-used metadata passes schema validation (bk-d8a0)"
    (let [ws (str *tmp* "/skills-used")]
      (write-cfg ws (pr-str {:name "skills-used" :workdir ws :lock "./l.lock"
                             :log-dir "./logs"
                             :observers {:ok {:sh "echo pass" :parse :string}}
                             :goal {:op := :ref :ok :value "pass"}
                             :progress :ok
                             :act {:sh "./act.sh {{attempt}}"}
                             :stall-after 3 :max-rollbacks 2
                             :skills-used ["verification-witness"]}))
      (is (nil? (bridge/validate! (str ws "/config.edn")))))))

(deftest author-prompt-carries-skill-contract
  (testing "author prompt pins read-before-claim for skills (bk-d8a0)"
    (is (str/includes? @#'bridge/author-prompt "SKILL CONTRACT"))
    (is (str/includes? @#'bridge/author-prompt ":skills-used")))
  (testing "author prompt carries the deliverables contract (bk-7496)"
    (is (str/includes? @#'bridge/author-prompt ":deliverables"))
    (is (str/includes? @#'bridge/author-prompt "45 MB"))))

(deftest run-status-test
  (testing "missing log → :authored"
    (is (= :authored (progress/run-status "never-was"))))
  (let [ws (str *tmp* "/st")]
    (fs/create-dirs ws)
    (spit (str ws "/run.log") "act 1 ok\nGOAL FULFILLED {:x 0}")
    (is (= :fulfilled (progress/run-status "st")))
    (spit (str ws "/run.log") "HALT: integrity {:check :format}")
    (is (= :halted (progress/run-status "st")))
    (spit (str ws "/run.log") "act 1 ok")
    (is (= :running (progress/run-status "st")))))

(deftest list-goals-test
  (testing "formats one line per workspace with status"
    (fs/create-dirs (str *tmp* "/alpha"))
    (spit (str *tmp* "/alpha/run.log") "GOAL FULFILLED {}")
    (fs/create-dirs (str *tmp* "/beta"))
    (let [lines (progress/list-goals)]
      (is (some #(str/includes? % "alpha — fulfilled") lines))
      (is (some #(str/includes? % "beta — authored") lines)))))

(deftest handle-request-test
  (testing "non-goal text → nil"
    (is (nil? (bridge/handle-request! "hello there" 1 2))))
  (testing "bare /goal → usage"
    (is (str/includes? (bridge/handle-request! "/goal" 1 2) "Usage:")))
  (testing "/goals is not hijacked by the /goal seam"
    (is (nil? (bridge/handle-request! "/goals" 1 2))))
  (testing "busy topic → queued behind the running goal; another topic is free"
    (let [pid (live-pid)]
      (registry/register-run! 1 2 {:pid pid :name "busy-goal" :chat-id 1 :thread-id 2})
      (with-redefs [bridge/scaffold! (fn [name] (str *tmp* "/" name))
                    bridge/spawn-detached! (fn [_ _] "0")
                    config/home (constantly *tmp*)]
        (let [reply (bridge/handle-request! "/goal build x" 1 2)]
          (is (str/includes? reply "queued"))
          (is (str/includes? reply "busy-goal")
              "the queue reply names what it waits behind"))
        (is (nil? (registry/active-run-for 3 4)) "a different topic is free to launch"))
      (p/shell {:continue true} "kill" (str pid))
      (registry/unregister-run! "busy-goal"))) ; explicit cleanup: later tests in this deftest assert an empty registry
  (testing "blank spec → usage (a trimmed blank IS bare /goal)"
    (is (str/includes? (bridge/handle-request! "/goal    " 1 2) "Usage:"))
    (is (str/includes? (bridge/handle-request! (str "/goal " (apply str (repeat 500 "x"))) 1 2)
                       "characters")))
  (testing "authoring failure envelope: a turn that writes an invalid config
            surfaces the validator's verdict, never launches (V1a: this is
            launch-scaffolded!'s contract — the detached script calls it and
            queues the reply; dispatch itself just acks)"
    (with-redefs [core/run-turn! (fn [_cfg _prompt]
                                   (let [d (str (registry/goals-root) "/author-fail")]
                                     (fs/create-dirs d)
                                     (spit (str d "/project.edn")
                                           "{:name \"bad\" :workdir \".\"}")))]
      (let [ws (str (registry/goals-root) "/author-fail")
            reply (bridge/launch-scaffolded! "author-fail" ws "build something nice" 7 9 nil)]
        (is (str/includes? reply "🚫 Goal authoring failed"))
        (is (not (.exists (io/file (registry/active-file)))))))))

(deftest progress-line-test
  (testing "act lines show progress movement and no-progress flag"
    (is (= "▸ it 1 act 6→4 · 14:47:00"
           (#'progress/progress-line {:event :act :iteration 1 :progress-before 6
                                  :progress-after 4 :progressed? true
                                  :ts "2026-09-06T14:47:00.814992Z"})))
    (is (str/includes? (#'progress/progress-line {:event :act :iteration 0 :progress-before 6
                                              :progress-after 6 :progressed? false
                                              :ts "2026-09-06T14:47:00Z"})
                       "(no progress)")))
  (testing "halt lines carry reason + world; done carries the satisfied world"
    (is (str/includes? (#'progress/progress-line {:event :halt :iteration 2 :reason :integrity
                                              :world {:defects 4}})
                       "halt:integrity"))
    (is (str/includes? (#'progress/progress-line {:event :done :world {:ok "pass"}})
                       "fulfilled")))
  (testing "malformed ts degrades, never throws"
    (is (str/includes? (#'progress/progress-line {:event :act :iteration 3 :ts nil})
                       "??:??:??"))))

(deftest progress-text-test
  (testing "header carries the name + event count"
    (let [t (progress/progress-text "demo" [{:event :done :world {:x 1}}])]
      (is (str/starts-with? t "🎯 goal `demo` — running · 1 events"))
      (is (str/includes? t "fulfilled"))))
  (testing "caps to the last 10 events with an explicit showing note"
    (let [evs (mapv #(hash-map :event :act :iteration % :ts "2026-09-06T10:00:00Z")
                    (range 14))
          t (progress/progress-text "big" evs)]
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
      (let [evs (progress/ledger-events ws)]
        (is (= 2 (count evs)))
        (is (= :act (:event (first evs))))
        (is (= :done (:event (second evs))))))
    (is (nil? (progress/ledger-events (str *tmp* "/no-such-ws"))))))

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

(deftest author-model-override-test
  (testing ":goal/author-model overrides the model for authoring only; absent key keeps the turn model (V1b)"
    (with-redefs [config/load-config (constantly {:provider :fake :model "main-model"
                                                  :goal/author-model "fast-model"})]
      (is (= "fast-model" (:model (#'bridge/author-cfg "x" "/tmp/ws")))
          "authoring rides the override model")
    (with-redefs [config/load-config (constantly {:provider :fake :model "main-model"})]
      (is (= "main-model" (:model (#'bridge/author-cfg "x" "/tmp/ws")))
          "absent override = authoring rides the configured model")))))

(deftest authoring-instrumentation-test
  (testing "every authoring run persists <ws>/authoring.edn with model, rounds, duration (V1c)"
    (with-redefs [config/load-config (constantly {:provider :fake :model "main-model"
                                                  :goal/authoring-idle-timeout-ms 5000
                                                  :goal/authoring-timeout-ms 30000})
                  core/run-turn! (fn [cfg _prompt]
                                   (when-let [emit (:status/emit cfg)]
                                     (emit {:status :tool/call :tool "read_file"})
                                     (emit {:status :tool/done :tool "read_file"})))]
      (let [ws (str *tmp* "/ws-instr")
            _ (fs/create-dirs ws)
            result (#'bridge/author-with-validation! "instr-goal" ws "spec" nil)]
        (is (string? result) "fake provider writes no config — validator verdict")
        (let [rec (edn/read-string (slurp (str ws "/authoring.edn")))]
          (is (= "main-model" (:model rec)))
          (is (= 2 (:rounds rec)) "initial turn + repair turn, one tool call each")
          (is (number? (:duration-ms rec)))
          (is (= :authored (:result rec)))
          (is (string? (:finished rec))))))))

(deftest goal-cancel-and-status-test
  (testing "V3 control verbs: cancel stops the topic's runner with an honest
            HALT verdict; status reads the runner's own words; both route
            through handle-request! without ever scaffolding"
    (testing "cancel with no run in topic"
      (is (str/includes? (bridge/cancel! 55 66) "No goal is running")))
    (testing "cancel with a live runner: verdict appended, pid killed, unregistered"
      (let [pid (live-pid)
            ws (str *tmp* "/cancel-me")]
        (fs/create-dirs ws)
        (spit (str ws "/run.log") "iteration 1 ok\n")
        (registry/register-run! 55 66 {:pid pid :name "cancel-me" :chat-id 55 :thread-id 66})
        (let [reply (bridge/handle-request! "/goal cancel" 55 66)]
          (is (str/includes? reply "cancel-me"))
          (is (str/includes? reply "⛔"))
          (is (str/includes? (slurp (str ws "/run.log"))
                             "HALT: cancelled by operator")
              "the watcher will announce ⛔ with attribution, not 'gave up'")
          (is (nil? (registry/active-run-for 55 66)) "unregistered")
          (p/shell {:continue true} "kill" (str pid)))))
    (testing "a dead-pid registry entry is pruned on read — cancel sees nothing"
      (registry/register-run! 55 66 {:pid 4000000000 :name "ghost" :chat-id 55 :thread-id 66})
      (is (str/includes? (bridge/cancel! 55 66) "No goal is running"))
      (is (nil? (registry/active-run-for 55 66)) "the read pruned the ghost")))
  (testing "/goal status <name> reports the runner's verdict"
    (let [ws (str *tmp* "/status-goal")]
      (fs/create-dirs ws)
      (spit (str ws "/run.log") "GOAL FULFILLED\n")
      (is (str/includes? (bridge/status-of "status-goal") "fulfilled"))
      (is (str/includes? (bridge/status-of "never-was") "No goal workspace"))))
  (testing "status of this topic's active goal via bare /goal status"
    (let [pid (live-pid)
          ws (str *tmp* "/active-status")]
      (fs/create-dirs ws)
      (spit (str ws "/run.log") "iteration 2 ok\n")
      (registry/register-run! 71 72 {:pid pid :name "active-status" :chat-id 71 :thread-id 72})
      (is (str/includes? (bridge/handle-request! "/goal status" 71 72) "running"))
      (p/shell {:continue true} "kill" (str pid))
      (registry/unregister-run! "active-status")))
  (testing "/goal cancel never reaches the spec dispatcher"
    (let [spawned (atom false)]
      (with-redefs [bridge/scaffold! (fn [_] (reset! spawned true) (str *tmp* "/x"))]
        (bridge/handle-request! "/goal cancel" 99 98)
        (is (false? @spawned) "control verbs are not build specs")))))

(deftest authoring-timeout-test
  (testing "a wedged authoring turn returns a stall string instead of parking forever"
    (with-redefs [config/load-config (constantly {:goal/authoring-timeout-ms 100})
                  core/run-turn! (fn [& _] (Thread/sleep 5000))]
      (let [ws (str *tmp* "/ws-stall")
            _ (fs/create-dirs ws)
            started (System/currentTimeMillis)
            result (#'bridge/author-with-validation! "stall-goal" ws "spec" nil)]
        (is (string? result))
        (is (str/includes? result "authoring stalled"))
        (is (< (- (System/currentTimeMillis) started) 4000)))))
  (testing "a non-timeout result flows through unchanged (validation failure string)"
    (with-redefs [config/load-config (constantly {:goal/authoring-timeout-ms 30000})
                  core/run-turn! (fn [& _] nil)]
      (let [ws (str *tmp* "/ws-invalid")
            _ (fs/create-dirs ws)
            result (#'bridge/author-with-validation! "invalid-goal" ws "spec" nil)]
        (is (string? result))
        (is (not (str/includes? result "authoring stalled")))))))

(def ^:private stalled :bb-agent.goal-bridge/stalled)
(def ^:private timed-out :bb-agent.goal-bridge/timed-out)

(deftest watch-authoring-test
  (testing "no progress at all: the idle clock fires, not the ceiling"
    (let [fut (future (Thread/sleep 30000))
          started (System/currentTimeMillis)
          r (#'bridge/watch-authoring! fut (atom (System/nanoTime))
                                     {:idle-ms 200 :ceiling-ms 30000 :poll-ms 25})]
      (is (= stalled r))
      (is (< (- (System/currentTimeMillis) started) 5000)
          "idle (200ms) fired, ceiling (30s) never got near"))))
  (testing "steady progress survives past where the old total budget would die"
    (let [prog (atom (System/nanoTime))
          bumper (future (loop [i 0]
                           (if (< i 12)
                             (do (reset! prog (System/nanoTime))
                                 (Thread/sleep 50)
                                 (recur (inc i)))
                             :done)))
          fut (future @bumper)]
      (is (= :done (#'bridge/watch-authoring! fut prog
                                              {:idle-ms 200 :ceiling-ms 30000 :poll-ms 25}))
          "12 emits over 600ms keep resetting the idle clock")))
  (testing "ceiling still wins while progress continues (hard backstop)"
    (let [prog (atom (System/nanoTime))
          bumper (future (while true
                           (reset! prog (System/nanoTime))
                           (Thread/sleep 50)))
          fut (future (Thread/sleep 30000))
          started (System/currentTimeMillis)
          r (#'bridge/watch-authoring! fut prog {:idle-ms 2000 :ceiling-ms 1000 :poll-ms 25})]
      (future-cancel bumper)
      (is (= timed-out r))
      (is (< (- (System/currentTimeMillis) started) 3000))))
  (testing "a realized future returns its value untouched"
    (is (= :ok (#'bridge/watch-authoring! (future :ok) (atom (System/nanoTime))
                                          {:idle-ms 1000 :ceiling-ms 5000 :poll-ms 25}))))

(deftest authoring-progress-emit-test
  (testing "emit activity bumps the idle clock AND forwards to the requester"
    (let [received (atom [])
          ws (str *tmp* "/ws-progress")
          _ (fs/create-dirs ws)]
      (with-redefs [config/load-config (constantly {:goal/authoring-idle-timeout-ms 300
                                                    :goal/authoring-timeout-ms 60000})
                    core/run-turn! (fn [cfg _]
                                     (let [emit (:status/emit cfg)]
                                       (dotimes [_ 5]
                                         (emit {:status :tool/call :tool "read_file"})
                                         (Thread/sleep 80))
                                       nil))]
        (let [result (#'bridge/author-with-validation! "progress-goal" ws "spec" #(swap! received conj %))]
          (is (not (str/includes? (str result) "authoring stalled"))
              "5 emits over 400ms with a 300ms idle budget: progress kept it alive")
          (is (pos? (count @received))
              "the requester's emit channel still receives forwarded events"))))))
