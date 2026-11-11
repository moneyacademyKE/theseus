(ns bb-agent.brief-universe-test
  "Live-wiring pins for the universe seam (2026-11-07): the exchangeInfo
   fetch, the never-serve-stale cache, and the queue-empty selection order."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.brief :as brief]
            [bb-agent.brief-universe :as universe]
            [cheshire.core :as json]
            [clojure.test :as t]))

(def ^:private exchange-json
  "{\"symbols\":[{\"symbol\":\"NVDABUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"NVDAB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"TSLABUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"TSLAB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"TQQQBUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"TQQQB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"BNBUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"BNB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"DGBUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"DGB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"1000CATUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"1000CAT\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"NVDABBTC\",\"status\":\"TRADING\",\"baseAsset\":\"NVDAB\",\"quoteAsset\":\"BTC\"}]}")

(def ^:private asset-json
  (json/generate-string {:code "000000" :success true
                         :data [{:assetCode "NVDAB" :trading true :tags ["bStocks"]}
                                {:assetCode "TSLAB" :trading true :tags ["bStocks"]}
                                {:assetCode "TQQQB" :trading true :tags ["bStocks"]}
                                {:assetCode "BNB" :trading true :tags ["BSC"]}
                                {:assetCode "DGB" :trading true :tags ["mining-zone"]}]}))

(defn- respond [status body]
  (fn [_url _opts] {:status status :body body}))

(defn- exchange-and-assets [url _opts]
  (cond (= url universe/exchange-info-url) {:status 200 :body exchange-json}
        (= url universe/asset-info-url) {:status 200 :body asset-json}
        :else {:status 503 :body "{}"}))

(defn- tv-fetcher [rows]
  (fn [sym _fields] (get rows sym)))

(def ^:private scanner-rows
  {"NASDAQ:LLY" {:type "stock" :sector "Health Technology"}
   "NASDAQ:SPY" {:type "fund"}
   "NASDAQ:NVDA" {:type "stock" :sector "Electronic Technology"}
   "NYSE:TR" {:type "stock" :sector "Consumer Non-Durables"}
   "NASDAQ:BN" {:type "stock" :sector "Finance"}
   "NASDAQ:DG" {:type "stock" :sector "Retail Trade"}})

(def ^:private tech-scanner-rows
  (assoc scanner-rows
         "NASDAQ:AMZN" {:type "stock" :sector "Retail Trade"}
         "NASDAQ:TSLA" {:type "stock" :sector "Consumer Durables"}))

(defn- validated-underlyings [u covered]
  (with-redefs [bb-agent.brief/tv-fetch (tv-fetcher (merge scanner-rows tech-scanner-rows))]
    (mapv :underlying (:tokens (brief/validated-universe u covered)))))

(defn- universe-of [tickers]
  {:tokens (mapv (fn [ticker] {:underlying ticker}) tickers) :fetched-ms 1})

(t/deftest fetch-universe-test
  (t/testing "one entry per tradable company — wrappers, digits and non-USDT pairs dropped"
    (with-redefs [http/get exchange-and-assets]
      (let [u (universe/fetch-universe)]
        (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u))))
        (t/is (pos? (:fetched-ms u))))))
  (t/testing "an unavailable or malformed asset registry fails closed"
    (doseq [bad [{:status 503 :body "{}"}
                 {:status 200 :body "{}"}
                 {:status 200 :body "not-json"}]]
      (with-redefs [http/get (fn [url opts]
                               (if (= url universe/asset-info-url)
                                 bad (exchange-and-assets url opts)))]
        (t/is (nil? (universe/fetch-universe))))))
  (t/testing "a dead or failing exchange is nil — never an exception, never a failed run"
    (with-redefs [babashka.http-client/get (respond 503 "{}")]
      (t/is (nil? (universe/fetch-universe))))
    (with-redefs [babashka.http-client/get (fn [_ _] (throw (Exception. "boom")))]
      (t/is (nil? (universe/fetch-universe))))))

(t/deftest load-universe-test
  (let [dir (fs/create-temp-dir {:prefix "universe-test"})
        path (str (fs/path dir "universe.edn"))
        stale 60000]
    (t/testing "no cache: fetches, writes, serves"
      (with-redefs [http/get exchange-and-assets]
        (let [u (universe/load-universe path (System/currentTimeMillis) stale)]
          (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u))))
          (t/is (fs/exists? path)))))
    (t/testing "a fresh cache serves without hitting the exchange"
      (with-redefs [babashka.http-client/get (fn [_ _] (throw (Exception. "must not fetch")))]
        (let [u (universe/load-universe path (System/currentTimeMillis) stale)]
          (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u)))))))
    (t/testing "stale cache + dead exchange is nil — a stale universe is never served"
      (with-redefs [babashka.http-client/get (respond 503 "{}")]
        (t/is (nil? (universe/load-universe path
                                            (+ (System/currentTimeMillis) stale 1000)
                                            stale)))))
    (t/testing "an unverified old cache cannot bypass bStocks verification"
      (spit path (pr-str {:tokens [{:underlying "BN" :token "BNB"}]
                          :fetched-ms (System/currentTimeMillis)}))
      (with-redefs [http/get (respond 503 "{}")]
        (t/is (nil? (universe/load-universe path (System/currentTimeMillis) stale)))))
    (t/testing "stale cache refetches and rewrites"
      (with-redefs [http/get exchange-and-assets]
        (let [u (universe/load-universe path
                                        (+ (System/currentTimeMillis) stale 1000)
                                        stale)]
          (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u)))))))))

