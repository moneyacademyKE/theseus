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

(deftest recycle-oldest-test
  (let [state (brief/parse-state sample-state)
        drained (assoc state :queue [])]
    (testing "empty queue rotates the oldest-covered ticker back into play"
      (let [{:keys [ticker state]} (brief/recycle-oldest drained)]
        (is (= "AVGO" ticker))
        (is (nil? (some #(= "AVGO" (first %)) (:covered state))))
        (is (= ["NVDA" "2026-09-10"] (first (:covered state))))
        (testing "and the recycled ticker re-advances onto covered with a fresh date"
          (let [re-advanced (brief/advance-state state ticker "2026-09-25")]
            (is (= ["AVGO" "2026-09-25"] (last (:covered re-advanced))))
            (is (= 2 (count (:covered re-advanced))))))))
    (testing "nothing covered and nothing queued is nil, not a crash"
      (is (nil? (brief/recycle-oldest {:covered [] :queue []}))))))

;; ---------- rerun: new takes only ----------

(deftest latest-brief-file-test
  (let [files ["2026-09-10-AAPL.md" "2026-09-25-AAPL.md" "2026-09-10-NVDA.md" "prompt.md" "state.md"]]
    (testing "picks the newest dated brief for the ticker"
      (is (= "2026-09-25-AAPL.md" (brief/latest-brief-file files "AAPL"))))
    (testing "nil when the ticker was never covered"
      (is (nil? (brief/latest-brief-file files "ASML"))))))

(deftest build-prompt-first-run-test
  (let [prompt (brief/build-prompt "Tick: {{TICKER}} on {{DATE}}\nPrior: {{PRIOR_COVERAGE}}\n"
                                   "NVDA"
                                   {:close 1.0 :currency "USD"}
                                   ["- headline"]
                                   "2026-09-25"
                                   nil)]
    (testing "first coverage says so explicitly"
      (is (str/includes? prompt "first coverage of NVDA")))
    (testing "no placeholder survives"
      (is (not (str/includes? prompt "{{PRIOR_COVERAGE}}"))))))

(deftest build-prompt-rerun-test
  (let [prompt (brief/build-prompt "Tick: {{TICKER}}\nPrior: {{PRIOR_COVERAGE}}\n"
                                   "AAPL"
                                   {:close 1.0 :currency "USD"}
                                   []
                                   "2026-09-25"
                                   {:date "2026-09-10" :text "OLD TAKE: iPhone toll booth."})]
    (testing "rerun injects the previous brief verbatim"
      (is (str/includes? prompt "OLD TAKE: iPhone toll booth.")))
    (testing "rerun carries the new-takes-only rule with the prior date"
      (is (str/includes? prompt "2026-09-10"))
      (is (str/includes? prompt "FOLLOW-UP")))
    (testing "no placeholder survives"
      (is (not (str/includes? prompt "{{PRIOR_COVERAGE}}"))))))
