(ns bb-agent.opencrabs-polish-test
  (:require [babashka.fs :as fs]
            [bb-agent.compression :as compression]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.fallback :as fallback]
            [bb-agent.plan :as plan]
            [bb-agent.provider :as provider]
            [bb-agent.rsi :as rsi]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.turn-integrity :as ti]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest tool-name-and-param-alias-healing
  (testing "known aliases and levenshtein-1 typos resolve to canonical tool names"
    (is (= "shell" (common/resolve-tool-name "bash")))
    (is (= "search" (common/resolve-tool-name "grep")))
    (is (= "read_file" (common/resolve-tool-name "readFile")))
    (is (= "edit_file" (common/resolve-tool-name "editFile")))
    (is (= "read_file" (common/resolve-tool-name "read_fle")))
    (is (= "totally_unknown_xyz" (common/resolve-tool-name "totally_unknown_xyz"))))
  (testing "parameter aliases normalize before execution without clobbering canonical keys"
    (let [req (tool/normalize-request
               {:tool/name "editFile"
                :tool/args {:file_path "a.clj"
                            :old_string "foo"
                            :new_string "bar"
                            :replace_all "true"}})]
      (is (= "edit_file" (:tool/name req)))
      (is (= "a.clj" (get-in req [:tool/args :path])))
      (is (= "foo" (get-in req [:tool/args :old_text])))
      (is (= "bar" (get-in req [:tool/args :new_text])))
      (is (= true (get-in req [:tool/args :replace_all]))))
    (let [req (tool/normalize-request
               {:tool/name "bash"
                :tool/args {:command "echo hi"}})]
      (is (= "shell" (:tool/name req)))
      (is (= "echo hi" (get-in req [:tool/args :cmd]))))))

(deftest harness-injects-active-plan-state-into-turn-messages
  (let [dir (fs/create-temp-dir {:prefix "theseus-plan-ctx-"})
        captured (atom nil)]
    (try
      (with-redefs [config/home (constantly (str dir))
                    provider/complete (fn [_ req]
                                        (reset! captured (:messages req))
                                        {:role :assistant :content "on it"})]
        (plan/init-plan! "plan-ctx-s1" "Ship feature" ["Write test" "Implement"])
        (plan/start-step! "plan-ctx-s1" 1)
        (core/run-turn! {:provider :fake
                         :model "test-model"
                         :session/id "plan-ctx-s1"}
                        "continue")
        (let [sys-text (->> @captured
                            (filter #(= :system (:role %)))
                            (map :content)
                            (str/join "\n"))]
          (is (str/includes? sys-text "## PLAN STATE (harness)"))
          (is (str/includes? sys-text "Ship feature"))
          (is (str/includes? sys-text "[in-progress] 1. Write test"))))
      (finally
        (fs/delete-tree dir)))))

(deftest output-reserve-cap-and-token-report-calibration
  (testing "bounded-output-tokens caps max_tokens at 20% of model context window"
    (is (= 6400 (compression/bounded-output-tokens "default-32k" 32768)))
    (is (= 4096 (compression/bounded-output-tokens "claude-sonnet-4" 4096)))
    (is (= 256 (compression/bounded-output-tokens "default-32k" 50))))
  (testing "calibrate-token-report rejects bogus flat provider offsets and impossible drops"
    (is (= 1200 (compression/calibrate-token-report 1200 1000)))
    (is (= 1000 (compression/calibrate-token-report 25000 1000))
        "rejects +20k flat provider over-report")
    (is (= 1000 (compression/calibrate-token-report 5 1000))
        "rejects below-floor drop")))

(deftest sticky-fallback-promotion-and-model-leak-guard
  (testing "guard-model-leak clears incompatible claude-* model on openai-compatible fallback"
    (let [safe (fallback/guard-model-leak
                {:provider :openai-compatible :model "claude-sonnet-4"}
                :anthropic-compatible
                {:openai-compatible {:default-model "gpt-4o"}})]
      (is (= "gpt-4o" (:model safe)))))
  (testing "sticky promotion moves a frequent rescuer to the front of the chain"
    (let [state (atom {})
          now 1000000
          chain [{:provider :openai-compatible :model "primary"}
                 {:provider :anthropic-compatible :model "backup"}]]
      (dotimes [i 4]
        (fallback/record-rescue! state "sess-1" {:provider :anthropic-compatible :model "backup"} (+ now (* i 1000))))
      (let [ordered (fallback/reorder-with-sticky state "sess-1" chain (+ now 5000))]
        (is (= :anthropic-compatible (:provider (first ordered))))
        (is (= "backup" (:model (first ordered))))))))

(deftest rsi-aggregates-turn-signals-into-friction-opportunities
  (let [events (vec (concat
                     (for [i (range 6)]
                       {:usage/event :turn
                        :provider :openai-compatible
                        :ok true
                        :signals {:correction? (< i 4)
                                  :brevity (when (< i 4) :shorter)
                                  :repeated-prompt? false
                                  :approval-denials 0}})))
        {:keys [opportunities]} (rsi/analyze {:min-events 5 :events events})
        kinds (set (map :kind opportunities))]
    (is (contains? kinds :friction-signals))
    (is (some #(str/includes? (str (:suggestion %)) "correction") opportunities))))

(deftest bare-tool-call-extractor-and-unrun-fenced-shell
  (testing "extracts raw JSON tool call emitted as plain text"
    (let [req (ti/extract-bare-tool-call
               "```json\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"README.md\"}}\n```"
               #{"read_file" "shell"})]
      (is (= "read_file" (:tool/name req)))
      (is (= {:path "README.md"} (:tool/args req))))
    (is (nil? (ti/extract-bare-tool-call "Here is a normal answer." #{"read_file"}))))
  (testing "detects unrun fenced bash block when user asked to run a command"
    (is (true? (ti/unrun-fenced-shell?
                "run the unit tests now"
                "Sure, I ran them:\n```bash\nbb test\n```"
                [])))
    (is (false? (ti/unrun-fenced-shell?
                 "show me an example bash command"
                 "```bash\necho hi\n```"
                 [])))))
