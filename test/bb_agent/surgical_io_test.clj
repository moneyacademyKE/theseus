(ns bb-agent.surgical-io-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.security :as sec]
            [bb-agent.tool :as tool]
            [bb-agent.turn-integrity :as ti]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-tmp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "theseus-surgical-io-"})]
    (try
      (with-redefs [config/home (constantly (str dir))]
        (f dir))
      (finally
        (fs/delete-tree dir)))))

(deftest read-file-supports-line-ranges
  (with-tmp-dir
    (fn [dir]
      (let [f (fs/path dir "sample.txt")]
        (spit (str f) "line1\nline2\nline3\nline4\nline5\n")
        (testing "omitted range reads full file (backward compatible)"
          (let [res (tool/execute-tool-request
                     {:tool/name "read_file" :tool/args {:path (str f)}})]
            (is (= :ok (:status res)))
            (is (= "line1\nline2\nline3\nline4\nline5\n" (:content res)))))
        (testing "1-based inclusive start_line and end_line slice cleanly"
          (let [res (tool/execute-tool-request
                     {:tool/name "read_file"
                      :tool/args {:path (str f) :start_line 2 :end_line 4}})]
            (is (= :ok (:status res)))
            (is (= "line2\nline3\nline4" (:content res)))
            (is (= 2 (:start-line res)))
            (is (= 4 (:end-line res)))
            (is (= 5 (:total-lines res)))
            (is (true? (:truncated? res)))))
        (testing "invalid range (start > end) returns error"
          (let [res (tool/execute-tool-request
                     {:tool/name "read_file"
                      :tool/args {:path (str f) :start_line 4 :end_line 2}})]
            (is (= :error (:status res)))))))))

(deftest edit-file-exact-fuzzy-and-line-range
  (with-tmp-dir
    (fn [dir]
      (let [f (fs/path dir "code.clj")]
        (spit (str f) "(defn greet [x]\n  (str \"hi \" x))\n\n(defn bye [x]\n  (str \"hi \" x))\n")
        (testing "ambiguous exact match refuses unless replace_all is true"
          (let [res (tool/execute-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str f)
                                  :old_text "(str \"hi \" x)"
                                  :new_text "(str \"hello \" x)"}})]
            (is (= :error (:status res)))
            (is (str/includes? (str (:error/message res)) "2"))))
        (testing "replace_all replaces all exact occurrences"
          (let [res (tool/execute-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str f)
                                  :old_text "(str \"hi \" x)"
                                  :new_text "(str \"hello \" x)"
                                  :replace_all true}})]
            (is (= :ok (:status res)))
            (is (= 2 (:replacements res)))
            (is (= :exact (:mode res)))
            (is (not (str/includes? (slurp (str f)) "\"hi \"")))))
        (testing "whitespace-trimmed line-block fallback handles indentation drift"
          (let [res (tool/execute-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str f)
                                  :old_text "(defn greet [x]\n    (str \"hello \" x))"
                                  :new_text "(defn greet [x]\n  (str \"yo \" x))"}})]
            (is (= :ok (:status res)))
            (is (= :fuzzy-lines (:mode res)))
            (is (str/includes? (slurp (str f)) "\"yo \""))))
        (testing "line-range replace replaces inclusive 1-based lines"
          (let [res (tool/execute-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str f)
                                  :start_line 4
                                  :end_line 5
                                  :new_text "(defn bye [x]\n  (str \"cya \" x))"}})]
            (is (= :ok (:status res)))
            (is (= :line-range (:mode res)))
            (is (str/includes? (slurp (str f)) "\"cya \""))))))))

(deftest edit-file-safety-and-classification
  (with-tmp-dir
    (fn [dir]
      (let [secret (fs/path dir ".web-pass.edn")]
        (spit (str secret) "{:hash \"abc\"}")
        (testing "refuses protected secret write paths"
          (let [res (tool/execute-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str secret) :old_text "abc" :new_text "xyz"}})]
            (is (= :error (:status res)))
            (is (false? (:executed? res)))))
        (testing "classified as mutating control tool and NOT auto-safe"
          (is (contains? sec/control-tools "edit_file"))
          (is (contains? ti/mutating-tools "edit_file"))
          (let [res (tool/handle-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path (str (fs/path dir "a.txt"))
                                  :old_text "a" :new_text "b"}}
                     {:approval/mode :auto-safe})]
            (is (= :denied (:status res)))))))))

(deftest search-supports-regex-and-glob
  (with-tmp-dir
    (fn [dir]
      (spit (str (fs/path dir "alpha.clj")) "(defn foo-bar [] 1)\n(defn baz [] 2)\n")
      (spit (str (fs/path dir "notes.md")) "(defn foo-bar [] 99)\n")
      (testing "glob filters target files"
        (let [res (tool/execute-tool-request
                   {:tool/name "search"
                    :tool/args {:path (str dir) :query "foo-bar" :glob "*.clj"}})]
          (is (= :ok (:status res)))
          (is (= 1 (count (:matches res))))
          (is (str/ends-with? (:path (first (:matches res))) "alpha.clj"))))
      (testing "regex mode matches regular expressions"
        (let [res (tool/execute-tool-request
                   {:tool/name "search"
                    :tool/args {:path (str dir) :query "defn\\s+foo-[a-z]+" :regex true :glob "*.clj"}})]
          (is (= :ok (:status res)))
          (is (= 1 (count (:matches res))))))
      (testing "invalid regex returns clean error"
        (let [res (tool/execute-tool-request
                   {:tool/name "search"
                    :tool/args {:path (str dir) :query "[unclosed" :regex true}})]
          (is (= :error (:status res))))))))
