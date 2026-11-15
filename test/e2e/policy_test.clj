(ns e2e.policy-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.policy :as policy]
            [bb-agent.tool :as tool]
            [bb-agent.usage :as usage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]))

(def ^:private rules-allow-shell
  "{:rules [{:name \"allow-shell\"
             :pred (fn [tool _] (= tool \"shell\"))
             :decision :allow}]}")
(def ^:private rules-allow-writes
  "{:rules [{:name \"allow-writes\"
             :pred (fn [tool _] (= tool \"write_file\"))
             :decision :allow}]}")

(def ^:private rules-deny-shell
  "{:rules [{:name \"deny-shell\"
             :pred (fn [tool _] (= tool \"shell\"))
             :decision :deny}]}")

(def ^:private rules-first-deny-then-allow-all
  "{:rules [{:name \"deny-reads\"
             :pred (fn [tool _] (= tool \"read_file\"))
             :decision :deny}
            {:name \"allow-everything\"
             :pred (fn [_ _] true)
             :decision :allow}]}")

(def ^:private rules-runaway
  "{:rules [{:name \"loops-forever\"
             :pred (fn [_ _] (loop [] (recur)))
             :decision :allow}]}")

(def ^:private rules-broken-sandbox
  "{:rules [{:name \"touches-world\"
             :pred (fn [_ _] (spit \"policy-evil-probe.txt\" \"x\"))
             :decision :allow}]}")

(defn- write-rules! [home content]
  (fs/create-dirs (fs/path home "brain"))
  (spit (str (fs/path home "brain" "rules.clj")) content))

(defn- run-tool [home request cfg]
  (with-redefs [config/home (fn [] (str home))]
    (tool/handle-tool-request request cfg)))

(defn- run-agent [home prompt]
  (p/shell {:out :string
            :err :string
            :continue true
            :extra-env {"THESEUS_HOME" (str home)}}
           "bb" "agent" prompt))

(defn- write-config! [home m]
  (spit (str (fs/path home "config.edn")) (pr-str m)))

(deftest allow-verdict-lifts-default-ask
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-allow-"})
        target (fs/path home "docs" "plan.md")]
    (try
      (write-rules! home rules-allow-writes)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str target)
                                          :content "predicated"
                                          :create-dirs? true}}
                             {:policy {:enabled true}})]
        (is (= :ok (:status result)) (pr-str result))
        (is (fs/exists? target))
        (is (= "predicated" (slurp (str target)))))
      (finally
        (fs/delete-tree home)))))

(deftest deny-verdict-overrides-auto-all
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-deny-"})]
    (try
      (write-rules! home rules-deny-shell)
      (let [result (run-tool home
                             {:tool/name "shell"
                              :approval/policy :auto-all
                              :tool/args {:cmd "touch should-not-run"}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result))
        (is (not (fs/exists? (fs/path home "should-not-run")))))
      (finally
        (fs/delete-tree home)))))

(deftest first-deny-wins-over-a-grant
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-order-"})
        note (fs/path home "note.txt")]
    (try
      (spit (str note) "readable")
      (write-rules! home rules-first-deny-then-allow-all)
      (let [result (run-tool home
                             {:tool/name "read_file"
                              :approval/policy :auto-safe
                              :tool/args {:path (str note)}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest runaway-pred-times-out-closed
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-loop-"})]
    (try
      (write-rules! home rules-runaway)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str (fs/path home "x.txt"))
                                          :content "no"}}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest broken-rules-fail-closed
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-broken-"})
        note (fs/path home "note.txt")]
    (try
      (spit (str note) "readable")
      (write-rules! home rules-broken-sandbox)
      (let [read-result (run-tool home
                                  {:tool/name "read_file"
                                   :approval/policy :auto-safe
                                   :tool/args {:path (str note)}}
                                  {:policy {:enabled true}})
            write-result (run-tool home
                                   {:tool/name "write_file"
                                    :tool/args {:path (str (fs/path home "y.txt"))
                                                :content "no"}}
                                   {:policy {:enabled true}})]
        (is (= :denied (:status read-result)) (pr-str read-result))
        (is (= :policy-integrity (:denial/source read-result)))
        (is (= :denied (:status write-result)) (pr-str write-result))
        (is (not (fs/exists? (fs/path home "policy-evil-probe.txt"))))
        (is (not (fs/exists? "policy-evil-probe.txt"))))
      (finally
        (fs/delete-tree home)))))

