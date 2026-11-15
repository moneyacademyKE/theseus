(ns e2e.brain-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bb-agent.brain :as brain]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]))

(deftest load-brain-missing-dir-returns-empty
  (is (= "" (brain/load-brain "/tmp/definitely-not-a-brain-dir-8391"))))

(deftest load-brain-sorts-headers-and-filters
  (let [dir (fs/create-temp-dir {:prefix "theseus-brain-"})]
    (try
      (spit (str (fs/path dir "z-identity.md")) "I am terse.")
      (spit (str (fs/path dir "a-rules.md")) "Never guess.")
      (spit (str (fs/path dir "notes.txt")) "not markdown")
      (let [out (brain/load-brain (str dir))]
        (is (str/includes? out "## a-rules.md"))
        (is (str/includes? out "## z-identity.md"))
        (is (str/includes? out "Never guess."))
        (is (str/includes? out "I am terse."))
        (is (not (str/includes? out "not markdown")))
        (is (neg? (compare (str/index-of out "## a-rules.md")
                           (str/index-of out "## z-identity.md")))))
      (finally
        (fs/delete-tree dir)))))

(deftest brain-context-injected-into-provider-request
  (let [home (fs/create-temp-dir {:prefix "theseus-brain-home-"})
        captured (atom nil)]
    (try
      (fs/create-dirs (fs/path home "brain"))
      (spit (str (fs/path home "brain" "IDENTITY.md"))
            "BRAIN-MARKER-742: speak in haiku")
      (with-redefs [config/home (fn [] (str home))
                    provider/complete (fn [_ request]
                                        (reset! captured request)
                                        {:content "fake: ok" :usage nil})]
        (let [turn (core/run-turn! {:provider :fake
                                    :model "fake-deterministic"
                                    :session/id "brain-e2e"}
                                   "say ok")]
          (is (= "fake: ok" (:assistant/final turn))))
        (let [messages (:messages @captured)
              system (filter #(= :system (:role %)) messages)]
          (is (= 1 (count system)) (pr-str messages))
          (is (str/includes? (:content (first system)) "BRAIN-MARKER-742"))
          (is (= :user (:role (last messages))))))
      (finally
        (fs/delete-tree home)))))

(deftest brain-context-cost-reports-what-is-always-injected
  ;; 2026-11-11: `load-brain-context`'s own docstring promises "per-turn cost
  ;; stays flat as knowledge grows" — true of knowledge/ (one line per page),
  ;; false of brain/*.md, which is loaded WHOLE and holds two append-only
  ;; ledgers. Measured live before this test existed: 754430 chars injected, of
  ;; which improvements.md alone is 635358 and wins.md 119318. Nothing counted it.
  ;; Source of the idea: ruslanudintsev-ai/agent-self-learning-core's Output
  ;; Budgeting (core/commands/base.py:196-292) and vibeeval/vibecosystem's
  ;; "Catalog size does not describe how much prompt content is loaded into each
  ;; request." Both measure what is actually injected rather than what exists.
  (let [home (fs/create-temp-dir {:prefix "theseus-brain-cost-"})]
    (try
      (fs/create-dirs (fs/path home "brain" "knowledge"))
      (spit (str (fs/path home "brain" "00-soul.md")) (apply str (repeat 100 "s")))
      (spit (str (fs/path home "brain" "ledger.md")) (apply str (repeat 900 "L")))
      (spit (str (fs/path home "brain" "knowledge" "one-page.md"))
            "# One page\nOwns: something narrow.\nbody body body\n")
      (let [cost (brain/context-cost (str (fs/path home "brain")))]
        (is (= :ok (:status cost)))
        ;; the dominant file is first, so the answer is not buried by volume
        (is (= "ledger.md" (get-in cost [:files 0 :name])))
        (is (= 900 (get-in cost [:files 0 :chars])))
        (is (= 100 (get-in cost [:files 1 :chars])))
        ;; a share per file, so "which one is it" is a number and not a guess
        (is (> (get-in cost [:files 0 :share]) (get-in cost [:files 1 :share])))
        (is (= 1000 (:identity-chars cost)))
        ;; the index is counted separately and is small by design
        (is (= 1 (:index-lines cost)))
        (is (< (:index-chars cost) (:identity-chars cost)))
        ;; and the honest total is the string that actually gets injected
        (is (= (count (brain/load-brain-context (str (fs/path home "brain"))))
               (:injected-chars cost)))
        (is (pos? (:approx-tokens cost))))
      (finally
        (fs/delete-tree home)))))

(deftest brain-context-cost-missing-dir-is-not-found-not-zero
  ;; A directory that does not exist is NOT a measured zero — the same rule
  ;; ledger-mentions learned on 2026-10-14. `:missing` with no counts at all.
  (let [cost (brain/context-cost "/tmp/no-such-brain-dir-8f3a1c")]
    (is (= :missing (:status cost)))
    (is (= [] (:files cost)))
    (is (nil? (:injected-chars cost)))
    (is (nil? (:identity-chars cost)))))

(deftest brain-context-cost-report-names-the-dominant-file-and-its-scope
  (let [home (fs/create-temp-dir {:prefix "theseus-brain-cost-report-"})]
    (try
      (fs/create-dirs (fs/path home "brain" "knowledge"))
      (spit (str (fs/path home "brain" "ledger.md")) (apply str (repeat 800 "L")))
      (spit (str (fs/path home "brain" "soul.md")) (apply str (repeat 200 "s")))
      (spit (str (fs/path home "brain" "knowledge" "a-page.md"))
            "# A page\nOwns: a narrow thing.\n")
      (let [report (with-out-str (brain/context-cost-print! (str (fs/path home "brain"))))]
        (is (str/includes? report "ledger.md"))
        (is (str/includes? report "soul.md"))
        ;; the share is printed, so 80% vs 20% is visible without arithmetic
        (is (re-find #"8\d\.\d%" report))
        ;; the token figure carries its own basis (selfsemester's `basis` rule)
        ;; the token figure carries its own basis (selfsemester's `basis` rule:
        ;; an estimate names where the number came from). Asserted against the
        ;; report's OWN wording — "injected-chars / 4", not "chars/4": the first
        ;; draft of this assertion was written from memory of the divisor and
        ;; missed the spaces, so it failed green-impl (the 11-04/11-09 mistake
        ;; class). Corrected against the file, not against the intention.
        (is (str/includes? report "injected-chars / 4"))
        (is (str/includes? report "not a provider tokenizer"))
        ;; and the report says what it did NOT measure
        (is (str/includes? report "negative scope"))
        (is (str/includes? report "not what the provider accepted")))
      (finally
        (fs/delete-tree home)))))

(deftest brain-context-cost-cli-exits-2-on-a-missing-path
  (is (= 2 (brain/-main ["/tmp/no-such-brain-dir-8f3a1c"]))))
