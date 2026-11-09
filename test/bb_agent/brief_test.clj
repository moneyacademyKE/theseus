(ns bb-agent.brief-test
  (:require [bb-agent.brief :as brief]
            [bb-agent.brief-universe :as universe]
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

;; ---------- generation config (budget raised 2026-10-10, owner directive) ----------

(deftest generation-cfg-test
  (let [cfg (brief/generation-cfg {:provider :anthropic-compatible
                                   :model "some-default"
                                   :telegram {:token "x"}})]
    (testing "the brief lane rides the daemon default model"
      (is (= "some-default" (:model cfg)))
      (is (= :anthropic-compatible (:provider cfg))))
    (testing "long dossier timeout survives"
      (is (= brief/generation-timeout-ms (:timeout-ms cfg)))
      (is (> brief/generation-timeout-ms 600000)))
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

;; ---------- the timeout must reach the wire (2026-10-10: provider/config, not top level) ----------

(deftest generation-timeout-reaches-provider-test
  (let [base {:provider :openai-compatible
              :model "some-default"
              :providers {:openai-compatible {:base-url "https://x/v1"
                                               :timeout-ms 300000}
                          :other {:base-url "https://y/v1"}}
              :telegram {:token "x"}}
        cfg (brief/generation-cfg base)]
    (testing "the lane's budget lands in the provider config the wire actually reads"
      (is (= brief/generation-timeout-ms
             (get-in cfg [:providers :openai-compatible :timeout-ms])))
      (is (= brief/generation-timeout-ms (:timeout-ms cfg))))
    (testing "every configured provider gets the budget, not just the primary"
      (is (= brief/generation-timeout-ms (get-in cfg [:providers :other :timeout-ms]))))
    (testing "a raised budget does not silently multiply: one attempt per pass"
      (is (= 1 (get-in cfg [:retry :max-attempts]))))
    (testing "the lock cannot go stale while a run is still inside its budget"
      (is (>= (* brief/lock-stale-minutes 60 1000) brief/worst-case-generation-ms)))))

;; ---------- the lock releases on death, not on a guessed age (2026-10-10) ----------

(deftest lock-held-test
  (let [now 1000000000
        minute 60000
        stale (* 60 minute)]
    (testing "a live owner inside its budget holds the lane"
      (is (brief/lock-held? "pid=42 at=999999000" now stale #{42})))
    (testing "a dead pid releases at once — a killed run cannot skip the next fire"
      (is (not (brief/lock-held? "pid=42 at=999999000" now stale #{}))))
    (testing "a live-but-hung run is freed by the stale backstop"
      (is (not (brief/lock-held? "pid=42 at=1" now stale #{42}))))
    (testing "legacy bare-millis locks keep the age-only rule"
      (is (brief/lock-held? "999999000" now stale #{}))
      (is (not (brief/lock-held? "1" now stale #{}))))
    (testing "garbage never wedges the lane"
      (is (not (brief/lock-held? "" now stale #{})))
      (is (not (brief/lock-held? "nonsense" now stale #{}))))))

(deftest generation-budget-math-test
  (testing "the worst case is the per-pass budget times the passes, no hidden retries"
    (is (= (* brief/generation-timeout-ms (count brief/section-plan))
           brief/worst-case-generation-ms))))

;; ---------- Binance-only universe (2026-10-10 owner directive) ----------

(deftest binance-equity-tokens-test
  (let [symbols [{:symbol "NVDABUSDT" :status "TRADING" :baseAsset "NVDAB" :quoteAsset "USDT"}
                 {:symbol "TSLABUSDT" :status "TRADING" :baseAsset "TSLAB" :quoteAsset "USDT"}
                 {:symbol "TQQQBUSDT" :status "TRADING" :baseAsset "TQQQB" :quoteAsset "USDT"}
                 {:symbol "BTCUSDT" :status "TRADING" :baseAsset "BTC" :quoteAsset "USDT"}
                 {:symbol "NVDABUSDC" :status "TRADING" :baseAsset "NVDAB" :quoteAsset "USDC"}
                 {:symbol "AAPLBUSDT" :status "BREAK" :baseAsset "AAPLB" :quoteAsset "USDT"}
                 {:symbol "SOXLBUSDT" :status "TRADING" :baseAsset "SOXLB" :quoteAsset "USDT"}
                 {:symbol "BANANAS31USDT" :status "TRADING" :baseAsset "BANANAS31" :quoteAsset "USDT"}
                 {:symbol "1000CATUSDT" :status "TRADING" :baseAsset "1000CAT" :quoteAsset "USDT"}]
        u (universe/binance-equity-tokens symbols)]
    (testing "only TRADING USDT pairs whose base asset is an alpha token ending in B"
      (is (contains? (set (map :underlying u)) "NVDA"))
      (is (contains? (set (map :underlying u)) "TSLA"))
      (is (not (contains? (set (map :underlying u)) "BTC")))
      (is (not (contains? (set (map :underlying u)) "AAPL"))) ; BREAK, not trading
      (is (not (contains? (set (map :underlying u)) "BANANAS3"))) ; digits
      (is (not (contains? (set (map :underlying u)) "1000CA")))) ; leading digits
    (testing "the token keeps its Binance pair name so the report can cite it"
      (let [nvda (first (filter #(= "NVDA" (:underlying %)) u))]
        (is (= "NVDAB" (:token nvda)))
        (is (= "NVDABUSDT" (:pair nvda)))))
    (testing "leveraged/inverse fund wrappers are excluded — an ETF is not a company"
      (is (not (contains? (set (map :underlying u)) "TQQQ")))
      (is (not (contains? (set (map :underlying u)) "SOXL"))))
    (testing "deduplicated by underlying, one entry per company"
      (is (= (count u) (count (distinct (map :underlying u))))))))

(deftest researchable-test
  (testing "a company and its ADR are both researchable"
    (is (universe/researchable? {:type "stock" :name "NVIDIA Corporation"}))
    (is (universe/researchable? {:type "dr" :name "ASML Holding NV"})))
  (testing "a fund wrapper is not — an ETF has no cash flow statement"
    (is (not (universe/researchable? {:type "fund" :name "ProShares UltraPro QQQ"}))))
  (testing "nil never is"
    (is (not (universe/researchable? nil)))))

(deftest fresh-universe-test
  (let [now 1000000000000
        stale-ms (* 24 60 60 1000)]
    (testing "a young cache with names is authoritative"
      (is (universe/fresh? {:tokens [{:underlying "NVDA"}] :fetched-ms (- now 1000)}
                           now stale-ms)))
    (testing "an old cache is stale"
      (is (not (universe/fresh? {:tokens [{:underlying "NVDA"}] :fetched-ms (- now stale-ms 1)}
                                now stale-ms))))
    (testing "no names and no stamp are never fresh — an empty universe must rebuild"
      (is (not (universe/fresh? {:tokens [] :fetched-ms now} now stale-ms)))
      (is (not (universe/fresh? {:tokens [{:underlying "NVDA"}]} now stale-ms))))))

(deftest refill-queue-test
  (let [u {:tokens [{:underlying "NVDA"} {:underlying "TSLA"} {:underlying "AAPL"}]}
        state {:covered [["NVDA" "2026-10-05"] ["MSFT" "2026-10-06"]] :queue []}
        refilled (brief/refill-queue state u)]
    (testing "exhausted queue takes Binance-tradable names not already covered"
      (is (= ["TSLA" "AAPL"] (:queue refilled))))
    (testing "covered history is untouched — refilling does not rewrite the ledger"
      (is (= (:covered state) (:covered refilled))))
    (testing "an empty universe leaves the queue empty (recycle-oldest still handles it)"
      (is (= [] (:queue (brief/refill-queue state {:tokens []})))))
    (testing "a non-empty queue is not disturbed"
      (let [s {:covered [] :queue ["NVDA"]}]
        (is (= ["NVDA"] (:queue (brief/refill-queue s u))))))))

;; ---------- bounded prior coverage (2026-10-10: the prompt that ate the timeout) ----------

(deftest prior-excerpt-test
  (let [long-section (str/join " " (repeat 4000 "word"))
        report (str "---\ntitle: AAPL - Apple Deep Report\nrating: 🟢\ndate: 2026-10-04\n---\n\n"
                    "**Executive Summary**\n\nApple runs a toll booth. " long-section "\n\n"
                    "**The Stat Block** (+)\n\nPrice 336. " long-section "\n\n"
                    "**What They Sell and Who Buys** (+)\n\n" long-section "\n\n"
                    "**Valuation: Update the Model** (+)\n\nBear 250, base 380, bull 500. " long-section "\n\n"
                    "**Bottom Line and Verdict** (+)\n\nA better company, not a better buy. " long-section "\n\n"
                    "*Date: 2026-10-04 | Price: 336 USD | Sources: vendor | Not financial advice. DYOR.*\n")
        ex (brief/prior-excerpt report)]
    (testing "the verdict-bearing sections survive the cut"
      (is (str/includes? ex "Executive Summary"))
      (is (str/includes? ex "Stat Block"))
      (is (str/includes? ex "Valuation: Update the Model"))
      (is (str/includes? ex "Bottom Line and Verdict")))
    (testing "the frontmatter (rating, date) rides along so the follow-up can differ honestly"
      (is (str/includes? ex "rating: 🟢")))
    (testing "padding-only sections are dropped"
      (is (not (str/includes? ex "What They Sell and Who Buys"))))
    (testing "the excerpt is bounded — a 40KB prior brief cannot ride every pass"
      (is (> (count report) 60000))
      (is (<= (count ex) brief/prior-excerpt-max-chars))
      (is (< (count ex) (/ (count report) 3))))
    (testing "a short prior brief passes through whole"
      (let [short "---\nrating: 🟡\n---\n\n**Executive Summary**\n\nSmall.\n"]
        (is (str/includes? (brief/prior-excerpt short) "Small."))))
    (testing "the cut is announced, never silent"
      (is (str/includes? ex "[excerpted")))))