(deftest disabled-ignores-rules
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-off-"})]
    (try
      (write-rules! home rules-allow-writes)
      (let [result (run-tool home
                             {:tool/name "write_file"
                              :tool/args {:path (str (fs/path home "z.txt"))
                                          :content "no"}}
                             {:policy {:enabled false}})]
        (is (= :denied (:status result)) (pr-str result)))
      (finally
        (fs/delete-tree home)))))

(deftest sandbox-contract-pin
  (is (thrown? Exception (sci/eval-string "(spit \"sandbox-probe\" \"x\")" {})))
  (is (thrown? Exception (sci/eval-string "(require '[clojure.java.io])" {})))
  (is (thrown? Exception (sci/eval-string "(System/currentTimeMillis)" {})))
  (is (thrown? Exception (sci/eval-string "(Thread/sleep 1)" {})))
  (is (thrown? Exception (sci/eval-string "(deref (future 1))" {})))
  (is (= "A" (sci/eval-string "(require '[clojure.string :as str]) (str/upper-case \"a\")" {}))))

(deftest subprocess-policy-deny-beats-auto-all
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-e2e-deny-"})]
    (try
      (write-config! home {:policy {:enabled true}})
      (write-rules! home rules-deny-shell)
      (let [result (run-agent home "try approved shell touch e2e-denied-marker")]
        (is (= 0 (:exit result)) (:err result))
        ;; The floor is the assertion that matters: the call did not run.
        ;; "needs your approval" was asserted here until 2026-09-11 — for a
        ;; CONSTITUTION denial nothing is awaiting approval, so the message
        ;; was a lie the human could not act on, and in a detached lane it
        ;; ended the turn with no recovery possible.
        (is (str/includes? (:out result) "constitution")
            "the denial that reaches the model names the law that fired")
        (is (not (str/includes? (:out result) "needs your approval"))
            "a policy denial asks nobody — it must not claim otherwise")
        ;; No clean-sentence assertion here any more: a policy denial ends
        ;; the round, not the turn, so the reply is whatever the model
        ;; composes from the denial (the fake provider echoes its tool
        ;; result). The old assertion passed only because the turn DIED at
        ;; the first veto — it was pinning the dead end.
        (is (not (fs/exists? (fs/path home "e2e-denied-marker")))))
      (finally
        (fs/delete-tree home)))))

(deftest subprocess-policy-allow-beats-ask
  (let [home (fs/create-temp-dir {:prefix "theseus-policy-e2e-allow-"})]
    (try
      (write-config! home {:policy {:enabled true}})
      (write-rules! home rules-allow-shell)
      (let [result (run-agent home "try denied shell touch e2e-allowed-marker")]
        (is (= 0 (:exit result)) (:err result))
        (is (str/includes? (:out result) ":status :ok") (:out result))
        (is (fs/exists? (fs/path home "e2e-allowed-marker"))))
      (finally
        (fs/delete-tree home)))))

(def ^:private v5-floor-denies
  ["deny-python" "deny-rm" "deny-irreversible-git"
   "deny-secrets" "deny-sudo" "deny-constitution-self-edit"])

