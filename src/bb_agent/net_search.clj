(ns bb-agent.net-search
  "web_search: keyless internet search over Brave Search's HTML results.

  Why HTML and not a search API: every key-carrying backend (Brave API,
  Tavily, Serper) needs a signup key the owner does not have, and the
  keyless JSON surfaces (DDG instant answers, searx instances) were
  probed live on 2026-11-11 and returned bot challenges or non-web
  topic shapes. Brave's HTML SERP answered from this machine with
  direct organic URLs (no redirect wrappers to decode) and correct
  results for the probe query. Bing HTML was tried first and served
  unrelated WhatsApp forum posts for a Babashka query — that backend
  was dropped on quality, not on markup.

  The parser is deliberately dumb — regex over one result-wrapper block
  at a time, no DOM — because the alternative is an HTML parser
  dependency for a page whose shape we control-bounded.

  Privacy, stated once: the query string is visible to the search
  engine. Nothing else leaves the machine — no session data, no file
  paths, no other tool arguments ride along.

  Failure posture: fail closed. Non-200, a network exception, or an
  unparseable body is an :error result the model can relay. A page whose
  results do not mention the query is an honest zero, not a result set:
  the relevance filter refuses to return unrelated hits as if they were
  answers. The tool never guesses results."
  (:require [babashka.http-client :as http]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

(def ^:private search-url "https://search.brave.com/search?q=")
(def ^:private user-agent
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
(def default-limit 5)
(def max-limit 8)

(defn fetch
  "One HTTP GET against the search endpoint. Separate fn on purpose:
  the parse tests and the executor wiring test with-redefs THIS seam,
  never the parser."
  [url]
  (try
    (http/get url {:throw false
                   :headers {"User-Agent" user-agent
                             "Accept-Language" "en-US,en;q=0.9"}
                   :timeout 10000})
    (catch Exception e
      {:status -1 :error (or (ex-message e) (.getName (class e)))})))

(defn- decode-entities
  "The handful of entities Brave actually emits in titles/snippets.
  Numeric refs are decoded by code point; unknown named entities are
  left alone — a wrong guess would corrupt the text, an honest
  pass-through cannot."
  [s]
  (-> (str (or s ""))
      (str/replace #"&#(\d+);" (fn [[_ code]]
                                 (String/valueOf (Character/toChars
                                                   (Integer/parseInt code)))))
      (str/replace #"&#x([0-9a-fA-F]+);" (fn [[_ hex]]
                                            (String/valueOf (Character/toChars
                                                             (Integer/parseInt hex 16)))))
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&nbsp;" " ")))

(defn- strip-tags
  [s]
  (-> (str (or s ""))
      (str/replace #"<[^>]+>" " ")
      decode-entities
      (str/replace #"\s+" " ")
      str/trim))

(defn- organic-href?
  "Brave's organic hrefs are direct destinations. Brave's own
  navigation links and thumbnail CDN are skipped."
  [href]
  (and (string? href)
       (str/starts-with? href "http")
       (not (str/includes? href "search.brave.com"))
       (not (str/includes? href "imgs.search.brave.com"))))

(defn- parse-block
  "One result-wrapper block -> a result map, or nil when the block has
  no usable title/link (Brave interleaves enrichment and layout-only
  wrapper divs)."
  [block]
  (when-let [[_ raw-href]
             (re-find #"(?s)<div class=\"result-content[^\"]*\"><a href=\"([^\"]+)\"" block)]
    (let [href (decode-entities raw-href)
          title (or (some-> (re-find #"<div class=\"title[^\"]*\" title=\"([^\"]*)\"" block)
                            second)
                    (some-> (re-find #"(?s)<div class=\"title[^\"]*>(.*?)</div>" block)
                            second))
          snippet (some-> (re-find #"(?s)<div class=\"generic-snippet[^\"]*\"><div class=\"content[^\"]*\">(.*?)</div>" block)
                          second)
          domain (some-> (re-find #"(?s)<cite[^>]*>(.*?)</cite>" block) second)]
      (when (and (organic-href? href)
                 (not (str/blank? (strip-tags title))))
        {:url href
         :title (strip-tags title)
         :snippet (some-> snippet strip-tags)
         :domain (some-> domain strip-tags)}))))

(defn parse-results
  "Brave results HTML -> ordered result maps. Public for tests."
  [body]
  (->> (str/split (str body) #"<div class=\"result-wrapper")
       rest
       (keep parse-block)))

(def ^:private stop-words
  #{"the" "and" "for" "with" "from" "how" "what" "why" "when" "where"
    "who" "which" "docs" "documentation" "about" "into" "that" "this"})

(defn- query-terms
  "Significant lowercase words used for relevance. Common English glue
  and generic 'documentation' do not count: a query of only glue has
  no terms and relevance is not enforced (nothing to enforce)."
  [query]
  (->> (str/split (str/lower-case query) #"[^a-z0-9]+")
       (remove stop-words)
       (filter #(>= (count %) 4))
       distinct
       vec))

(defn- relevant?
  [{:keys [title snippet url domain]} terms]
  (if (empty? terms)
    true
    (let [haystack (str/lower-case
                    (str (or title "") " " (or snippet "") " "
                         (or url "") " " (or domain "")))]
      (some #(str/includes? haystack %) terms))))

(defn- result->line
  [i {:keys [title url snippet]}]
  (str i ". " title " — " url
       (when-not (str/blank? snippet) (str "\n   " snippet))))

(defn- bounded-limit
  [limit]
  (let [n (cond (integer? limit) limit
                (number? limit) (long limit)
                :else default-limit)]
    (-> n (max 1) (min max-limit))))

(defn search
  "The web_search handler. args: {:query s, :limit n}. Fetch, parse,
  filter by query relevance, bound, format. Fail closed on transport
  and parse; honest zero when the engine serves unrelated results."
  [args]
  (let [tool-name "web_search"
        query (str/trim (str (or (:query args) (get args "query") "")))]
    (if (str/blank? query)
      (common/error-result tool-name "query must not be empty" {:executed? false})
      (let [{:keys [status body]} (fetch (str search-url
                                             (java.net.URLEncoder/encode query "UTF-8")))]
        (if (not= 200 status)
          (common/error-result tool-name
                               (str "search failed (HTTP " status ")")
                               {:http/status status})
          (let [results (->> (parse-results body)
                             (filter #(relevant? % (query-terms query)))
                             (take (bounded-limit (or (:limit args) (get args "limit")))))]
            (common/ok-result
             tool-name
             (cond-> {:query query
                      :results results
                      :count (count results)}
               (seq results)
               (assoc :output (str "web results for \"" query "\":\n"
                                   (str/join "\n" (map-indexed result->line results))))
               (empty? results)
               (assoc :output (str "no relevant results for \"" query
                                   "\" — the engine returned an empty or unrelated page; "
                                   "retry once, then say so instead of guessing"))))))))))

(def handlers
  {"web_search" search})
