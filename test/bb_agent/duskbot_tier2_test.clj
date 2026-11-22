(ns bb-agent.duskbot-tier2-test
  (:require [babashka.fs :as fs]
            [bb-agent.action-receipts :as receipts]
            [bb-agent.config :as config]
            [bb-agent.deliverable-check :as dc]
            [bb-agent.schedule-probe :as probe]
            [bb-agent.shell-lexer :as slex]
            [bb-agent.state-hygiene :as hygiene]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest shell-lexer-distinguishes-data-from-commands-and-fails-closed
  (testing "quoted strings and heredocs do NOT false-positive as catastrophic commands"
    (is (false? (:catastrophic? (slex/catastrophic-command? "git commit -m 'fix rm -rf / bug'"))))
    (is (false? (:catastrophic? (slex/catastrophic-command? "echo \"dd if=/dev/zero of=/dev/disk0\""))))
    (is (false? (:catastrophic? (slex/catastrophic-command? "cat <<'EOF'\nrm -rf /\nEOF\n")))))
  (testing "real catastrophic commands are caught"
    (is (true? (:catastrophic? (slex/catastrophic-command? "rm -rf /"))))
    (is (true? (:catastrophic? (slex/catastrophic-command? "echo hi && rm -rf ~"))))
    (is (true? (:catastrophic? (slex/catastrophic-command? "dd if=/dev/zero of=/dev/disk1"))))
    (is (true? (:catastrophic? (slex/catastrophic-command? ":(){ :|:& };:")))))
  (testing "unclosed quotes fail closed (:synced? false)"
    (let [res (slex/catastrophic-command? "echo 'unterminated")]
      (is (false? (:synced? res)))
      (is (true? (:catastrophic? res))))))

(deftest schedule-probe-sandbox-and-pacing-gate
  (testing "seatbelt profile denies secret paths and strips env secrets"
    (let [sbpl (probe/seatbelt-profile "/tmp/scratch-1")
          env (probe/sanitized-env {"PATH" "/usr/bin" "HOME" "/Users/moe" "OPENAI_API_KEY" "sk-secret"})]
      (is (str/includes? sbpl ".ssh"))
      (is (str/includes? sbpl ".aws"))
      (is (= "/usr/bin" (get env "PATH")))
      (is (nil? (get env "OPENAI_API_KEY")))))
  (testing "wake detection triggers on output diff or JSON wakeAgent=true"
    (is (true? (probe/wake-asked? "line1\n{\"wakeAgent\": true}\n")))
    (is (false? (probe/wake-asked? "unchanged output\n")))
    (let [r1 (probe/evaluate-probe-output "hello" nil)
          r2 (probe/evaluate-probe-output "hello" (:hash r1))
          r3 (probe/evaluate-probe-output "changed" (:hash r1))]
      (is (true? (:wake? r1)))
      (is (false? (:wake? r2)))
      (is (true? (:wake? r3)))))
  (testing "pacing gate enforces boot hold, daily cap, and deterministic backoff"
    (is (true? (probe/boot-hold? 1000 50000)))
    (is (false? (probe/boot-hold? 1000 200000)))
    (is (true? (probe/daily-cap-reached? 10 10)))
    (let [b1 (probe/deterministic-backoff-ms "run-abc" 1)
          b2 (probe/deterministic-backoff-ms "run-abc" 1)
          b3 (probe/deterministic-backoff-ms "run-abc" 3)]
      (is (= b1 b2) "backoff jitter is deterministic per run-id")
      (is (> b3 b1) "backoff grows exponentially"))))

(deftest action-receipts-auditor-and-failure-signature-normalizer
  (testing "detects unbacked past-tense action claims and ignores code blocks/denials"
    (let [unbacked (receipts/unbacked-claims
                    "I sent the email to Alice and downloaded the report."
                    [])]
      (is (contains? (set unbacked) :send))
      (is (contains? (set unbacked) :file)))
    (let [backed (receipts/unbacked-claims
                  "I wrote the updated config file."
                  [{:tool/name "edit_file" :status :ok :executed? true}])]
      (is (empty? backed)))
    (let [ignored (receipts/unbacked-claims
                   "I did not send the file.\n```\nI sent the email.\n```"
                   [])]
      (is (empty? ignored))))
  (testing "writes 0600 monthly JSONL receipt log allowlisted by state hygiene"
    (let [dir (fs/create-temp-dir {:prefix "theseus-receipts-"})]
      (try
        (with-redefs [config/home (constantly (str dir))]
          (receipts/append-receipt! {:session/id "s1" :tool "edit_file" :kind :file :ok? true})
          (is (empty? (hygiene/violations (fs/path dir "state")))))
        (finally
          (fs/delete-tree dir)))))
  (testing "failure-signature normalizer strips PII/paths/UUIDs into stable hash"
    (let [k1 (receipts/failure-key :shell "failed reading /Users/moe/a.clj id 550e8400-e29b-41d4-a716-446655440000 at https://example.com/1")
          k2 (receipts/failure-key :shell "failed reading /Users/bob/b.clj id 11111111-2222-3333-4444-555555555555 at https://other.org/9")]
      (is (= k1 k2))
      (is (str/starts-with? k1 "shell:")))))

(deftest fresh-context-post-deliverable-critic
  (testing "parses PASS and FAIL verdicts (capped at 3 gaps) and fails safe to :pass"
    (is (= {:verdict :pass :gaps []} (dc/parse-critic-verdict "PASS")))
    (let [fail (dc/parse-critic-verdict "FAIL\n- Missing section 2\n- Wrong row count\n- Broken total\n- Extra 4th item")]
      (is (= :fail (:verdict fail)))
      (is (= 3 (count (:gaps fail)))))
    (is (= :pass (:verdict (dc/parse-critic-verdict "ambiguous rambling")))))
  (testing "run-deliverable-check frames document as untrusted data"
    (let [captured (atom nil)
          res (dc/run-deliverable-check
               {:ask "Write 3 bullets" :reply "Done" :document "- one\n"}
               (fn [prompt]
                 (reset! captured prompt)
                 "FAIL\n- Only 1 bullet instead of 3"))]
      (is (str/includes? @captured "untrusted data"))
      (is (= :fail (:verdict res)))
      (is (= 1 (count (:gaps res)))))))
