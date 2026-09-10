(ns bb-agent.brief-test
  (:require [bb-agent.brief :as brief]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def sample-state
  "# AI-tech-stock hourly brief — rotation state
# Tickers already covered (do not repeat):
- AVGO (2026-09-10)
- NVDA (2026-09-10)

# Queue (pick next unused, in rough rotation order):
MU
ORCL
- MSFT
")

(deftest parse-state-test
  (let [state (brief/parse-state sample-state)]
    (testing "covered pairs parsed in order"
      (is (= [["AVGO" "2026-09-10"] ["NVDA" "2026-09-10"]] (:covered state))))
    (testing "queue accepts bare and dashed tickers"
      (is (= ["MU" "ORCL" "MSFT"] (:queue state))))
    (testing "next ticker is the queue head"
      (is (= "MU" (brief/next-ticker state))))))

(deftest state-roundtrip-test
  (let [state (brief/parse-state sample-state)
        reparsed (brief/parse-state (brief/render-state state))]
    (is (= state reparsed))))

(deftest advance-state-test
  (let [state (brief/parse-state sample-state)
        advanced (brief/advance-state state "MU" "2026-09-10")]
    (is (= ["ORCL" "MSFT"] (:queue advanced)))
    (is (= ["MU" "2026-09-10"] (last (:covered advanced))))))

(deftest chunk-text-test
  (testing "short text is one chunk"
    (is (= ["hello world"] (brief/chunk-text "hello world" 100))))
  (testing "splits on paragraph boundaries, never exceeding max"
    (let [paras (map #(str "para" % " " (str/join (repeat 30 "word"))) (range 5))
          text (str/join "\n\n" paras)
          chunks (brief/chunk-text text 200)]
      (is (every? #(<= (count %) 200) chunks))
      (is (= text (str/join "\n\n" chunks)))))
  (testing "a paragraph bigger than max is hard-split"
    (let [big (str/join (repeat 500 "x"))
          chunks (brief/chunk-text big 200)]
      (is (every? #(<= (count %) 200) chunks))
      (is (= big (str/join chunks)))))
  (testing "no empty chunks"
    (is (not-any? str/blank? (brief/chunk-text "a\n\n\n\nb" 100)))))
