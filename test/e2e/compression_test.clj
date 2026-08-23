(ns e2e.compression-test
  (:require [bb-agent.compression :as compression]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- msg [role content] {:role role :content content})

(defn- sized [n] (msg :user (apply str (repeat n "x"))))

(deftest estimate-tokens-scales-with-content
  (testing "empty conversation is zero tokens"
    (is (= 0 (compression/estimate-tokens []))))
  (testing "chars / 3.5, rounded up"
    (is (= 2 (compression/estimate-tokens [(sized 4)])))  ; 4/3.5 -> 2
    (is (= 2 (compression/estimate-tokens [(sized 7)])))  ; 7/3.5 -> 2
    (is (= 3 (compression/estimate-tokens [(sized 8)])))) ; 8/3.5 -> 3
  (testing "tool payloads count toward the estimate"
    (is (= 3 (compression/estimate-tokens
              [{:role :tool :content "" :tool/results [{:a 1}]}]))))) ; pr-str is 8 chars

(deftest should-compress-crosses-threshold
  (is (false? (compression/should-compress? [(msg :user "hi")] 1000)))
  (is (true? (compression/should-compress? (repeat 500 (msg :user "hello world")) 1000))))

(deftest prune-keeps-only-recent-tool-results
  (let [messages [(msg :user "list files")
                  (msg :tool "output-1")
                  (msg :tool "output-2")
                  (msg :tool "output-3")
                  (msg :assistant "done")]
        pruned (compression/prune-old-tool-results messages 2)]
    (testing "all but the 2 most recent tool messages are cleared"
      (is (= "[old tool output cleared]" (get-in pruned [1 :content])))
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
    (testing "head kept, middle replaced by one summary message, tail kept"
      (let [result (compression/compress messages
                                         {:protect-first 2
                                          :tail-char-budget 14 ; exactly the final message
                                          :call-llm-fn fake-llm})]
        (is (= 4 (count result)))
        (is (= "sys prompt" (get-in result [0 :content])))
        (is (= "original goal" (get-in result [1 :content])))
        (is (= :user (get-in result [2 :role])))
        (is (str/includes? (get-in result [2 :content]) "CONTEXT COMPACTION"))
        (is (str/includes? (get-in result [2 :content]) "GOAL: ping the box."))
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
