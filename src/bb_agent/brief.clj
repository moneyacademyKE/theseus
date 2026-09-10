(ns bb-agent.brief
  "Hourly AI-stock brief — the Theseus-native pipeline (bk-02b7).

   One ticker per run, rotation state in brain/ai-stock-briefs/state.md.
   Facts are fetched deterministically (TradingView scanner + Google News
   RSS), the LLM only writes. Delivery goes through the durable outbox, so
   the Sly bot (@eileenslybot) is the sender — never OpenCrabs.

   Ordering: enqueue + confirmed drain FIRST, then state.md and the .md
   artifact. A crashed run therefore retries the ticker next hour instead
   of silently skipping it (at-least-once, same semantics as the outbox).

   Usage: bb brief [--dry-run]"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.outbox :as outbox]
            [bb-agent.telegram-delivery :as delivery]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private chat-id "-1003995594829") ; Sly Theseus group
(def ^:private thread-id 239)             ; ai-stock-briefs topic
(def ^:private max-chunk 3800)            ; Telegram limit is 4096
(def ^:private lock-stale-minutes 30)

(defn- brief-dir [] (str (config/home) "/brain/ai-stock-briefs"))
(defn- state-file [] (str (brief-dir) "/state.md"))
(defn- log-file [] (str (config/home) "/state/ai-brief/brief.log"))
(defn- lock-file [] (str (config/home) "/state/ai-brief/brief.lock"))

(defn- log! [msg]
  (fs/create-dirs (str (config/home) "/state/ai-brief"))
  (let [line (str (java.time.OffsetDateTime/now) " " msg)]
    (spit (log-file) (str line "\n") :append true)
    (println line)))

;; ---------- pure: rotation state ----------

