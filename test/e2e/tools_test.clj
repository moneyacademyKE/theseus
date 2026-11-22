(ns e2e.tools-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"THESEUS_HOME" (str home)}}
           "bb" "agent" prompt))

(deftest shell-tool-call-is-denied-by-default
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-e2e-"})
        side-effect-file (fs/path home "should-not-exist")
        denied-command (str "touch " side-effect-file)
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (let [result (run-agent home (str "try denied shell " denied-command))]
        (is (= 0 (:exit result)) (:err result))
        (is (or (str/includes? (:out result) "🚫 shell denied")
                (str/includes? (:out result) "🚫 shell needs your approval")))
        (is (not (str/includes? (:out result) "tool-results="))
            "the user-facing text is a clean sentence, never raw EDN")
        (is (not (fs/exists? side-effect-file)))
        (let [turn (first (edn/read-string (slurp (str session-file))))]
          (is (= [{:tool/name "shell"
                   :tool/args {:cmd denied-command}}]
                 (:tool/requests turn)))
          (is (= [{:tool/name "shell"
                   :status :denied
                   :executed? false
                   :approval/required? true
                   :denial/source :approval
                   :error/message "Tool shell requires explicit approval and no approver is present in this turn. Recourse: ask the user to approve, or report this step as skipped — do not silently retry."}]
                 (:tool/results turn))
              ":denial/source is how core/awaiting-human? tells an approval
               gate (a human is pending — end the turn) from a constitution
               veto (nobody to ask — recover)")))
      (finally
        (fs/delete-tree home)))))

(deftest approved-shell-tool-executes-and-feeds-result-back
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-approve-"})
        side-effect-file (fs/path home "did-exist")
        command (str "touch " side-effect-file)
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (let [result (run-agent home (str "try approved shell " command))]
        (is (= 0 (:exit result)) (:err result))
        (is (fs/exists? side-effect-file))
        (is (str/includes? (:out result) ":status :ok"))
        (let [turn (first (edn/read-string (slurp (str session-file))))
              tool-result (first (:tool/results turn))]
          (is (= "tool-results=" (subs (:assistant/final turn) 0 13)))
          (is (= "shell" (:tool/name tool-result)))
          (is (= :ok (:status tool-result)))
          (is (= 0 (:exit/code tool-result)))))
      (finally
        (fs/delete-tree home)))))

(deftest approved-read-write-search-and-git-tools-execute
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-files-"})
        nested-file (fs/path home "workspace" "nested" "note.txt")
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (let [write-result (run-agent home (str "try approved write_file " nested-file "|alpha beta gamma"))]
        (is (= 0 (:exit write-result)) (:err write-result))
        (is (= "alpha beta gamma" (slurp (str nested-file))))
        (is (str/includes? (:out write-result) ":tool/name \"write_file\"")))
      (let [read-result (run-agent home (str "try approved read_file " nested-file))]
        (is (= 0 (:exit read-result)) (:err read-result))
        (is (str/includes? (:out read-result) "alpha beta gamma")))
      (let [search-result (run-agent home (str "try approved search " home "|beta"))]
        (is (= 0 (:exit search-result)) (:err search-result))
        (is (str/includes? (:out search-result) ":tool/name \"search\""))
        (is (str/includes? (:out search-result) "alpha beta gamma")))
      (let [git-result (run-agent home (str "try approved git_status " home))]
        (is (= 0 (:exit git-result)) (:err git-result))
        (is (str/includes? (:out git-result) ":tool/name \"git_status\"")))
      (let [turns (edn/read-string (slurp (str session-file)))]
        (is (= 4 (count turns)))
        (is (= ["write_file" "read_file" "search" "git_status"]
               (mapv (comp :tool/name first :tool/results) turns))))
      (finally
        (fs/delete-tree home)))))

(deftest approved-file-tools-work-outside-home
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-boundary-"})
        outside (fs/create-temp-dir {:prefix "theseus-outside-"})
        outside-file (fs/path outside "escaped.txt")]
    (try
      (let [result (run-agent home (str "try approved write_file " outside-file "|nope"))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :ok"))
        (is (fs/exists? outside-file)))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest approved-shell-denies-destructive-command
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-shell-safe-"})
        victim (fs/path home "victim.txt")]
    (try
      (spit (str victim) "keep")
      (let [result (run-agent home (str "try approved shell rm " victim))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :error"))
        (is (str/includes? (:out result) "Denied unsafe shell command"))
        (is (= "keep" (slurp (str victim)))))
      (finally
        (fs/delete-tree home)))))

