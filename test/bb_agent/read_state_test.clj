(ns bb-agent.read-state-test
  (:require [babashka.fs :as fs]
            [bb-agent.tool :as tool]
            [bb-agent.tool.file :as file]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest session-fully-read-guard-test
  (let [dir (fs/create-temp-dir {:prefix "theseus-read-state-"})
        existing (str (fs/path dir "existing.txt"))
        fresh (str (fs/path dir "fresh.txt"))
        sid (str "sess-" (random-uuid))
        cfg {:session/id sid :approval/mode :auto-all}]
    (try
      (spit existing "line1\nline2\nline3\nline4\n")

      (testing "creating a brand-new file is always allowed without prior read"
        (let [res (tool/handle-tool-request
                   {:tool/name "write_file"
                    :tool/args {:path fresh :content "hello"}}
                   cfg)]
          (is (= :ok (:status res)))))

      (testing "overwriting an existing file after ONLY a windowed read is refused"
        (let [r (tool/handle-tool-request
                 {:tool/name "read_file"
                  :tool/args {:path existing :start_line 1 :end_line 2}}
                 cfg)]
          (is (= :ok (:status r)))
          (is (true? (:truncated? r))))
        (let [w (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path existing :content "clobbered"}}
                 cfg)]
          (is (= :error (:status w)))
          (is (false? (:executed? w)))
          (is (str/includes? (:error/message w) "full file"))))

      (testing "append? true on an existing file is allowed even without a full read"
        (let [w (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path existing :content "line5\n" :append? true}}
                 cfg)]
          (is (= :ok (:status w)))))

      (testing "full read_file unlocks overwrite in the same session"
        (let [r (tool/handle-tool-request
                 {:tool/name "read_file"
                  :tool/args {:path existing}}
                 cfg)]
          (is (= :ok (:status r))))
        (is (true? (file/was-fully-read? sid existing)))
        (let [w (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path existing :content "clean overwrite\n"}}
                 cfg)]
          (is (= :ok (:status w)))
          (is (= "clean overwrite\n" (slurp existing)))))

      (finally
        (fs/delete-tree dir)))))