(t/deftest select-ticker-test
  (let [queued {:queue ["AMD" "MU"] :covered [["NVDA" "2026-10-05"]]}
        empty-q {:queue [] :covered [["NVDA" "2026-11-07"] ["AMD" "2026-11-07"]]}
        u {:tokens [{:underlying "TSLA"} {:underlying "AMD"}] :fetched-ms 1}]
    (t/testing "a queued name wins only when the current screened universe verifies it"
      (t/is (= {:ticker "AMD" :state (assoc queued :queue ["AMD"])}
               (brief/select-ticker queued u))))
    (t/testing "an empty queue refills from the universe, skipping covered names"
      (let [{:keys [ticker state]} (brief/select-ticker empty-q u)]
        (t/is (= "TSLA" ticker))
        (t/is (= ["TSLA"] (:queue state)))
        (t/is (= (:covered empty-q) (:covered state)))))
    (t/testing "empty queue + empty screened universe fails closed"
      (t/is (nil? (:ticker (brief/select-ticker empty-q {:tokens []})))))
    (t/testing "nothing anywhere is nil"
      (t/is (nil? (brief/select-ticker {:queue [] :covered []} nil))))))

(t/deftest stale-queue-is-dropped-before-selection-test
  (let [state {:queue ["BN" "TSLA"]
               :covered [["NVDA" "2026-11-07"]]}
        screened {:tokens [{:underlying "TSLA"}] :fetched-ms 1}
        result (brief/select-ticker state screened)]
    (t/testing "a persisted queue entry outside the current screen cannot run"
      (t/is (= "TSLA" (:ticker result)))
      (t/is (= ["TSLA"] (:queue (:state result)))))))

(t/deftest recycle-only-screened-covered-names-test
  (let [state {:queue []
               :covered [["BN" "2026-11-01"] ["TSLA" "2026-11-02"]]}
        screened {:tokens [{:underlying "TSLA"}] :fetched-ms 1}
        result (brief/select-ticker state screened)]
    (t/testing "recycling skips historical names that are no longer screened"
      (t/is (= "TSLA" (:ticker result)))
      (t/is (= [["BN" "2026-11-01"]] (:covered (:state result)))))))

(t/deftest selection-fails-closed-without-screened-universe-test
  (t/testing "a queued ticker is not trusted when current verification is unavailable"
    (t/is (nil? (brief/select-ticker {:queue ["AMD"]
                                      :covered []}
                                     nil)))))

(t/deftest validated-universe-test
  (t/testing "non-tech sectors are rejected: BN, DG, TR, AMZN, TSLA, LLY"
    (t/is (= ["NVDA"] (validated-underlyings (universe-of ["BN" "DG" "TR" "AMZN" "TSLA" "LLY" "NVDA"]) #{}))))
  (t/testing "tech sectors accepted across exchanges"
    (with-redefs [bb-agent.brief/tv-fetch
                  (tv-fetcher (assoc tech-scanner-rows
                                     "NYSE:MSFT" {:type "stock" :sector "Technology Services"}
                                     "NYSE:GOOGL" {:type "stock" :sector "Communications"}))]
      (let [u {:tokens [{:underlying "NVDA"} {:underlying "MSFT"} {:underlying "GOOGL"}]
               :fetched-ms 1}
            v (brief/validated-universe u #{})]
        (t/is (= ["NVDA" "MSFT" "GOOGL"] (mapv :underlying (:tokens v))))
        (t/is (= ["Electronic Technology" "Technology Services" "Communications"]
                 (mapv :sector (:tokens v)))))))
  (t/testing "unknown sector is rejected"
    (with-redefs [bb-agent.brief/tv-fetch (fn [sym fields]
                                            (when-let [row ((tv-fetcher scanner-rows) sym fields)]
                                              (assoc row :sector nil)))]
      (t/is (= [] (mapv :underlying (:tokens (brief/validated-universe (universe-of ["NVDA"]) #{})))))))
  (t/testing "covered names are never probed"
    (with-redefs [bb-agent.brief/tv-fetch
                  (fn [sym _fields]
                    (when (= "NASDAQ:NVDA" sym)
                      (throw (Exception. "covered names must not be probed")))
                    (get scanner-rows sym))]
      (t/is (= [] (mapv :underlying (:tokens (brief/validated-universe (universe-of ["NVDA"]) #{"NVDA"})))))))
  (t/testing "funds remain dropped"
    (t/is (= [] (validated-underlyings (universe-of ["SPY"]) #{}))))
  (t/testing "empty universe passes through untouched"
    (t/is (nil? (brief/validated-universe nil #{})))))