(defn parse-state
  "Parse state.md into {:covered [[ticker date]...] :queue [ticker...]}."
  [text]
  (let [lines (str/split-lines text)
        queue-start (count (take-while #(not (str/starts-with? % "# Queue")) lines))
        covered (->> (take queue-start lines)
                     (keep #(re-find #"^-\s+([A-Z.]+)\s+\((\d{4}-\d{2}-\d{2})\)" %))
                     (mapv rest))
        queue (->> (drop (inc queue-start) lines)
                   (map str/trim)
                   (keep #(re-find #"^(?:-\s+)?([A-Z.]{1,6})$" %))
                   (mapv second))]
    {:covered covered :queue queue}))

(defn render-state [{:keys [covered queue]}]
  (str "# AI-tech-stock hourly brief — rotation state\n"
       "# Tickers already covered (do not repeat):\n"
       (str/join "\n" (map (fn [[t d]] (str "- " t " (" d ")")) covered))
       "\n\n# Queue (pick next unused, in rough rotation order):\n"
       (str/join "\n" queue)
       "\n"))

(defn next-ticker [{:keys [queue]}] (first queue))

(defn advance-state
  "Move ticker from queue to covered with today's date."
  [state ticker today]
  {:covered (conj (:covered state) [ticker today])
   :queue (vec (remove #(= ticker %) (:queue state)))})

;; ---------- pure: chunking ----------

(defn chunk-text
  "Split text into <= max-char messages on paragraph boundaries.
   A paragraph longer than max is hard-split. Never returns empty chunks."
  [text max-chars]
  (let [paras (str/split text #"\n\n")]
    (loop [ps paras, cur "", out []]
      (if (empty? ps)
        (cond-> out (not (str/blank? cur)) (conj cur))
        (let [p (first ps)
              cand (if (str/blank? cur) p (str cur "\n\n" p))]
          (cond
            (<= (count cand) max-chars)
            (recur (rest ps) cand out)

            ;; single paragraph too big: hard-split it
            (and (str/blank? cur) (> (count p) max-chars))
            (recur (cons (subs p max-chars) (rest ps))
                   (subs p 0 max-chars)
                   out)

            :else
            (recur ps "" (conj out cur))))))))

;; ---------- impure: data ----------

(defn- fetch-quote
  "TradingView scanner: price, currency, market cap, TTM P/E. Tries NASDAQ,
   NYSE, then the bare symbol. Returns a map or nil."
  [ticker]
  (let [fields "close,currency,market_cap_basic,price_earnings_ttm"]
    (some (fn [exch]
            (let [sym (if (str/blank? exch) ticker (str exch ":" ticker))
                  url (str "https://scanner.tradingview.com/symbol?symbol="
                           sym "&fields=" fields)]
              (try
                (let [{:keys [status body]} (http/get url {:throw false})
                      data (when (= 200 status)
                             (json/parse-string body true))]
                  (when (number? (:close data)) data))
                (catch Exception _ nil))))
          ["NASDAQ" "NYSE" ""])))

(defn- fetch-headlines
  "Google News RSS titles for the ticker, newest first, capped."
  [ticker]
  (try
    (let [url (str "https://news.google.com/rss/search?q="
                   (java.net.URLEncoder/encode (str ticker " stock") "UTF-8")
                   "%20when:7d&hl=en-US&gl=US&ceid=US:en")
          {:keys [status body]} (http/get url {:throw false})]
      (if (= 200 status)
        (->> (re-seq #"<title>([^<]+)</title>" body)
             (map second)
             (remove #(str/includes? % "Google News"))
             (take 8)
             (mapv #(str "- " %)))
        []))
    (catch Exception _ [])))

;; ---------- impure: generate + deliver ----------

(defn- build-prompt [template ticker quote headlines today]
  (-> template
      (str/replace "{{TICKER}}" ticker)
      (str/replace "{{DATE}}" today)
      (str/replace "{{PRICE}}" (str (:close quote)))
      (str/replace "{{CURRENCY}}" (or (:currency quote) "USD"))
      (str/replace "{{PE}}" (if (:price_earnings_ttm quote)
                              (format "%.2f" (double (:price_earnings_ttm quote)))
                              "n/a (negative or unavailable)"))
      (str/replace "{{MARKETCAP}}"
                   (if (:market_cap_basic quote)
                     (format "$%.1fB" (/ (double (:market_cap_basic quote)) 1e9))
                     "unavailable"))
      (str/replace "{{HEADLINES}}" (str/join "\n" headlines))))

(defn- deliver!
  "Enqueue every chunk to the durable outbox, then drain immediately so the
   message goes out now; the poller's per-cycle drain is the backstop.
   Returns the drain result map {:sent :deferred :dead :poison}."
  [telegram-cfg chunks]
  (doseq [chunk chunks]
    (outbox/enqueue!
     {:outbox-root (outbox/root-for telegram-cfg)}
     {:method "sendMessage"
      :params {:chat_id chat-id :message_thread_id thread-id :text chunk}
      :routing {:chat-id chat-id :thread-id thread-id}
      :kind :ai-stock-brief}))
  (outbox/drain!
   telegram-cfg
   (fn [record]
     (delivery/post-api telegram-cfg (:method record)
                        {:headers {"content-type" "application/json"}
                         :body (json/generate-string (:params record))}
                        (:routing record)
                        delivery/default-runtime))))

;; ---------- run ----------

(defn- with-lock [f]
  (fs/create-dirs (str (config/home) "/state/ai-brief"))
  (let [lock (fs/file (lock-file))]
    (if (and (fs/exists? lock)
             (< (- (System/currentTimeMillis)
                   (.toMillis (fs/last-modified-time lock)))
                (* lock-stale-minutes 60 1000)))
      (log! "SKIP brief: fresh lock held by another run.")
      (try
        (spit lock (str (System/currentTimeMillis)))
        (f)
        (finally (fs/delete-if-exists lock))))))

(defn run-brief! [{:keys [dry-run?]}]
  (let [state (parse-state (slurp (state-file)))
        ticker (next-ticker state)
        today (str (java.time.LocalDate/now))]
    (when (str/blank? ticker)
      (throw (ex-info "rotation queue is empty — refill state.md" {})))
    (log! (str "brief start: " ticker (when dry-run? " (dry-run)")))
    (let [quote (or (fetch-quote ticker)
                    (throw (ex-info (str "no quote for " ticker) {})))
          headlines (fetch-headlines ticker)
          template (slurp (str (brief-dir) "/prompt.md"))
          prompt (build-prompt template ticker quote headlines today)
          cfg (config/load-config)
          turn (core/run-turn! cfg prompt)
          text (str/trim (str (:assistant/final turn)))]
      (when-not (str/starts-with? text "---")
        (throw (ex-info "generation did not start with frontmatter — refusing to post"
                        {:ticker ticker :head (subs text 0 (min 120 (count text)))})))
      (let [chunks (chunk-text text max-chunk)]
        (log! (str "generated " (count text) " chars in " (count chunks) " chunk(s)"))
        (if dry-run?
          (do (doseq [c chunks] (println "--- chunk ---") (println c))
              (log! "dry-run: nothing sent, state untouched"))
          (let [{:keys [sent dead] :as drain}
                (deliver! (:telegram cfg) chunks)]
            (if (and (= sent (count chunks)) (zero? dead))
              (do
                (spit (str (brief-dir) "/" today "-" ticker ".md") text)
                (spit (state-file) (render-state (advance-state state ticker today)))
                (log! (str "brief posted: " ticker " (" sent " message(s))")))
              (throw (ex-info "drain incomplete — state NOT advanced, will retry"
                              (assoc drain :ticker ticker))))))))))

(defn -main [& args]
  (with-lock #(run-brief! {:dry-run? (some #{"--dry-run"} args)})))