(deftest owner-grants-v5-constitution-contract
  ;; Pin the proposed candidate, never a developer's or production home. The
  ;; live constitution remains unchanged until reviewed and credential rotation.
  (let [f "configs/rules.candidate.clj"]
    (is (fs/exists? f) "the isolated candidate must ship with its tests")
    (when (fs/exists? f)
      (let [rules (:rules (sci/eval-string (slurp (str f)) {}))
            verdict (fn [tool args]
                      (some (fn [{:keys [pred decision]}]
                              (when (pred tool args) decision))
                            rules))
            names (mapv :name rules)
            idx (fn [n] (.indexOf names n))
            first-allow (some (fn [[i r]] (when (= :allow (:decision r)) i))
                              (map-indexed vector rules))]
        ;; freedoms granted by the loosening
        (is (= :allow (verdict "shell" {:cmd "sed -i '' s/a/b/ f.txt"})) "shell mutations")
        (is (= :allow (verdict "write_file" {:path "brain/00-soul.md"})) "self-modification")
        (is (= :allow (verdict "launch_goal" {})) "goal loop reachable from normal prompts (v5.1)")
        (is (= :allow (verdict "shell" {:cmd "chmod +x check.sh && ./check.sh"})) "chmod is a v5-granted everyday mutation (live-fire regression: shadow list used to deny it)")
        (is (= :allow (verdict "shell" {:cmd "mv a.txt b.txt"})) "mv is v5-granted")
        (is (= :deny (verdict "shell" {:cmd "dd if=/dev/zero of=x"})) "dd floor")
        (is (= :deny (verdict "shell" {:cmd "chown root x"})) "chown floor")
        ;; durable floor
        (is (= :deny (verdict "shell" {:cmd "rm -rf x"})) "rm floor")
        (is (= :deny (verdict "shell" {:cmd "python3 x.py"})) "python floor")
        (is (= :deny (verdict "shell" {:cmd "git push -f origin main"})) "git floor")
        (is (= :deny (verdict "read_file" {:path "/Users/moe/theseus/config.edn"})) "secrets floor")
        (is (not= :deny (verdict "read_file"
                                 {:path "/Users/moe/theseus/goals/x.config.edn"}))
            "goal-runner configs (*.config.edn) are NOT the secrets file — fence is boundary-anchored (live-fire regression)")
        ;; 2026-09-08: .key/.pem match only in path context — a Rust struct
        ;; field named rec.key is not a private key file. Three rustdb build
        ;; turns died to this overmatch before the fence was narrowed.
        (is (not= :deny (verdict "shell" {:cmd "perl -0pi -e 's/a/index.remove(&rec.key),/b/' src/lib.rs"}))
            "rec.key struct field is NOT a secrets hit (rustdb regression)")
        (is (not= :deny (verdict "shell" {:cmd "grep -rn 'rec.key' src/"}))
            "grep over struct fields is NOT a secrets hit")
        (is (= :deny (verdict "shell" {:cmd "cat ~/.ssh/server.key"})) "path-context .key still denied")
        (is (= :deny (verdict "shell" {:cmd "cat /etc/ssl/cert.pem"})) "path-context .pem still denied")
        (is (= :deny (verdict "shell" {:cmd "cat ~/.ssh/id_ed25519"})) "id_ed25519 floor")
        ;; 2026-09-11: bare `secret` substring ate a commit whose heredoc
        ;; MESSAGE discussed the fence itself ("secrets" in prose). Writing
        ;; about secrets is not exfiltrating one — the word now needs path
        ;; context (a slash) or a file extension.
        (is (not= :deny (verdict "shell" {:cmd "git commit -m 'core: the secrets fence overmatched'"}))
            "the word 'secrets' in prose is NOT a secrets hit (commit-message regression)")
        (is (not= :deny (verdict "shell" {:cmd "echo the deny-secrets rule fired"}))
            "naming the rule in prose is NOT a secrets hit")
        (is (= :deny (verdict "shell" {:cmd "cat ~/.aws/secrets"})) "path-context secrets file still denied")
        (is (= :deny (verdict "shell" {:cmd "cat secrets.txt"})) "bare secrets.<ext> file still denied")
        (is (not= :deny (verdict "shell" {:cmd "cat production-secrets"}))
            "bare extensionless name is the accepted residual risk (same class as bare server.key)")
        (is (= :deny (verdict "shell" {:cmd "sudo ls"})) "sudo floor")
        (is (= :deny (verdict "write_file" {:path "brain/rules.clj"})) "law protects itself")
        (is (= :deny (verdict "write_file" {:path "/work/brain/rules.clj"})) "absolute law protects itself")
        (is (= :deny (verdict "write_file" {:path "./brain/rules.clj"})) "relative law protects itself")
        (is (= :allow (verdict "write_file" {:path "/work/other/rules.clj"})) "unrelated rules file")
        (is (= :allow (verdict "read_file" {:path "/work/app/.env.example"})) "example env file")
        (is (= :allow (verdict "shell" {:cmd "cat /work/app/.env.example"})) "shell example env file")
        (is (= :deny (verdict "shell" {:cmd "cat /work/app/.env.example; cat /work/app/.env"})) "example cannot hide secret")
        (is (= :deny (verdict "shell" {:cmd "cat /work/app/.env.example # /work/app/.env"})) "comments remain conservative")
        (is (= :deny (verdict "read_file" {:path "/work/app/.env"})) "real env file")
        (is (= :deny (verdict "read_file" {:path "/work/app/.env.examples"})) "not an example")
        (is (= :deny (verdict "read_file" {:path "/work/app/.env.example.local"})) "local secrets")
        (is (= :deny (verdict "shell" {:cmd "cat /work/app/.env.example.local"})) "shell local secrets")
        (is (= :deny (verdict "shell" {:cmd "cat /work/app/.env"})) "shell env access")
        (is (= :deny (verdict "shell" {:cmd "cat config.edn"})) "shell config access")
        (is (= :deny (verdict "shell" {:cmd "cat /work/app/.env" :path "/work/app/.env.example"})) "unused path cannot hide command")
        (is (= :allow (verdict "shell" {:cmd "echo python version"})) "inert echo is not execution")
        (is (= :deny (verdict "shell" {:cmd "echo python; python3 task.py"})) "echo cannot hide execution")
        (is (= :deny (verdict "shell" {:cmd "echo $(python3 task.py)"})) "substitution cannot hide execution")
        (is (= :deny (verdict "shell" {:cmd "(python3 task.py)"})) "subshell cannot hide execution")
        (is (= :deny (verdict "shell" {:cmd "echo `python3 task.py`"})) "backticks cannot hide execution")
        (is (= :deny (verdict "shell" {:cmd "python3 task.py"})) "direct python execution")
        (is (= :deny (verdict "shell" {:cmd "echo ok; python3 task.py"})) "compound python execution")
        (is (= :deny (verdict "shell" {:cmd "/usr/bin/python3 task.py"})) "absolute python")
        (is (= :deny (verdict "shell" {:cmd "env python3 task.py"})) "env python")
        (is (= :deny (verdict "shell" {:cmd "command python3 task.py"})) "command python")
        (is (= :deny (verdict "shell" {:cmd "FOO=bar python3 task.py"})) "environment assignment")
        (is (= :allow (verdict "shell" {:cmd "git push origin main"})) "normal push")
        (is (= :deny (verdict "shell" {:cmd "git push --force-with-lease origin main"})) "force-with-lease")
        (is (= :deny (verdict "shell" {:cmd "git -C /work push -f origin main"})) "git global options")
        (is (= :deny (verdict "shell" {:cmd "git push origin +main:refs/heads/main"})) "forced refspec")
        (is (= :deny (verdict "shell" {:cmd "git push origin main --force=anything"})) "forced push long form")
        ;; regeneration rule as code: every durable deny precedes the first allow
        (is (every? #(< (idx %) first-allow) v5-floor-denies)
            "layer-1 denies sit above all allows")))))


(deftest candidate-decisions-use-the-production-evaluator
  (let [candidate "configs/rules.candidate.clj"
        cases [{:tool/name "shell" :tool/args {:cmd "echo python version"}
                :expected :allow}
               {:tool/name "shell" :tool/args {:cmd "git push origin main"}
                :expected :allow}
               {:tool/name "read_file" :tool/args {:path "/work/.env.example"}
                :expected :allow}
               {:tool/name "shell" :tool/args {:cmd "python3 task.py"}
                :expected :deny :rule "deny-python"}
               {:tool/name "shell" :tool/args {:cmd "git push --force origin main"}
                :expected :deny :rule "deny-irreversible-git"}
               {:tool/name "read_file" :tool/args {:path "/work/.env"}
                :expected :deny :rule "deny-secrets"}]]
    (with-redefs [policy/rules-file (constantly candidate)]
      (doseq [{:keys [expected rule] :as case} cases]
        (let [result (policy/evaluate case {:policy {:enabled true}})]
          (is (= expected (:decision result)) (pr-str case))
          (when rule
            (is (= rule (:rule result)) (pr-str case))))))))

(deftest candidate-rules-work-through-the-tool-seam
  (let [home (fs/create-temp-dir {:prefix "policy-candidate-"})
        example (fs/path home "work" ".env.example")
        secret (fs/path home "work" ".env")
        unrelated (fs/path home "work" "other" "rules.clj")
        cfg {:policy {:enabled true}}]
    (try
      (write-rules! home (slurp "configs/rules.candidate.clj"))
      (fs/create-dirs (fs/parent example))
      (spit (str example) "example")
      (spit (str secret) "fixture-secret")
      (let [echo (run-tool home {:tool/name "shell"
                                 :tool/args {:cmd "echo python version" :cwd (str home)}} cfg)
            read-example (run-tool home {:tool/name "read_file"
                                         :tool/args {:path (str example)}} cfg)
            read-secret (run-tool home {:tool/name "read_file"
                                        :tool/args {:path (str secret)}} cfg)
            write-unrelated (run-tool home {:tool/name "write_file"
                                            :tool/args {:path (str unrelated)
                                                        :content "example" :create-dirs? true}} cfg)]
        (is (= :ok (:status echo)))
        (is (= "python version\n" (:stdout echo)))
        (is (= :ok (:status read-example)))
        (is (= "example" (:content read-example)))
        (is (= :denied (:status read-secret)))
        (is (= "deny-secrets" (:denial/rule read-secret)))
        (is (nil? (:content read-secret)))
        (is (= :ok (:status write-unrelated)))
        (is (= "example" (slurp (str unrelated)))))
      (finally (fs/delete-tree home)))))

(deftest grant-cannot-bypass-an-explicit-ask
  (let [home (fs/create-temp-dir {:prefix "policy-explicit-ask-"})
        target (str (fs/path home "not-written"))
        calls (atom 0)]
    (try
      (write-rules! home rules-allow-writes)
      (let [result (run-tool home {:tool/name "write_file"
                                   :tool/args {:path target :content "no"}
                                   :approval/policy :ask}
                             {:policy {:enabled true}
                              :approval/ask (fn [_] (swap! calls inc) :denied)})]
        (is (= :denied (:status result)))
        (is (= 1 @calls))
        (is (not (fs/exists? target))))
      (finally (fs/delete-tree home)))))

(deftest unnamed-legacy-rule-remains-valid-and-explainable
  (let [home (fs/create-temp-dir {:prefix "policy-unnamed-"})]
    (try
      (write-rules! home "{:rules [{:pred (fn [_ _] true) :decision :deny}]}")
      (let [result (run-tool home {:tool/name "shell"
                                   :tool/args {:cmd "echo safe"}
                                   :approval/policy :auto-all}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)))
        (is (= :policy (:denial/source result)))
        (is (= "<unnamed>" (:denial/rule result)))
        (is (= :safety-floor (:denial/category result))))
      (finally (fs/delete-tree home)))))