(deftest approved-shell-denies-bypass-command
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-shell-bypass-"})
        victim (fs/path home "victim.txt")]
    (try
      (spit (str victim) "keep")
      (let [result (run-agent home (str "try approved shell cd .; rm " victim))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :error"))
        (is (str/includes? (:out result) "Denied unsafe shell command"))
        (is (= "keep" (slurp (str victim)))))
      (finally
        (fs/delete-tree home)))))

(deftest approved-shell-enforces-timeout
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-timeout-"})]
    (try
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [result (tool/handle-tool-request {:tool/name "shell"
                                                :approval/policy :auto-all
                                                :tool/args {:cmd "sleep 2"
                                                            :timeout-ms 1}})]
          (is (= "shell" (:tool/name result)))
          (is (= :error (:status result)))
          (is (str/includes? (:error/message result) "Timed out"))))
      (finally
        (fs/delete-tree home)))))

(deftest file-and-document-tools-follow-symlinks
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-symlink-"})
        outside (fs/create-temp-dir {:prefix "theseus-secret-"})
        secret (fs/path outside "secret.txt")
        link (fs/path home "linked-secret.txt")]
    (try
      (spit (str secret) "secret")
      (fs/create-sym-link link secret)
      (let [read-result (run-agent home (str "try approved read_file " link))
            document-result (with-redefs [bb-agent.config/home (fn [] (str home))]
                              (tool/handle-tool-request {:tool/name "document_read"
                                                         :approval/policy :auto-all
                                                         :tool/args {:path (str link)}}))]
        (is (= 0 (:exit read-result)) (:err read-result))
        (is (str/includes? (:out read-result) ":status :ok"))
        (is (not (str/includes? (str (:error/message document-result)) "outside allowed root"))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest auto-safe-only-approves-read-only-tools
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-auto-safe-"})
        file (fs/path home "note.txt")]
    (try
      (spit (str file) "safe read")
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [read-result (tool/handle-tool-request {:tool/name "read_file"
                                                     :approval/policy :auto-safe
                                                     :tool/args {:path (str file)}})
              shell-result (tool/handle-tool-request {:tool/name "shell"
                                                      :approval/policy :auto-safe
                                                      :tool/args {:cmd "touch should-not-run"}})
              write-result (tool/handle-tool-request {:tool/name "write_file"
                                                      :approval/policy :auto-safe
                                                      :tool/args {:path (str (fs/path home "write.txt"))
                                                                  :content "nope"}})]
          (is (= :ok (:status read-result)))
          (is (= :denied (:status shell-result)))
          (is (= :denied (:status write-result)))
          (is (not (fs/exists? (fs/path home "write.txt"))))))
      (finally
        (fs/delete-tree home)))))

(deftest search-follows-symlinked-files
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-search-symlink-"})
        outside (fs/create-temp-dir {:prefix "theseus-search-secret-"})
        secret (fs/path outside "secret.txt")
        link (fs/path home "linked-secret.txt")]
    (try
      (spit (str secret) "secret needle")
      (fs/create-sym-link link secret)
      (let [result (run-agent home (str "try approved search " link "|needle"))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "secret needle")))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest shell-and-git-cwd-work-outside-home
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-cwd-"})
        outside (fs/create-temp-dir {:prefix "theseus-tools-outside-cwd-"})]
    (try
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [_ (p/shell {:dir (str outside)} "git" "init" "-q")
              shell-result (tool/handle-tool-request {:tool/name "shell"
                                                      :approval/policy :auto-all
                                                      :tool/args {:cmd "pwd"
                                                                  :cwd (str outside)}})
              git-result (tool/handle-tool-request {:tool/name "git_status"
                                                    :approval/policy :auto-all
                                                    :tool/args {:cwd (str outside)}})]
          (is (= :ok (:status shell-result)))
          (is (= :ok (:status git-result)))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest large-file-tools-check-size-before-reading
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-large-"})
        large (fs/path home "large.txt")]
    (try
      (spit (str large) (apply str (repeat 1000001 "x")))
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [read-result (tool/handle-tool-request {:tool/name "read_file"
                                                     :approval/policy :auto-safe
                                                     :tool/args {:path (str large)}})
              document-result (tool/handle-tool-request {:tool/name "document_read"
                                                         :approval/policy :auto-safe
                                                         :tool/args {:path (str large)}})]
          (is (= :error (:status read-result)))
          (is (str/includes? (:error/message read-result) "File too large"))
          (is (= :error (:status document-result)))
          (is (str/includes? (:error/message document-result) "File too large"))))
      (finally
        (fs/delete-tree home)))))

