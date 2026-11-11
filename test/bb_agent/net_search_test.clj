(ns bb-agent.net-search-test
  "web_search: Brave HTML parsing, entity decoding, internal-link
  filtering, query-relevance filtering, fail-closed transport, honest
  zeros, limit bounding, and the full executor wiring — with the HTTP
  seam redefed so no test ever leaves the machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bb-agent.net-search :as net-search]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]))

(def ^:private fixture
  ;; Shape captured from a live Brave response 2026-11-11 (minified to
  ;; the load-bearing markup): two organic results, one internal-only
  ;; link, one unrelated result the relevance filter must drop.
  (str "<div class=\"result-wrapper svelte-1rq4ngz\">"
       "<div class=\"result-content svelte-1rq4ngz\">"
       "<a href=\"https://book.babashka.org/\" target=\"_self\" class=\"svelte-14r20fy l1\">"
       "<div class=\"site-name-wrapper svelte-on1hvy\">"
       "<cite class=\"snippet-url desktop-small-regular\">book.babashka.org</cite></div>"
       "<div class=\"title search-snippet-title\" title=\"Babashka book\">Babashka book</div></a>"
       "<div class=\"generic-snippet svelte-1cwdgg3\">"
       "<div class=\"content desktop-default-regular\">Pods &amp; docs <em>available</em> for Babashka</div></div></div>"
       "<div class=\"result-wrapper svelte-1rq4ngz\">"
       "<div class=\"result-content svelte-1rq4ngz\">"
       "<a href=\"https://github.com/babashka/babashka\" target=\"_self\">"
       "<div class=\"title search-snippet-title\" title=\"GitHub - babashka\">GitHub - babashka</div></a>"
       "</div>"
       "<div class=\"result-wrapper\"><div class=\"result-content\">"
       "<a href=\"https://search.brave.com/videos?q=x\">Internal videos tab</a></div></div>"
       "<div class=\"result-wrapper\"><div class=\"result-content\">"
       "<a href=\"https://forum.example/whatsapp\"><div class=\"title search-snippet-title\""
       " title=\"Whatsapp web down\">Whatsapp web down</div></a></div>"))

(deftest parse-organic-results
  (let [results (net-search/parse-results fixture)]
    (is (= 3 (count results)) "internal links drop; unrelated waits for relevance")
    (is (= "https://book.babashka.org/" (:url (first results))))
    (is (= "Babashka book" (:title (first results))))
    (is (= "Pods & docs available for Babashka" (:snippet (first results)))
        "nested tags stripped, &amp; decoded")
    (is (= "book.babashka.org" (:domain (first results))))
    (is (nil? (:snippet (second results))) "no snippet survives as nil, not blank")))

(deftest relevance-filter-drops-unrelated-results
  (with-redefs [net-search/fetch (constantly {:status 200 :body fixture})]
    (let [{:keys [status results]} (net-search/search {:query "Babashka scripting documentation"})
          result-count (:count (net-search/search {:query "Babashka scripting documentation"}))]
      (is (= :ok status))
      (is (= 2 (count results)) "WhatsApp result cannot survive the query filter")
      (is (= 2 result-count))
      (is (every? #(str/includes? (str/lower-case (str (:title %) " " (:snippet %)))
                                  "babashka")
                  results)))))

(deftest parse-empty-body
  (is (= [] (net-search/parse-results "<html><body>challenge page</body></html>"))))

(deftest search-fails-closed-on-transport
  (with-redefs [net-search/fetch (constantly {:status 503 :body "unavailable"})]
    (let [{:keys [status executed?]} (net-search/search {:query "anything"})]
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
      (is (re-find #"no relevant results" output)))))

(deftest search-bounds-and-formats
  (with-redefs [net-search/fetch (constantly {:status 200 :body fixture})]
    (let [full (net-search/search {:query "babashka"})
          one (net-search/search {:query "babashka" :limit 1})]
      (is (= 2 (:count full)))
      (is (= 1 (:count one)))
      (is (re-find #"Babashka book — https://book\.babashka\.org/"
                   (:output full)) "output line: title — url"))
    (is (= 2 (:count (net-search/search {:query "babashka" :limit 99}))))))

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
                   :tool/args {:query "babashka" :limit "2"}})]
      (is (= :ok (:status result)))
      (is (true? (:executed? result)))
      (is (= 2 (:count result))))))
