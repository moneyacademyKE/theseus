(ns bb-agent.truncation-test
  (:require [bb-agent.truncation :as trunc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest looks-truncated-mid-sentence-test
  (testing "detects mid-word / dangling clause / unclosed fence endings"
    (is (true? (trunc/looks-truncated-mid-sentence "Here is the fix, because the handler")))
    (is (true? (trunc/looks-truncated-mid-sentence "```clojure\n(defn foo [x]\n  (+ x 1)")))
    (is (true? (trunc/looks-truncated-mid-sentence "The result is:"))))

  (testing "cleanly terminated sentences and code blocks are not flagged"
    (is (false? (trunc/looks-truncated-mid-sentence "All tests pass.")))
    (is (false? (trunc/looks-truncated-mid-sentence "Done! Ready for Wave C?")))
    (is (false? (trunc/looks-truncated-mid-sentence "```clojure\n(+ 1 2)\n```")))
    (is (false? (trunc/looks-truncated-mid-sentence "")))
    (is (false? (trunc/looks-truncated-mid-sentence nil)))))

(deftest usage-gap-corroboration-test
  (testing "subtracts reasoning tokens before checking 2-char/token floor"
    (is (true? (trunc/usage-gap? "short" {:tokens/output 200 :tokens/reasoning 0})))
    (is (false? (trunc/usage-gap? "short" {:tokens/output 200 :tokens/reasoning 198}))
        "thinking-heavy turns must not fake a gap")
    (is (false? (trunc/usage-gap? "short" {:tokens/output 0})))))

(deftest continuation-nudge-and-degenerate-guard-test
  (testing "embeds verbatim tail anchor and forbids lone markup closure"
    (let [nudge (trunc/continuation-nudge "Some long preamble and then the handler crashed because" nil)]
      (is (str/includes? nudge "the handler crashed because"))
      (is (str/includes? nudge "---"))
      (is (str/includes? nudge "closing it alone does NOT count"))))

  (testing "flags degenerate continuations (empty, pure backticks/punct, or tail echo)"
    (is (true? (trunc/degenerate-continuation? "ended in `code" "`")))
    (is (true? (trunc/degenerate-continuation? "ended in ```clojure\n(foo" "```")))
    (is (true? (trunc/degenerate-continuation? "some text ending here" "ending here")))
    (is (false? (trunc/degenerate-continuation? "the handler crashed because" " the socket closed cleanly.")))))
