(ns e2e.tools-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.tool :as tool]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"OPENCRABS_HOME" (str home)}}
           "bb" "agent" prompt))

(deftest shell-tool-call-is-denied-by-default
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-e2e-"})
        side-effect-file (fs/path home "should-not-exist")
        denied-command (str "touch " side-effect-file)
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (let [result (run-agent home (str "try denied shell " denied-command))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) "tool-results="))
        (is (str/includes? (:out result) ":status :denied"))
        (is (not (fs/exists? side-effect-file)))
        (let [turn (first (edn/read-string (slurp (str session-file))))]
          (is (= [{:tool/name "shell"
                   :tool/args {:cmd denied-command}}]
                 (:tool/requests turn)))
          (is (= [{:tool/name "shell"
                   :status :denied
                   :executed? false
                   :approval/required? true
                   :error/message "Tool shell requires explicit approval"}]
                 (:tool/results turn)))))
      (finally
        (fs/delete-tree home)))))

(deftest approved-shell-tool-executes-and-feeds-result-back
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-approve-"})
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
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-files-"})
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

(deftest approved-file-tools-cannot-escape-home
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-boundary-"})
        outside (fs/create-temp-dir {:prefix "opencrabs-bb-outside-"})
        outside-file (fs/path outside "escaped.txt")]
    (try
      (let [result (run-agent home (str "try approved write_file " outside-file "|nope"))]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :error"))
        (is (str/includes? (:out result) "outside allowed root"))
        (is (not (fs/exists? outside-file))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest approved-shell-denies-destructive-command
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-shell-safe-"})
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
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-shell-bypass-"})
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
  (let [result (tool/handle-tool-request {:tool/name "shell"
                                          :approval/policy :auto-all
                                          :tool/args {:cmd "sleep 2"
                                                      :timeout-ms 1}})]
    (is (= "shell" (:tool/name result)))
    (is (= :error (:status result)))
    (is (str/includes? (:error/message result) "Timed out"))))

(deftest file-and-document-tools-deny-symlink-escapes
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-symlink-"})
        outside (fs/create-temp-dir {:prefix "opencrabs-bb-secret-"})
        secret (fs/path outside "secret.txt")
        link (fs/path home "linked-secret.txt")]
    (try
      (spit (str secret) "secret")
      (fs/create-sym-link link secret)
      (let [read-result (run-agent home (str "try approved read_file " link))
            document-result (tool/handle-tool-request {:tool/name "document_read"
                                                       :approval/policy :auto-all
                                                       :tool/args {:path (str link)}})]
        (is (= 0 (:exit read-result)) (:err read-result))
        (is (str/includes? (:out read-result) ":status :error"))
        (is (str/includes? (:out read-result) "outside allowed root"))
        (is (= :error (:status document-result)))
        (is (str/includes? (:error/message document-result) "outside allowed root")))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest auto-safe-only-approves-read-only-tools
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-auto-safe-"})
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

(deftest search-denies-symlinked-files-outside-root
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-search-symlink-"})
        outside (fs/create-temp-dir {:prefix "opencrabs-bb-search-secret-"})
        secret (fs/path outside "secret.txt")
        link (fs/path home "linked-secret.txt")]
    (try
      (spit (str secret) "secret needle")
      (fs/create-sym-link link secret)
      (let [result (run-agent home (str "try approved search " home "|needle"))]
        (is (= 0 (:exit result)) (:err result))
        (is (not (str/includes? (:out result) "secret needle"))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest shell-and-git-cwd-must-stay-under-home
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-cwd-"})
        outside (fs/create-temp-dir {:prefix "opencrabs-bb-tools-outside-cwd-"})]
    (try
      (with-redefs [bb-agent.config/home (fn [] (str home))]
        (let [shell-result (tool/handle-tool-request {:tool/name "shell"
                                                      :approval/policy :auto-all
                                                      :tool/args {:cmd "pwd"
                                                                  :cwd (str outside)}})
              git-result (tool/handle-tool-request {:tool/name "git_status"
                                                    :approval/policy :auto-all
                                                    :tool/args {:cwd (str outside)}})]
          (is (= :error (:status shell-result)))
          (is (str/includes? (:error/message shell-result) "outside allowed root"))
          (is (= :error (:status git-result)))
          (is (str/includes? (:error/message git-result) "outside allowed root"))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree outside)))))

(deftest large-file-tools-check-size-before-reading
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-tools-large-"})
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
