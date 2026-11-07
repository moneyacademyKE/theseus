(ns bb-agent.brief-universe-test
  "Live-wiring pins for the universe seam (2026-11-07): the exchangeInfo
   fetch, the never-serve-stale cache, and the queue-empty selection order."
  (:require [babashka.fs :as fs]
            [bb-agent.brief :as brief]
            [bb-agent.brief-universe :as universe]
            [clojure.test :as t]))

(def ^:private exchange-json
  "{\"symbols\":[{\"symbol\":\"NVDABUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"NVDAB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"TSLABUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"TSLAB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"TQQQBUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"TQQQB\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"1000CATUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"1000CAT\",\"quoteAsset\":\"USDT\"},
   {\"symbol\":\"NVDABBTC\",\"status\":\"TRADING\",\"baseAsset\":\"NVDAB\",\"quoteAsset\":\"BTC\"}]}")

(defn- respond [status body]
  (fn [_url _opts] {:status status :body body}))

(t/deftest fetch-universe-test
  (t/testing "one entry per tradable company — wrappers, digits and non-USDT pairs dropped"
    (with-redefs [babashka.http-client/get (respond 200 exchange-json)]
      (let [u (universe/fetch-universe)]
        (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u))))
        (t/is (pos? (:fetched-ms u))))))
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
      (with-redefs [babashka.http-client/get (respond 200 exchange-json)]
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
    (t/testing "stale cache refetches and rewrites"
      (with-redefs [babashka.http-client/get (respond 200 exchange-json)]
        (let [u (universe/load-universe path
                                        (+ (System/currentTimeMillis) stale 1000)
                                        stale)]
          (t/is (= ["NVDA" "TSLA"] (mapv :underlying (:tokens u)))))))))

(t/deftest select-ticker-test
  (let [queued {:queue ["AMD" "MU"] :covered [["NVDA" "2026-10-05"]]}
        empty-q {:queue [] :covered [["NVDA" "2026-11-07"] ["AMD" "2026-11-07"]]}
        u {:tokens [{:underlying "TSLA"} {:underlying "AMD"}] :fetched-ms 1}]
    (t/testing "a live queue wins — no universe consulted"
      (t/is (= {:ticker "AMD" :state queued} (brief/select-ticker queued nil))))
    (t/testing "an empty queue refills from the universe, skipping covered names"
      (let [{:keys [ticker state]} (brief/select-ticker empty-q u)]
        (t/is (= "TSLA" ticker))
        (t/is (= ["TSLA"] (:queue state)))
        (t/is (= (:covered empty-q) (:covered state)))))
    (t/testing "empty queue + empty universe falls back to recycling the oldest covered"
      (t/is (= "NVDA" (:ticker (brief/select-ticker empty-q {:tokens []})))))
    (t/testing "nothing anywhere is nil"
      (t/is (nil? (brief/select-ticker {:queue [] :covered []} nil))))))

(t/deftest validated-universe-test
  (let [u {:tokens [{:underlying "LLY"} {:underlying "SPY"} {:underlying "TR"} {:underlying "NVDA"}]
           :fetched-ms 1}]
    (with-redefs [bb-agent.brief/tv-fetch
                  (fn [sym _fields]
                    (condp = sym
                      "NASDAQ:LLY" {:type "stock"}
                      "NASDAQ:SPY" {:type "fund"}
                      "NASDAQ:NVDA" (throw (Exception. "covered names must not be probed"))
                      nil))]
      (let [v (brief/validated-universe u #{"NVDA"})]
        (t/testing "stocks kept with their type; funds and unknown symbols dropped"
          (t/is (= ["LLY"] (mapv :underlying (:tokens v))))
          (t/is (= "stock" (:type (first (:tokens v)))))
          (t/is (= 1 (:fetched-ms v)))))
      (t/testing "an empty universe passes through untouched"
        (t/is (nil? (brief/validated-universe nil #{"NVDA"})))))))
