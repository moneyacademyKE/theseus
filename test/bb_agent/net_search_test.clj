(ns bb-agent.net-search-test
  "web_search: Bing HTML parsing (ads excluded, entities decoded,
  internal links skipped), fail-closed transport, honest zeros, limit
  bounding, and the full executor wiring — schema heal included — with
  the HTTP seam redefed so no test ever leaves the machine."
  (:require [clojure.test :refer [deftest is]]
            [bb-agent.net-search :as net-search]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]))

(def ^:private fixture
  ;; Shape captured from a live Bing response 2026-11-11 (minified to
  ;; the load-bearing markup): one ad block, one organic block with
  ;; nested tags + entity + cite, one internal-only block, one organic
  ;; without a cite.
  (str "<ul><li class=\"b_ad\"><h2><a href=\"https://ads.example/x\">AD RESULT</a></h2></li>"
       "<li class=\"b_algo\"><h2 class=\"b_topTitle\"><a h=\"ID=SERP,5\" "
       "href=\"https://example.com/page\">Example <strong>Page</strong> &amp; Docs</a></h2>"
       "<div class=\"b_caption\"><p class=\"b_lineclamp2\">The <em>best</em> page &#8212; truly.</p>"
       "<cite>https://example.com › page</cite></div></li>"
       "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/more\">MORE internal</a></h2></li>"
       "<li class=\"b_algo\"><h2><a href=\"https://second.org/doc\">Second doc</a></h2>"
       "<p>snippet only, no cite</p></li></ul>"))

(deftest parse-organic-results
  (let [results (net-search/parse-results fixture)]
    (is (= 2 (count results)) "ads and internal-only blocks drop out")
    (is (= "https://example.com/page" (:url (first results))))
    (is (= "Example Page & Docs" (:title (first results))) "nested tags stripped, &amp; decoded")
    (is (= "The best page — truly." (:snippet (first results))) "em stripped, numeric entity decoded")
    (is (= "https://second.org/doc" (:url (second results))))
    (is (nil? (:domain (second results))) "no cite survives as nil, not blank")))

(deftest parse-empty-body
  (is (= [] (net-search/parse-results "<html><body>challenge page</body></html>"))))

(deftest search-fails-closed-on-transport
  (with-redefs [net-search/fetch (constantly {:status 503 :body "unavailable"})]
    (let [{:keys [status executed?]} (net-search/search {:query "anything"})]
(deftest parse-bing-redirect-results
  (let [encoded (.encodeToString (java.util.Base64/getUrlEncoder)
                             (.getBytes "https://docs.example.org/guide"
                                        java.nio.charset.StandardCharsets/UTF_8))
        html (str "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/ck/a?!&amp;u=a1"
                  encoded "&amp;ntb=1\">Docs &amp; Guide</a></h2><p>Useful docs</p></li>")
        result (first (net-search/parse-results html))]
    (is (= "https://docs.example.org/guide" (:url result)))
    (is (= "Docs & Guide" (:title result)))))
      (is (= :error status))
      (is (true? executed?)))))

(deftest search-refuses-blank-query
  (let [{:keys [status executed?]} (net-search/search {:query "   "})]
    (is (= :error status))
    (is (false? executed?))))

(deftest search-honest-zero
  (with-redefs [net-search/fetch (constantly {:status 200 :body "<html>empty</html>"})]
    (let [{:keys [status results output]} (net-search/search {:query "obscure"})]
      (is (= :ok status))
      (is (= [] results))
      (is (re-find #"no results parsed" output)))))

(deftest search-bounds-and-formats
  (with-redefs [net-search/fetch (constantly {:status 200 :body fixture})]
    (let [full (net-search/search {:query "one"})
          one (net-search/search {:query "one" :limit 1})]
      (is (= 2 (:count full)) "default limit takes everything the page gave")
      (is (= 1 (:count one)) "limit 1 takes one")
      (is (re-find #"Example Page & Docs — https://example\.com/page"
                   (:output full)) "output line: title — url"))
    ;; over-limit asks are capped, never rejected
    (is (= 2 (:count (net-search/search {:query "one" :limit 99}))))))

(deftest registry-and-policy-wiring
  (let [defs (->> tools/definitions (map (fn [d] (get-in d [:function :name]))) set)]
    (is (contains? defs "web_search"))
    (is (= ["query"] (get-in (some #(when (= "web_search"
                                            (get-in % [:function :name])) %)
                                   tools/definitions)
                             [:function :parameters :required]))))
  (is (contains? common/safe-auto-tools "web_search")
      "read-only network GET rides with read_file/search, not with shell"))

(deftest executor-wiring-heals-and-dispatches
  (with-redefs [net-search/fetch (constantly {:status 200 :body fixture})]
    ;; limit arrives as the STRING "2" — the heal seam must turn it into
    ;; an integer before the handler bounds it.
    (let [result (tool/execute-tool-request
                  {:tool/name "web_search"
                   :tool/args {:query "wiring" :limit "2"}})]
      (is (= :ok (:status result)))
      (is (true? (:executed? result)))
      (is (= 2 (:count result))))))
