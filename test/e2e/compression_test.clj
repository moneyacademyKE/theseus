(ns e2e.compression-test
  (:require [babashka.fs :as fs]
            [bb-agent.compression :as compression]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- msg [role content] {:role role :content content})

(defn- sized [n] (msg :user (apply str (repeat n "x"))))

(deftest estimate-tokens-scales-with-content
  (testing "empty conversation is zero tokens"
    (is (= 0 (compression/estimate-tokens []))))
  (testing "unified chars / 4, rounded up"
    (is (= 1 (compression/estimate-tokens [(sized 4)])))
    (is (= 2 (compression/estimate-tokens [(sized 7)])))
    (is (= 2 (compression/estimate-tokens [(sized 8)])))
    (is (= 3 (compression/estimate-tokens [(sized 9)]))))
  (testing "tool payloads count toward the estimate"
    (is (= 2 (compression/estimate-tokens
              [{:role :tool :content "" :tool/results [{:a 1}]}])))))

(deftest should-compress-crosses-threshold
  (is (false? (compression/should-compress? [(msg :user "hi")] 1000)))
  (is (true? (compression/should-compress? (repeat 500 (msg :user "hello world")) 1000))))

(deftest prune-keeps-only-recent-tool-results-with-breadcrumbs
  (let [messages [(msg :user "list files")
                  {:role :tool
                   :content "long raw output"
                   :tool/results [{:tool/name "read_file"
                                   :status :ok
                                   :content "line one of file\nline two"}]}
                  (msg :tool "output-2")
                  (msg :tool "output-3")
                  (msg :assistant "done")]
        pruned (compression/prune-old-tool-results messages 2)]
    (testing "oldest tool message is condensed to a deterministic 1-line breadcrumb"
      (let [b (get-in pruned [1 :content])]
        (is (str/starts-with? b "[tool pruned:"))
        (is (str/includes? b "read_file"))
        (is (str/includes? b "ok"))
        (is (str/includes? b "line one of file"))
        (is (nil? (get-in pruned [1 :tool/results])) "heavy tool payload dropped")))
    (testing "the 2 most recent tool messages survive untouched"
      (is (= "output-2" (get-in pruned [2 :content])))
      (is (= "output-3" (get-in pruned [3 :content])))
      (is (= "done" (get-in pruned [4 :content]))))))

(deftest find-boundaries-protects-head-and-tail-budget
  (let [messages [(sized 10)  ; 0 head
                  (sized 10)  ; 1 head
                  (sized 20)  ; 2
                  (sized 20)  ; 3
                  (sized 5)   ; 4
                  (sized 5)]] ; 5
    (testing "budget too small for a second message keeps only the last"
      (is (= [2 5] (compression/find-boundaries messages 2 9))))
    (testing "budget admitting exactly two small messages keeps the last two"
      (is (= [2 4] (compression/find-boundaries messages 2 10)))
      (is (= [2 4] (compression/find-boundaries messages 2 11))))
    (testing "generous budget fits everything, no middle remains"
      (is (= [2 2] (compression/find-boundaries messages 2 1000))))))

(deftest compress-summarizes-middle-and-keeps-zones
  (let [prompts (atom [])
        fake-llm (fn [prompt]
                   (swap! prompts conj prompt)
                   "GOAL: ping the box. PROGRESS: done.")
        messages (into [(msg :system "sys prompt")
                        (msg :user "original goal")]
                       (concat (map (fn [i] (msg :user (str "filler-" i))) (range 6))
                               [(msg :user "final question")]))]
    (testing "head kept, middle replaced by one summary message with provenance, tail kept"
      (let [result (compression/compress messages
                                         {:protect-first 2
                                          :tail-char-budget 14
                                          :call-llm-fn fake-llm})]
        (is (= 4 (count result)))
        (is (= "sys prompt" (get-in result [0 :content])))
        (is (= "original goal" (get-in result [1 :content])))
        (is (= :user (get-in result [2 :role])))
        (is (str/includes? (get-in result [2 :content]) "CONTEXT COMPACTION"))
        (is (str/includes? (get-in result [2 :content]) "GOAL: ping the box."))
        (is (str/includes? (get-in result [2 :content]) "[Turns 2-7]"))
        (is (= "final question" (get-in result [3 :content])))
        (is (= 1 (count @prompts)) "summarizer called exactly once")
        (let [prompt (first @prompts)]
          (is (str/includes? prompt "filler-1") "middle content reached the summarizer")
          (is (str/includes? prompt "[user]") "turns are labelled by role")
          (is (not (str/includes? prompt "final question")) "tail stays out of the summary")
          (is (not (str/includes? prompt "original goal")) "head stays out of the summary"))))))

(deftest compress-noop-when-nothing-to-compress
  (let [messages [(msg :system "sys") (msg :user "goal") (msg :user "reply")]]
    (testing "everything fits inside protected zones: returned as-is"
      (is (= messages
             (compression/compress messages {:protect-first 3
                                             :tail-char-budget 0
                                             :call-llm-fn (fn [_] (is false "summarizer must not run"))}))))))

(deftest structured-memory-parses-edn-merges-newest-tail-and-measures-retention
  (testing "EDN map response parses into summary + structured fields"
    (let [parsed (compression/parse-summary-response
                  (pr-str {:summary "migrated SQLite"
                           :facts ["go-sqlite3 pod used"]
                           :decisions ["use BEGIN IMMEDIATE"]
                           :files ["src/bb_agent/web_search.clj"]
                           :pending ["run full suite"]}))]
      (is (= "migrated SQLite" (:summary parsed)))
      (is (= ["go-sqlite3 pod used"] (:facts parsed)))
      (is (= ["src/bb_agent/web_search.clj"] (:files parsed)))))
  (testing "plain prose falls back safely without throwing"
    (let [parsed (compression/parse-summary-response "plain prose summary")]
      (is (= "plain prose summary" (:summary parsed)))
      (is (empty? (:facts parsed)))))
  (testing "merge keeps newest bounded tail and deduplicates"
    (let [old {:facts (mapv #(str "fact-" %) (range 10))
               :decisions ["d1"] :files ["a.clj"] :pending ["p1"]}
          new {:facts ["fact-9" "fact-10" "fact-11" "fact-12" "fact-13"]
               :decisions ["d2"] :files ["b.clj"] :pending ["p2"]}
          merged (compression/merge-structured-memory old new 5)]
      (is (= ["fact-9" "fact-10" "fact-11" "fact-12" "fact-13"] (:facts merged))
          "keeps the newest 5 facts instead of freezing the oldest 5")
      (is (= ["d1" "d2"] (:decisions merged)))))
  (testing "retention-report computes pure set-diff retention"
    (let [before {:facts ["f1" "f2"] :decisions ["d1"] :files ["a.clj"] :pending []}
          after  {:facts ["f1" "f3"] :decisions ["d1"] :files ["a.clj"] :pending []}
          rep (compression/retention-report before after)]
      (is (= 4 (:tracked rep)))
      (is (= 3 (:retained rep)))
      (is (= #{"f2"} (:dropped rep)))
      (is (== 0.75 (:retention-rate rep))))))

(deftest dynamic-history-budget-accounts-for-model-and-overhead
  (testing "explicit :history-budget-chars always wins"
    (is (= 400 (compression/history-budget-chars {:model "claude-sonnet-4"
                                                  :history-budget-chars 400}
                                                 50000))))
  (testing "larger models get a larger budget, reduced by system overhead"
    (let [small-b (compression/history-budget-chars {:model "fake-deterministic"} 0)
          big-b (compression/history-budget-chars {:model "claude-sonnet-4"} 0)
          big-with-overhead (compression/history-budget-chars {:model "claude-sonnet-4"} 40000)]
      (is (> big-b small-b))
      (is (< big-with-overhead big-b)))))

(deftest session-load-turn-range-drills-down-verbatim
  (let [home (fs/create-temp-dir {:prefix "theseus-drill-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (dotimes [i 5]
          (session/append-turn! "drill-1" {:user/input (str "q-" i)
                                           :assistant/final (str "a-" i)}))
        (let [slice (session/load-turn-range "drill-1" 1 3)]
          (is (= [1 2 3] (mapv :turn/index slice)))
          (is (= ["q-1" "q-2" "q-3"] (mapv :user/input slice)))))
      (finally
        (fs/delete-tree home)))))
