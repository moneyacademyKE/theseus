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
  (let [prompt (brief/build-prompt "Tick: {{TICKER}} on {{DATE}}\nPrior: {{PRIOR_COVERAGE}}\nFund: {{FUNDAMENTALS}}\n"
                                   "NVDA"
                                   {:close 1.0 :currency "USD"
                                    :total_revenue_ttm 8.91e10}
                                   ["- headline"]
                                   "2026-09-25"
                                   nil)]
    (testing "first coverage says so explicitly"
      (is (str/includes? prompt "first coverage of NVDA")))
    (testing "fundamentals rendered into the facts block"
      (is (str/includes? prompt "TTM revenue: $89.1B")))
    (testing "no placeholder survives"
      (is (not (str/includes? prompt "{{PRIOR_COVERAGE}}")))
      (is (not (str/includes? prompt "{{FUNDAMENTALS}}"))))))

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

;; ---------- deep-dossier: fundamentals facts block ----------

(deftest fundamentals-block-test
  (let [block (brief/fundamentals-block
               {:total_revenue_ttm 8.91e10
                :gross_margin_ttm 66.543
                :net_margin_ttm 42.944
                :earnings_per_share_diluted_ttm 7.8332
                :price_free_cash_flow_ttm 43.7576})]
    (testing "each non-null fundamental is a labeled line with a human unit"
      (is (str/includes? block "TTM revenue: $89.1B"))
      (is (str/includes? block "Gross margin: 66.5%"))
      (is (str/includes? block "Net margin: 42.9%"))
      (is (str/includes? block "Diluted EPS (TTM): 7.83"))
      (is (str/includes? block "P/FCF (TTM): 43.8"))))
  (testing "null and missing fields are omitted, never printed as filler"
    (let [partial (brief/fundamentals-block {:total_revenue_ttm 5e9 :gross_margin_ttm nil})]
      (is (str/includes? partial "TTM revenue: $5.0B"))
      (is (not (str/includes? partial "Gross margin"))))
    (is (str/blank? (brief/fundamentals-block {})))))

(deftest report-caption-test
  (testing "caption names ticker, date, and the file kind"
    (let [cap (brief/report-caption "AAPL" "2026-09-29")]
      (is (str/includes? cap "AAPL"))
      (is (str/includes? cap "2026-09-29"))
      (is (str/includes? cap ".md")))))

;; ---------- generation model (owner directive 2026-10-02) ----------

(deftest generation-cfg-test
  (let [cfg (brief/generation-cfg {:provider :anthropic-compatible
                                   :model "some-default"
                                   :telegram {:token "x"}})]
    (testing "the brief lane pins its own model, not the daemon's default"
      (is (= "ali/glm-5.3" (:model cfg)))
      (is (= :openai-compatible (:provider cfg))))
    (testing "long dossier timeout survives the override"
      (is (= 600000 (:timeout-ms cfg))))
    (testing "everything else passes through untouched"
      (is (= {:token "x"} (:telegram cfg))))))

;; ---------- sectioned dossier generation (2026-10-02: the long-completion death) ----------

(deftest section-plan-test
  (testing "three passes — one 5k-word completion times out, three short ones don't"
    (is (= 3 (count brief/section-plan))))
  (testing "the first pass opens with the frontmatter"
    (is (re-find #"(?i)frontmatter" (:ask (first brief/section-plan)))))
  (testing "the passes together cover every section of the report"
    (let [asks (str/join " " (map :ask brief/section-plan))]
      (doseq [s ["Executive Summary" "Stat Block" "What Changed Since the Last Look"
                 "What They Sell" "Weakness Section" "Growth Drivers"
                 "Valuation: Update the Model" "Bottom Line and Verdict"]]
        (is (str/includes? asks s) (str "no pass covers: " s))))))

(deftest generate-dossier-test
  (let [calls (atom [])
        runner (fn [cfg prompt]
                 (swap! calls conj {:cfg cfg :prompt prompt})
                 {:assistant/final (str "PART-" (count @calls))})
        out (brief/generate-dossier! runner
                                     {:model "m"}
                                     "Tick: {{TICKER}} Prior: {{PRIOR_COVERAGE}} Ask: {{SECTION_ASK}} Earlier: {{EARLIER_PASSES}}"
                                     "AMD"
                                     {:close 1.0 :currency "USD"}
                                     ["- headline"]
                                     "2026-10-02"
                                     {:date "2026-09-26" :text "OLD TAKE"})]
    (testing "one generation call per pass"
      (is (= 3 (count @calls))))
    (testing "each pass's ask lands in its own prompt"
      (is (str/includes? (:prompt (first @calls)) (:ask (first brief/section-plan))))
      (is (str/includes? (:prompt (second @calls)) (:ask (second brief/section-plan)))))
    (testing "earlier passes accumulate so the verdict stays coherent"
      (is (not (str/includes? (:prompt (first @calls)) "PART-")))
      (is (str/includes? (:prompt (second @calls)) "PART-1"))
      (is (str/includes? (:prompt (nth @calls 2)) "PART-1"))
      (is (str/includes? (:prompt (nth @calls 2)) "PART-2")))
    (testing "passes stitch in document order"
      (is (= "PART-1\n\nPART-2\n\nPART-3" out)))
    (testing "the cfg (pinned model, long timeout) rides every call"
      (is (every? #(= "m" (:model %)) (map :cfg @calls))))
    (testing "the verified facts and rerun context reach every pass"
      (is (every? #(str/includes? (:prompt %) "OLD TAKE") @calls))
      (is (every? #(str/includes? (:prompt %) "AMD") @calls)))))

(deftest build-prompt-section-slot-test
  (let [prompt (brief/build-prompt "T: {{TICKER}} | A: {{SECTION_ASK}} | E: {{EARLIER_PASSES}}"
                                   "NVDA" {:close 1.0 :currency "USD"} [] "2026-10-02" nil
                                   "emit ONLY the Stat Block" "EARLIER TEXT")]
    (testing "the 7-arity threads the pass instruction and earlier sections"
      (is (str/includes? prompt "emit ONLY the Stat Block"))
      (is (str/includes? prompt "EARLIER TEXT")))
    (testing "no placeholder survives"
      (is (not (str/includes? prompt "{{SECTION_ASK}}")))
      (is (not (str/includes? prompt "{{EARLIER_PASSES}}"))))))
