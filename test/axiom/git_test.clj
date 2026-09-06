(ns axiom.git-test
  "Checkpoint/rollback regression test (Phase 1).

  The pure `decide` tests never touch the shell. This namespace is the
  exception: it spins up a real throwaway git repo to prove the Phase 1
  'Done when' criterion directly -- 'a rollback restores byte-identical
  state to last-good'. It creates and deletes its own temp dir; it never
  touches the demo workspace."
  (:require [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [axiom.core :as core]
            [axiom.git :as git]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]))

(def ^:private repo (atom nil))

(defn- shq
  "Quietly run git in `dir`; non-zero exits are surfaced via :continue."
  [dir & args]
  (apply sh {:out :string :err :string :dir dir :continue true} args))

(defn- fresh-repo
  "Create a temp git repo with one committed GOOD file; return its path."
  []
  (let [dir (str (fs/create-temp-dir))]
    (shq dir "git" "init" "-q")
    (shq dir "git" "config" "user.email" "axiom@test")
    (shq dir "git" "config" "user.name" "axiom-test")
    (spit (str dir "/marker") "GOOD\n")
    (shq dir "git" "add" "marker")
    (shq dir "git" "commit" "-q" "-m" "known-good baseline")
    dir))

(use-fixtures :once
  (fn [run-tests]
    (reset! repo (fresh-repo))
    (run-tests)
    (some-> @repo fs/delete-tree)))

(deftest tag-and-rollback-restores-byte-identical-state
  (testing "a corrupting working-tree change is undone by rollback! to last-good"
    (let [dir    @repo
          marker (str dir "/marker")]
      ;; snapshot the known-good commit as last-good
      (is (some? (git/tag! dir "last-good")) "tag! returns the tag name in a repo")
      ;; simulate a corrupting act on the working tree
      (spit marker "CORRUPT\n")
      (is (= "CORRUPT\n" (slurp marker)) "corruption took hold before rollback")
      ;; rollback restores byte-identical state
      (is (true? (:ok? (git/rollback! dir "last-good"))))
      (is (= "GOOD\n" (slurp marker)) "rollback restored the last-good bytes"))))

(deftest rollback-is-a-safe-noop-outside-a-git-repo
  (testing "rollback! returns nil (and mutates nothing) outside a git repo"
    (let [dir (str (fs/create-temp-dir))]
      (is (nil? (git/rollback! dir "last-good")))
      (fs/delete-tree dir))))

(deftest tag-is-honest-about-failure
  (testing "tag! throws when git rejects the tag (a silent failure would make
            rollback restore a STALE commit and rewind good progress)"
    ;; "a..b" is an invalid git tag name -- git tag -f must fail
    (is (thrown? Exception (git/tag! @repo "bad..name"))))
  (testing "tag! degrades to nil outside a repo (no crash, D3 no-op)"
    (let [dir (str (fs/create-temp-dir))]
      (is (nil? (git/tag! dir "last-good")))
      (fs/delete-tree dir))))

(deftest rollback-is-honest-tri-value
  (testing "rollback!: true-ok / false-failed-reset / nil-not-a-repo"
    (let [dir @repo]
      ;; self-sufficient: (re)create the tag -- test order must not matter
      (is (some? (git/tag! dir "last-good")))
      (is (true? (:ok? (git/rollback! dir "last-good"))))
      (is (false? (:ok? (git/rollback! dir "tag-never-created")))))
    (let [dir (str (fs/create-temp-dir))]
      (is (nil? (git/rollback! dir "last-good")))
      (fs/delete-tree dir))))

(defn- run-cfg
  "Minimal run! config over the fixture repo: goal unreachable, act no-op."
  [dir lock-suffix & kvs]
  (merge {:workdir dir
          :lock (str dir "/.axiom.lock." lock-suffix)
          :log-dir nil
          :observers {:x {:sh "echo 1" :parse :int}}
          :goal {:op := :ref :x :value "never"}
          :act {:sh "true"}}
         (apply hash-map kvs)))

(deftest run-halts-loudly-when-checkpoint-throws
  (testing "a failed checkpoint inside run! becomes a :error halt (bundle+page),
            never a silent crash"
    (let [res (core/run! (run-cfg @repo "err" :checkpoint {:tag-prefix "bad..tag"})
                         {:max-iters 3})]
      (is (= :halt (:status res)))
      (is (= :error (:reason res))))))

(deftest run-halts-when-rollback-fails
  (testing "a failed reset --hard halts :rollback-failed instead of iterating
            on a corrupt world"
    (with-redefs [git/rollback! (constantly {:ok? false :out "" :err "test: forced reset failure"})]
      (let [res (core/run! (run-cfg @repo "rb" :stall-after 1 :max-rollbacks 1)
                           {:max-iters 5})]
        (is (= :halt (:status res)))
        (is (= :rollback-failed (:reason res)))))))

(defn -main
  "Run the git/rollback tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (t/run-tests 'axiom.git-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))