(deftest malformed-and-throwing-rules-fail-closed
  (let [home (fs/create-temp-dir {:prefix "policy-errors-"})
        target (str (fs/path home "must-not-exist"))
        cases [["not valid clojure (" :exception]
               ["{:rules []}" :invalid]
               ["{:rules [{:name \"missing-pred\" :decision :allow}]}" :invalid]
               [(str "{:rules [{:name \"fault\" "
                     ":pred (fn [_ _] (throw (ex-info \"fault\" {}))) "
                     ":decision :allow}]}") :exception]]]
    (try
      (doseq [[source reason] cases]
        (write-rules! home source)
        (let [result (run-tool home {:tool/name "write_file"
                                     :tool/args {:path target :content "no"}
                                     :approval/policy :auto-all}
                               {:policy {:enabled true}})]
          (is (= :denied (:status result)) (str reason))
          (is (= :policy-integrity (:denial/source result)) (str reason))
          (is (= reason (:denial/reason result)) (str reason))
          (is (not (fs/exists? target)) (str reason))))
      (finally (fs/delete-tree home)))))

(deftest allow-before-deny-cannot-shadow-a-floor
  (let [home (fs/create-temp-dir {:prefix "policy-order-"})
        marker (str (fs/path home "not-run"))]
    (try
      (write-rules! home (str "{:rules [{:name \"broad-allow\" :pred (fn [_ _] true) :decision :allow} "
                              "{:name \"deny-shell\" :pred (fn [tool _] (= tool \"shell\")) :decision :deny}]}"))
      (let [r (run-tool home {:tool/name "shell" :tool/args {:cmd (str "touch " marker)}
                              :approval/policy :auto-all}
                        {:policy {:enabled true}})]
        (is (= :denied (:status r)))
        (is (= "deny-shell" (:denial/rule r)))
        (is (str/includes? (:error/message r) "deny-shell"))
        (is (not (fs/exists? marker))))
      (finally (fs/delete-tree home)))))

