(ns e2e.policy-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool :as tool]
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
            :extra-env {"OPENCRABS_HOME" (str home)}}
           "bb" "agent" prompt))

(defn- write-config! [home m]
  (spit (str (fs/path home "config.edn")) (pr-str m)))

(deftest allow-verdict-lifts-default-ask
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-allow-"})
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
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-deny-"})]
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

(deftest first-match-wins
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-order-"})
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

(deftest runaway-pred-times-out-to-baseline
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-loop-"})]
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

(deftest broken-rules-fail-to-baseline
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-broken-"})
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
        (is (= :ok (:status read-result)) (pr-str read-result))
        (is (= :denied (:status write-result)) (pr-str write-result))
        (is (not (fs/exists? (fs/path home "policy-evil-probe.txt"))))
        (is (not (fs/exists? "policy-evil-probe.txt"))))
      (finally
        (fs/delete-tree home)))))

(deftest disabled-ignores-rules
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-off-"})]
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
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-e2e-deny-"})]
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
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-policy-e2e-allow-"})]
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
  ;; Pins the owner's 2026-09-06 loosening: layer-2 freedoms + layer-1 floor.
  ;; The regeneration rule (rules.clj header) says a rebuilt grant set replaces
  ;; layer 2 only — this test fails if a rebuild drops the durable floor or
  ;; reorders it below the allows. Skips harmlessly where no constitution exists.
  (let [candidates [(fs/path (config/home) "brain" "rules.clj")
                    ;; the production runtime home (launchd sets OPENCRABS_HOME
                    ;; there; bare shells don't have it) — pin the real law
                    (fs/path "/Users/moe/theseus/brain" "rules.clj")]
        f (some #(when (fs/exists? %) %) candidates)]
    (when f
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
        ;; regeneration rule as code: every durable deny precedes the first allow
        (is (every? #(< (idx %) first-allow) v5-floor-denies)
            "layer-1 denies sit above all allows")))))
