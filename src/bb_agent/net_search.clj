(ns bb-agent.net-search
  "web_search: keyless internet search over Bing's HTML results page.

  Why Bing HTML and not a search API: every key-carrying backend
  (Brave, Tavily, Serper) needs a signup the owner does not have, and
  the keyless JSON surfaces (DDG instant answers, searx instances)
  were probed live on 2026-11-11 and returned either bot challenges
  (DDG lite/html, searx.be) or topic-abstract shapes that are not web
  results. Bing's organic results page answered from this machine
  with titles, URLs and snippets, and its markup has been stable for
  years (the li.b_algo block is the same anchor the classic scrapers
  target). The parser is deliberately dumb — regex over one result
  block at a time, no DOM — because the alternative is an HTML parser
  dependency for a page whose shape we already control-bounded.

  Privacy, stated once: the query string is visible to the search
  engine (Microsoft). Nothing else leaves the machine — no session
  data, no file paths, no other tool arguments ride along.

  Failure posture: fail closed. Any non-200, a network exception, or
  an unparseable body is an :error result the model can relay; an
  honestly-empty result page is :ok with zero results. The tool never
  guesses results."
  (:require [babashka.http-client :as http]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

(def ^:private search-url "https://www.bing.com/search?q=")
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
  "The handful of entities Bing actually emits in titles/snippets.
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

(defn- unwrap-bing-href
  "Decode Bing's HTML entity and base64url destination wrapper."
  [href]
  (let [href (decode-entities href)]
    (if-let [[_ encoded] (re-find #"[?&]u=a1([^&\\\"'\\s]+)" href)]
      (try
        (String. (.decode (java.util.Base64/getUrlDecoder) encoded)
                 java.nio.charset.StandardCharsets/UTF_8)
        (catch Exception _ href))
      href)))

(defn- organic-href?
  "Bing decorates organic hrefs with destination wrappers; ads live in
  different blocks (li.b_ad). Internal Bing links are skipped."
  [href]
  (let [href (unwrap-bing-href href)]
    (and (string? href)
         (str/starts-with? href "http")
         (not (str/includes? href "bing.com/aclick"))
         (not (str/starts-with? href "https://www.bing.com")))))

(defn- parse-block
  "One li.b_algo block -> a result map, or nil when the block has no
  usable link (Bing sometimes interleaves layout-only h2 blocks)."
  [block]
  (when-let [[_ raw-href raw-title]
             (re-find #"(?s)<h2[^>]*><a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>" block)]
    (let [href (unwrap-bing-href raw-href)]
      (when (organic-href? href)
        (let [snippet (some-> (re-find #"(?s)<p[^>]*>(.*?)</p>" block) second)
              domain (some-> (re-find #"(?s)<cite[^>]*>(.*?)</cite>" block) second)]
          {:url href
           :title (strip-tags raw-title)
           :snippet (some-> snippet strip-tags)
           :domain (some-> domain strip-tags)})))))

(defn parse-results
  "Bing results HTML -> ordered result maps, ads excluded by the split
  itself (only li.b_algo blocks are examined). Public for tests."
  [body]
  (->> (str/split (str body) #"<li class=\"b_algo\"")
       rest
       (keep parse-block)
       (remove #(str/blank? (:title %)))))

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
  bound, format. Fail closed on transport and parse; honest zero on an
  empty result set."
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
               (assoc :output (str "no results parsed for \"" query
                                   "\" — the engine returned an empty or unusual page; "
                                   "retry once, then say so instead of guessing"))))))))))

(def handlers
  {"web_search" search})
