(ns bb-agent.tool-name-heal-test
  (:require [bb-agent.tool :as tool]
            [bb-agent.tool-name-heal :as heal]
            [clojure.test :refer [deftest is testing]]))

(def ^:private sample-tools
  ["read_file" "write_file" "edit_file" "shell" "git_status"
   "telegram_send_file" "web_send_file" "repl_eval" "query_graph"
   "launch_goal" "discover_tools" "plan" "spawn_subagent"])

(deftest resolve-tool-name-stages-test
  (testing "exact match returns unchanged"
    (is (= "read_file" (heal/resolve-tool-name "read_file" sample-tools))))

  (testing "stage 1: normalized exact (casing, hyphens, punctuation)"
    (is (= "read_file" (heal/resolve-tool-name "read-file" sample-tools)))
    (is (= "git_status" (heal/resolve-tool-name "GitStatus" sample-tools)))
    (is (= "repl_eval" (heal/resolve-tool-name "REPL_EVAL" sample-tools))))

  (testing "stage 2: abbreviation-expanded token subset (>=2 tokens, unique best)"
    (is (= "telegram_send_file" (heal/resolve-tool-name "tg_send_file_now" sample-tools)))
    (is (= "telegram_send_file" (heal/resolve-tool-name "tgramSendFile" sample-tools)))
    (is (nil? (heal/resolve-tool-name "send_file" sample-tools))
        "ambiguous across telegram_send_file and web_send_file must not heal"))

  (testing "stage 3: small Levenshtein typo (unique match only)"
    (is (= "read_file" (heal/resolve-tool-name "read_flie" sample-tools)))
    (is (= "telegram_send_file" (heal/resolve-tool-name "telegram_sned_file" sample-tools)))
    (is (nil? (heal/resolve-tool-name "totally_unknown_tool" sample-tools)))
    (is (nil? (heal/resolve-tool-name "" sample-tools)))
    (is (nil? (heal/resolve-tool-name nil sample-tools)))))

(deftest execute-tool-request-heals-near-miss-test
  (testing "typo in registered tool name heals transparently and stamps :tool/healed-from"
    (let [res (tool/execute-tool-request {:tool/name "repl-eval"
                                          :tool/args {:code "(+ 20 22)"}})]
      (is (= :ok (:status res)))
      (is (= "repl_eval" (:tool/name res)))
      (is (= "repl-eval" (:tool/healed-from res)))))

  (testing "unknown tool with no unique match still returns :executed? false error"
    (let [res (tool/execute-tool-request {:tool/name "definitely_not_a_tool"
                                          :tool/args {}})]
      (is (= :error (:status res)))
      (is (false? (:executed? res))))))