(deftest enabled-policy-failure-does-not-authorize-any-call
  (let [home (fs/create-temp-dir {:prefix "policy-invalid-"})
        secret-file (str (fs/path home "secret.txt"))]
    (try
      (spit secret-file "fixture-only")
      (doseq [approval [:auto-all :auto-safe :ask]]
        (let [r (run-tool home {:tool/name "read_file" :tool/args {:path secret-file}
                                :approval/policy approval}
                          {:policy {:enabled true} :approval/ask (constantly :approved)})]
          (is (= :denied (:status r)) (str approval))
          (is (= :policy-integrity (:denial/source r)) (str approval))))
      (finally (fs/delete-tree home)))))

(deftest explicit-never-cannot-be-overridden-by-a-grant
  (let [home (fs/create-temp-dir {:prefix "policy-never-"})
        target (str (fs/path home "not-written"))]
    (try
      (write-rules! home rules-allow-writes)
      (let [r (run-tool home {:tool/name "write_file" :tool/args {:path target :content "no"}
                              :approval/policy :never}
                        {:policy {:enabled true}})]
        (is (= :denied (:status r)))
        (is (not (fs/exists? target))))
      (finally (fs/delete-tree home)))))

(deftest explicit-ask-is-not-overridden-by-config-auto-all
  (let [home (fs/create-temp-dir {:prefix "policy-ask-"})
        target (str (fs/path home "not-written"))
        rules "{:rules [{:name \"no-match\" :pred (fn [_ _] false) :decision :allow}]}"
        calls (atom 0)]
    (try
      (write-rules! home rules)
      (let [r (run-tool home {:tool/name "write_file" :tool/args {:path target :content "no"}
                              :approval/policy :ask}
                        {:policy {:enabled true} :approval/mode :auto-all
                         :approval/ask (fn [_] (swap! calls inc) :denied)})]
        (is (= :denied (:status r)))
        (is (= 1 @calls))
        (is (not (fs/exists? target))))
      (finally (fs/delete-tree home)))))