(deftest shell-hard-floor-releases-everyday-mutations
  ;; v5 grants chmod/mv; the handler's never-auto floor keeps only
  ;; rm/dd/sudo/chown for policy-silent homes. Live-fire 2026-09-06:
  ;; `chmod +x check.sh` was shadow-denied below the constitution.
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-shell-v5-"})]
    (try
      (spit (str (fs/path home "x.sh")) "#!/bin/sh\n")
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [chmod (tool/handle-tool-request
                     {:tool/name "shell" :approval/policy :auto-all
                      :tool/args {:cmd (str "chmod +x " (fs/path home "x.sh"))}} {})]
          (is (= :ok (:status chmod)) "chmod must run under :auto-all — v5 grants it"))
        (let [mv (tool/handle-tool-request
                  {:tool/name "shell" :approval/policy :auto-all
                   :tool/args {:cmd (str "mv " (fs/path home "x.sh") " " (fs/path home "y.sh"))}} {})]
          (is (= :ok (:status mv)) "mv must run under :auto-all — v5 grants it"))
        (let [rm (tool/handle-tool-request
                  {:tool/name "shell" :approval/policy :auto-all
                   :tool/args {:cmd (str "rm " (fs/path home "y.sh"))}} {})]
          (is (str/includes? (str (:error/message rm)) "Denied unsafe")
              "rm stays on the never-auto floor even under :auto-all")))
      (finally
        (fs/delete-tree home)))))

(deftest document-read-says-when-it-truncated
  ;; 2026-11-11: `document_read` returned 20000 chars of a 60000-char file with
  ;; no marker of any kind — a silent truncation that reads as the whole file.
  ;; Reproduced live before this test existed: a 60000-char /tmp file came back
  ;; `text chars: 20000`, `keys on doc: (:path :text)`, marker present: false.
  ;; Source: ruslanudintsev-ai/agent-self-learning-core's Output Budgeting,
  ;; https://github.com/ruslanudintsev-ai/agent-self-learning-core/blob/main/core/commands/base.py#L261
  ;; — `data[:max_str_len] + "\n... [TRUNCATED {excess} chars by Output Budget.
  ;; Use specific query/slice] ..."`. A cut is data the caller must see, not a
  ;; fact the tool keeps to itself.
  ;; Contract asserted here (decided before the implementation, so the numbers
  ;; are the spec and not a description of whatever shipped): the budget is the
  ;; ceiling on the WHOLE :text including the marker, :shown-chars counts source
  ;; characters kept, :total-chars counts source characters on disk.
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-trunc-"})
        budget bb-agent.tool.common/max-doc-chars
        big (fs/path home "big.txt")
        small (fs/path home "small.txt")]
    (try
      (spit (str big) (apply str (repeat (+ budget 40000) "A")))
      (spit (str small) "tiny")
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [trunc (tool/handle-tool-request {:tool/name "document_read"
                                               :approval/policy :auto-safe
                                               :tool/args {:path (str big)}})
              whole (tool/handle-tool-request {:tool/name "document_read"
                                               :approval/policy :auto-safe
                                               :tool/args {:path (str small)}})
              text (get-in trunc [:document :text])]
          (is (= :ok (:status trunc)))
          ;; the cut is named as data, with the denominator beside it
          (is (true? (get-in trunc [:document :truncated])))
          (is (= (+ budget 40000) (get-in trunc [:document :total-chars])))
          (is (pos? (get-in trunc [:document :shown-chars])))
          (is (<= (get-in trunc [:document :shown-chars]) budget))
          ;; the budget is a ceiling on the whole payload, marker included
          (is (= budget (count text)))
          (is (str/starts-with? text "AAAA"))
          ;; ...and the marker is inside the text the model actually reads
          (is (str/includes? text "TRUNCATED"))
          ;; no-regression: a file under the budget claims nothing was cut
          (is (false? (get-in whole [:document :truncated])))
          (is (= 4 (get-in whole [:document :total-chars])))
          (is (= "tiny" (get-in whole [:document :text])))
          (is (not (str/includes? (get-in whole [:document :text]) "TRUNCATED")))))
      (finally
        (fs/delete-tree home)))))

