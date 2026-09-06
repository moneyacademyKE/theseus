(ns bb-agent.goal-bridge-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
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
      (is (.exists (io/file ws ".git"))))))

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
    (is (nil? (bridge/handle-request! "hello there" 1 2))))
  (testing "bare /goal → usage"
    (is (str/includes? (bridge/handle-request! "/goal" 1 2) "Usage:")))
  (testing "/goals is not hijacked by the /goal seam"
    (is (nil? (bridge/handle-request! "/goals" 1 2))))
  (testing "active run → refusal naming it"
    (let [pid (live-pid)]
      (spit bridge/active-file (pr-str {:pid pid :name "busy-goal"}))
      (is (str/includes? (bridge/handle-request! "/goal build x" 1 2) "busy-goal"))
      (p/shell {:continue true} "kill" (str pid))))
  (testing "blank spec → usage (a trimmed blank IS bare /goal)"
    (is (str/includes? (bridge/handle-request! "/goal    " 1 2) "Usage:"))
    (is (str/includes? (bridge/handle-request! (str "/goal " (apply str (repeat 500 "x"))) 1 2)
                       "characters")))
  (testing "authoring failure envelope: a turn that writes an invalid config
            surfaces the validator's verdict, never launches"
    (with-redefs [core/run-turn! (fn [_cfg _prompt]
                                   (let [d (str bridge/goals-root "/author-fail")]
                                     (fs/create-dirs d)
                                     (spit (str d "/config.edn")
                                           "{:name \"bad\" :workdir \".\"}")))]
      (let [reply (bridge/handle-request! "/goal build something nice" 7 9)]
        (is (str/includes? reply "🚫 Goal authoring failed"))
        (is (not (.exists (io/file bridge/active-file))))))))
