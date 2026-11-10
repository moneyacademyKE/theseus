(ns bb-agent.brief-universe
  "The Binance tradable-equity universe for the brief lane (2026-10-10 owner
   directive: Binance only). A tokenized equity lists as the ticker plus a
   trailing B (NVDAB for NVIDIA); this namespace turns the exchange symbol
   list into one entry per company a report can actually research, and owns
   venue prices: the equity quote is what the company is worth, the Binance
   price is what the reader can actually trade."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]))

;; ---------- Binance venue price ----------

(def ticker-url
  "24hr ticker for one symbol: last price, high/low, quote volume. This is
   the price the reader can actually trade at — the equity quote is what the
   company is worth, and the difference between the two is a fact."
  "https://api.binance.com/api/v3/ticker/24hr")

(defn fetch-token-price
  "Binance venue price for a tokenized equity, or nil. Pure-ish: one call,
   no retry — a missing venue price is an omitted line, never a failed run."
  [pair-symbol]
  (try
    (let [{:keys [status body]}
          (http/get (str ticker-url "?symbol=" pair-symbol)
                    {:throw false :timeout 15000})]
      (when (= 200 status)
        (let [t (json/parse-string body true)
              last (:lastPrice t)
              vol (:quoteVolume t)]
          (when last
            {:price (parse-double last)
             :currency "USDT"
             :change-pct-24h (some-> (:priceChangePercent t) parse-double)
             :quote-volume-24h (some-> vol parse-double)}))))
    (catch Exception _ nil)))

(defn token-for
  "The universe entry for an underlying ticker, or nil when Binance does not
   list it. Brief uses this to price the report in the reader's venue."
  [universe underlying]
  (first (filter #(= underlying (:underlying %)) (:tokens universe))))

;; ---------- universe selection ----------

(def fund-wrappers
  "Leveraged and inverse ETF tickers that also list as ticker-plus-B pairs.
   An ETF is not a company — there is no cash flow statement to research.
   Curated, not derived: extend when a new wrapper shows up."
  #{"TQQQ" "SQQQ" "SOXL" "SOXS" "SPXL" "SPXS" "UPRO" "SDOW" "UDOW"
    "LABU" "LABD" "NVDL" "NVDX" "TECL" "TMF" "DDM" "UMDD" "USD"})

(defn- tokenized-equity?
  "TRADING USDT pair whose base asset is letters-only ending in B — the
   tokenized-equity listing shape. Digits (1000CAT, BANANAS31), other quote
   assets, and halted pairs are all excluded."
  [{:keys [status baseAsset quoteAsset]}]
  (and (= "TRADING" status)
       (= "USDT" quoteAsset)
       (re-matches #"[A-Z]+B" (or baseAsset ""))))

(defn- underlying-of
  "NVDAB -> NVDA. Only meaningful on a tokenized-equity base asset."
  [baseAsset]
  (subs baseAsset 0 (dec (count baseAsset))))

(defn- dedupe-by
  "First entry per key wins — order preserved."
  [f xs]
  (:out (reduce (fn [acc x]
                  (let [k (f x)]
                    (if (contains? (:seen acc) k)
                      acc
                      {:seen (conj (:seen acc) k)
                       :out (conj (:out acc) x)})))
                {:seen #{} :out []}
                xs)))

(defn binance-equity-tokens
  "Binance exchangeInfo symbols -> one entry per tradable company:
   {:underlying NVDA, :token NVDAB, :pair NVDABUSDT}. TRADING USDT pairs
   with a letters-only ticker-plus-B base asset; fund wrappers dropped;
   first listing wins when a company has several pairs."
  [symbols]
  (->> symbols
       (filter tokenized-equity?)
       (remove #(contains? fund-wrappers (underlying-of (:baseAsset %))))
       (map (fn [{:keys [symbol baseAsset]}]
              {:underlying (underlying-of baseAsset)
               :token baseAsset
               :pair symbol}))
       (dedupe-by :underlying)))

(def tech-sectors
  "TradingView sectors treated as tech for the AI/tech-stock lane. Data from
   the scanner, not a hand-maintained ticker list. This is a broad sector
   screen, not a classification of each firm's AI activities or fundamentals."
  #{"Electronic Technology"
    "Technology Services"
    "Communications"})

(defn tech-sector?
  "Whether an entry belongs to the tech universe. Missing sector is false."
  [entry]
  (contains? tech-sectors (:sector entry)))

(defn researchable?
  "Companies and ADRs are researchable; fund wrappers and nil are not."
  [entry]
  (contains? #{"stock" "dr"} (:type entry)))

(defn fresh?
  "Is a cached universe still authoritative: names present, stamped, and no
   older than stale-ms. Anything else must rebuild, never serve stale."
  [universe now stale-ms]
  (boolean
   (and (seq (:tokens universe))
        (:fetched-ms universe)
        (<= (- now (:fetched-ms universe)) stale-ms))))

;; ---------- live universe ----------

(def exchange-info-url
  "Every symbol Binance lists."
  "https://api.binance.com/api/v3/exchangeInfo")

(def asset-info-url
  "Binance's public asset registry; bStocks are identified by the bStocks tag."
  "https://www.binance.com/bapi/asset/v2/public/asset/asset/get-all-asset")

(def ^:private verified-source :binance-bstocks-v1)

(defn fetch-universe
  "Intersect active spot pairs with Binance's bStocks-tagged asset registry.
   If either source is unavailable or malformed, return nil rather than
   treating an unrelated crypto symbol as a tokenized equity."
  []
  (try
    (let [market (http/get exchange-info-url {:throw false :timeout 15000})
          assets (http/get asset-info-url {:throw false :timeout 15000})]
      (when (and (= 200 (:status market)) (= 200 (:status assets)))
        (let [symbols (:symbols (json/parse-string (:body market) true))
              registry (json/parse-string (:body assets) true)
              records (:data registry)]
          (when (and (seq symbols) (= "000000" (:code registry))
                     (true? (:success registry)) (sequential? records) (seq records))
            (let [verified (into #{} (keep (fn [{:keys [assetCode tags trading]}]
                                           (when (and (string? assetCode)
                                                      (true? trading)
                                                      (some #{"bStocks"} tags))
                                             assetCode)))
                                 records)]
              (when (seq verified)
                {:tokens (filterv #(contains? verified (:token %))
                                  (binance-equity-tokens symbols))
                 :listing-source verified-source
                 :fetched-ms (System/currentTimeMillis)}))))))
    (catch Exception _ nil)))

(defn load-universe
  "The universe behind path: serve the cache while fresh, else refetch and
   rewrite it. Nil when the cache is stale AND the fetch fails — a stale
   universe is never served."
  [path now stale-ms]
  (or (try (let [cached (edn/read-string (slurp path))]
             (when (and (= verified-source (:listing-source cached))
                        (fresh? cached now stale-ms))
               cached))
           (catch Exception _ nil))
      (when-let [u (fetch-universe)]
        (try
          (fs/create-dirs (fs/parent path))
          (spit path (pr-str u))
          (catch Exception _ nil))
        u)))
