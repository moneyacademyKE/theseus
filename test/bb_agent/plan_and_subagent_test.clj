(ns bb-agent.plan-and-subagent-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.plan :as plan]
            [bb-agent.security :as sec]
            [bb-agent.state-hygiene :as hygiene]
            [bb-agent.tool :as tool]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn- with-tmp-home [f]
  (let [dir (fs/create-temp-dir {:prefix "theseus-wave3-"})]
    (try
      (with-redefs [config/home (constantly (str dir))]
        (f dir))
      (finally
        (fs/delete-tree dir)))))

(deftest plan-lifecycle-verification-and-mechanical-gate
  (with-tmp-home
    (fn [dir]
      (let [sid "plan-sess-1"
            target (fs/path dir "work.txt")
            base-cfg {:session/id sid
                      :cwd (str dir)
                      :approval/mode :auto-all
                      :plan/gate-after-tool-calls 3
                      :turn/tool-calls 3}]
        (spit (str target) "hello\n")
        (testing "mechanical plan gate blocks mutating edit once threshold reached without plan"
          (let [r (tool/handle-tool-request
                   {:tool/name "edit_file"
                    :tool/args {:path (str target) :old_text "hello" :new_text "world"}}
                   base-cfg)]
            (is (= :denied (:status r)))
            (is (= :plan-gate (:denial/source r)))
            (is (= "hello\n" (slurp (str target))))))
        (testing "read_file is never blocked by plan gate"
          (let [r (tool/handle-tool-request
                   {:tool/name "read_file" :tool/args {:path (str target)}}
                   base-cfg)]
            (is (= :ok (:status r)))))
        (testing "plan init unlocks mutating tools and persists to state/plans"
          (let [init-res (tool/handle-tool-request
                          {:tool/name "plan"
                           :tool/args {:action "init"
                                       :title "Fix greeting"
                                       :steps ["Update work.txt" "Verify output"]}}
                          base-cfg)]
            (is (= :ok (:status init-res)))
            (is (true? (plan/has-active-plan? sid)))
            (is (empty? (hygiene/violations (fs/path dir "state"))))))
        (testing "mutating edit now succeeds with active plan"
          (let [r (tool/handle-tool-request
                   {:tool/name "edit_file"
                    :tool/args {:path (str target) :old_text "hello" :new_text "world"}}
                   base-cfg)]
            (is (= :ok (:status r)))
            (is (= "world\n" (slurp (str target))))))
        (testing "plan complete with failing verify_cmd refuses to mark step done"
          (let [r (tool/handle-tool-request
                   {:tool/name "plan"
                    :tool/args {:action "complete" :step 1 :verify_cmd "exit 1"}}
                   base-cfg)]
            (is (= :error (:status r)))
            (is (= :pending (:status (first (:steps (plan/load-plan sid))))))))
        (testing "plan complete with passing verify_cmd marks step done"
          (let [r (tool/handle-tool-request
                   {:tool/name "plan"
                    :tool/args {:action "complete" :step 1 :verify_cmd "grep -q world work.txt"}}
                   base-cfg)]
            (is (= :ok (:status r)))
            (is (= :done (:status (first (:steps (plan/load-plan sid))))))))))))

(deftest spawn-subagent-executes-inherits-taint-and-isolates-worktree
  (with-tmp-home
    (fn [dir]
      (let [repo (fs/path dir "repo")]
        (fs/create-dirs repo)
        (p/shell {:dir (str repo)} "git" "init" "-q")
        (p/shell {:dir (str repo)} "git" "config" "user.email" "test@example.com")
        (p/shell {:dir (str repo)} "git" "config" "user.name" "Test")
        (spit (str (fs/path repo "file.txt")) "base\n")
        (p/shell {:dir (str repo)} "git" "add" "file.txt")
        (p/shell {:dir (str repo)} "git" "commit" "-q" "-m" "init")
        (let [captured (atom nil)
              parent-exp (assoc sec/empty-exposure :external? true :sources #{"web_search"})
              fake-runner (fn [child-cfg child-prompt]
                            (reset! captured {:cfg child-cfg :prompt child-prompt})
                            {:assistant/final (str "ran in " (:cwd child-cfg))
                             :turn/ok true})
              res (tool/handle-tool-request
                   {:tool/name "spawn_subagent"
                    :tool/args {:prompt "inspect repo" :worktree true}}
                   {:cwd (str repo)
                    :approval/mode :auto-all
                    :approval/ask (constantly :approved)
                    :security/exposure parent-exp
                    :subagent/runner fake-runner})]
          (is (= :ok (:status res)))
          (is (true? (:worktree? res)))
          (is (not= (str repo) (get-in @captured [:cfg :cwd]))
              "worktree mode runs child in isolated worktree dir, not main repo")
          (is (true? (get-in @captured [:cfg :security/exposure :external?]))
              "child inherits parent taint exposure")
          (is (false? (fs/exists? (get-in @captured [:cfg :cwd])))
              "temporary worktree is cleaned up after completion")
          (let [ledger (edn/read-string (slurp (str (fs/path dir "state" "subagents.edn"))))]
            (is (= 1 (count ledger)))
            (is (= :completed (:subagent/status (first ledger))))
            (is (true? (get-in (first ledger) [:subagent/exposure :external?])))))))))

(deftest spawn-subagent-refuses-auto-safe-without-approval
  (with-tmp-home
    (fn [_dir]
      (let [res (tool/handle-tool-request
                 {:tool/name "spawn_subagent"
                  :tool/args {:prompt "do work"}}
                 {:approval/mode :auto-safe})]
        (is (= :denied (:status res)))))))