(deftest protected-secret-paths-are-refused-even-under-auto-safe
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-secrets-"})
        cfg-path (fs/path home "config.edn")
        pass-path (fs/path home ".web-pass.edn")
        sess-path (fs/path home "state" "web-sessions.edn")
        a2a-path (fs/path home "state" "a2a-token")
        link-path (fs/path home "alias.edn")
        normal-path (fs/path home "notes.txt")]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str cfg-path) "{:api-key \"top-secret-cfg\"}")
      (spit (str pass-path) "{:hash \"secret-hash\"}")
      (spit (str sess-path) "{\"tok\" {:role :owner}}")
      (spit (str a2a-path) "secret-a2a")
      (fs/create-sym-link link-path cfg-path)
      (spit (str normal-path) "public top-secret-cfg note")
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (doseq [target [cfg-path pass-path sess-path a2a-path link-path]]
          (let [r1 (tool/handle-tool-request {:tool/name "read_file"
                                              :approval/policy :auto-safe
                                              :tool/args {:path (str target)}})
                r2 (tool/handle-tool-request {:tool/name "document_read"
                                              :approval/policy :auto-safe
                                              :tool/args {:path (str target)}})
                r3 (tool/handle-tool-request {:tool/name "search"
                                              :approval/policy :auto-safe
                                              :tool/args {:path (str target)
                                                          :query "secret"}})
                r4 (tool/handle-tool-request {:tool/name "write_file"
                                              :approval/policy :auto-all
                                              :tool/args {:path (str target)
                                                          :content "overwritten"}})]
            (is (= :error (:status r1)) (str "read_file must refuse " target))
            (is (false? (:executed? r1)))
            (is (= :error (:status r2)) (str "document_read must refuse " target))
            (is (false? (:executed? r2)))
            (is (= :error (:status r3)) (str "search must refuse " target))
            (is (false? (:executed? r3)))
            (is (= :error (:status r4)) (str "write_file must refuse " target))
            (is (false? (:executed? r4)))))
        (testing "directory search skips protected files, oversized files, and binary files"
          (let [bin-path (fs/path home "blob.bin")
                big-path (fs/path home "huge.txt")]
            (fs/write-bytes bin-path (byte-array [65 0 66 116 111 112 45 115 101 99 114 101 116 45 99 102 103]))
            (spit (str big-path) (apply str (repeat (+ common/max-read-bytes 10) "a")))
            (let [sr (tool/handle-tool-request {:tool/name "search"
                                                :approval/policy :auto-safe
                                                :tool/args {:path (str home)
                                                            :query "top-secret-cfg"}})
                  matched-paths (set (map :path (:matches sr)))]
              (is (= :ok (:status sr)))
              (is (contains? matched-paths (str normal-path)))
              (is (not (contains? matched-paths (str cfg-path))))
              (is (not (contains? matched-paths (str link-path))))
              (is (not (contains? matched-paths (str bin-path))))))))
      (finally
        (fs/delete-tree home)))))

(deftest shell-floor-blocks-bypass-variants-and-browser-cli-fails-closed
  (let [home (fs/create-temp-dir {:prefix "theseus-tools-bypass2-"})]
    (try
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (doseq [cmd ["/bin/rm /tmp/x"
                     "\\rm /tmp/x"
                     "env rm /tmp/x"
                     "nohup rm /tmp/x"
                     "echo hi\nrm /tmp/x"
                     "echo $(rm /tmp/x)"]]
          (let [r (tool/handle-tool-request {:tool/name "shell"
                                             :approval/policy :auto-all
                                             :tool/args {:cmd cmd}})]
            (is (= :error (:status r)) (str "must deny " cmd))
            (is (false? (:executed? r)))))
        (let [br (tool/handle-tool-request {:tool/name "browser_cli"
                                            :approval/policy :auto-all
                                            :tool/args {:url "https://example.com"}})]
          (is (= :error (:status br)))
          (is (false? (:executed? br)))))
      (finally
        (fs/delete-tree home)))))

(deftest unknown-tool-reports-not-executed
  (let [res (tool/execute-tool-request {:tool/name "no_such_tool_xyz" :tool/args {}})]
    (is (= :error (:status res)))
    (is (false? (:executed? res)))
    (is (str/includes? (str (:error/message res)) "Unknown tool"))))