(deftest grants-do-not-override-restrictive-or-unknown-approval-modes
  (let [home (fs/create-temp-dir {:prefix "policy-mode-"})
        target (str (fs/path home "not-written"))]
    (try
      (write-rules! home rules-allow-writes)
      (doseq [mode [:never :auto-safe :ask :bogus]]
        (let [result (run-tool home {:tool/name "write_file"
                                     :tool/args {:path target :content "no"}}
                               {:policy {:enabled true} :approval/mode mode})]
          (is (= :denied (:status result)) (str "configured " mode))
          (is (not (fs/exists? target)) (str "configured " mode))))
      (let [result (run-tool home {:tool/name "write_file"
                                   :tool/args {:path target :content "no"}
                                   :approval/policy :bogus}
                             {:policy {:enabled true}})]
        (is (= :denied (:status result)) "unknown per-request mode")
        (is (not (fs/exists? target))))
      (finally (fs/delete-tree home)))))

(deftest policy-denial-is-attributed-in-the-usage-ledger
  (let [home (fs/create-temp-dir {:prefix "policy-ledger-"})]
    (try
      (write-rules! home rules-deny-shell)
      (with-redefs [config/home (constantly (str home))]
        (let [result (tool/handle-tool-request
                      {:tool/name "shell" :tool/args {:cmd "echo safe"}
                       :session/id "policy-test" :approval/policy :auto-all}
                      {:policy {:enabled true}})
              event (first (usage/load-events))]
          (is (= :denied (:status result)))
          (is (= "deny-shell" (:denial/rule event)))
          (is (= :safety-floor (:denial/category event)))))
      (finally (fs/delete-tree home)))))

(deftest missing-policy-records-actionable-reason
  (let [home (fs/create-temp-dir {:prefix "policy-reason-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (let [result (tool/handle-tool-request
                      {:tool/name "shell" :tool/args {:cmd "echo safe"}
                       :session/id "policy-test" :approval/policy :auto-all}
                      {:policy {:enabled true}})
              event (first (usage/load-events))]
          (is (= :denied (:status result)))
          (is (= :missing (:denial/reason result)))
          (is (= :missing (:denial/reason event)))
          (is (str/includes? (:error/message result) "missing"))))
      (finally (fs/delete-tree home)))))
