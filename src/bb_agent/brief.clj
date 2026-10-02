(ns bb-agent.brief
  "Hourly AI-stock brief — the Theseus-native pipeline (bk-02b7).

   One ticker per run, rotation state in brain/ai-stock-briefs/state.md.
   Facts are fetched deterministically (TradingView scanner + Google News
   RSS), the LLM only writes. Delivery goes through the durable outbox, so
   the Sly bot (@eileenslybot) is the sender — never OpenCrabs.

   Dossier mode (2026-09-28, owner directive): long deep reports delivered
   as .md document files — quote-level fundamentals ride the prompt so the
   writer has valuation-grade facts, the .md lands as a Telegram document
   with the Executive Summary as the in-thread message.

   Ordering: enqueue + confirmed drain FIRST, then state.md and the .md
   artifact. A crashed run therefore retries the ticker next hour instead
   of silently skipping it (at-least-once, same semantics as the outbox).

   Quote resilience (2026-10-01, owner: 'no quote for AMD'): TradingView
   rate-limits bursts — five watchdog-retried runs in an hour all failed
   the single attempt. fetch-quote now backs off and retries with jitter
   across exchanges before giving up.

   Generation timeout (2026-10-01): dossier prompts ask for 3,500–6,000
   words; the provider default of 60s cannot carry a long completion and
   dies mid-stream as 'request timed out'. The brief lane overrides
   :timeout-ms to 600s — a slow-but-alive generation beats a fast death.

   Generation model (2026-10-02, owner directive): the brief lane pins
   its own provider+model (`ali/glm-5.3` via :openai-compatible) instead
   of inheriting the daemon's default — the hourly lane's quality bar and
   latency budget are its own, and a default-model change elsewhere must
   not silently change what writes the dossiers.

   Failure visibility (2026-10-02): a run that dies after 'brief start'
   used to vanish — no log line, only a missing post. -main now logs the
   exception message and its data (the fallback chain's :fallback/tried
   ledger) before rethrowing, so the log tells the whole story.

   Usage: bb brief [--dry-run]"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.outbox :as outbox]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-upload :as upload]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private chat-id "-1003995594829") ; Sly Theseus group
(def ^:private thread-id 239)             ; ai-stock-briefs topic
(def ^:private max-chunk 3800)            ; Telegram limit is 4096
(def ^:private lock-stale-minutes 30)
(def ^:private generation-timeout-ms 600000) ; dossiers are long; 60s kills them mid-stream

(def generation-model
  "Owner-pinned model for the hourly brief lane (2026-10-02)."
  {:provider :openai-compatible
   :model "ali/glm-5.3"})

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
                     (keep #(re-find #"^- ([A-Z.]+) \((\d{4}-\d{2}-\d{2})\)" %))
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

(defn recycle-oldest
  "Queue exhausted: rotate the oldest-covered ticker back into play and drop
   it from covered so advance-state can re-file it with a fresh date. Nil when
   nothing has ever been covered — a genuinely empty universe stays an error."
  [{:keys [covered] :as state}]
  (let [[t _] (first covered)]
    (when t
      {:ticker t
       :state (update state :covered
                      #(into [] (remove (fn [[x _]] (= x t))) %))})))

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

(defn- tv-fetch
  "One TradingView scanner call for `fields`. Returns the parsed map or
   nil (non-200, unparseable, connection error — all just 'no data')."
  [sym fields]
  (try
    (let [url (str "https://scanner.tradingview.com/symbol?symbol="
                   sym "&fields=" fields)
          {:keys [status body]} (http/get url {:throw false})]
      (when (= 200 status)
        (json/parse-string body true)))
    (catch Exception _ nil)))

(defn- jitter-sleep! [ms]
  (Thread/sleep (+ ms (long (rand ms)))))

(defn fetch-quote
  "TradingView scanner: price, currency, market cap, TTM P/E. Tries NASDAQ,
   NYSE, then the bare symbol, with backoff-and-retry per exchange — the
   scanner rate-limits bursts, and a cron lane (plus its watchdog) is
   exactly a burst. Two attempts per exchange, ~1.5-3s between."
  [ticker]
  (let [fields "close,currency,market_cap_basic,price_earnings_ttm"]
    (some (fn [exch]
            (let [sym (if (str/blank? exch) ticker (str exch ":" ticker))]
              (or (tv-fetch sym fields)
                  (do (jitter-sleep! 1500)
                      (tv-fetch sym fields)))))
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

;; ---------- deep-dossier: fundamentals facts block ----------

(defn- fmt-billions
  "123456789 -> \"$12.3B\". Nil-safe."
  [n]
  (when n (format "$%.1fB" (/ (double n) 1e9))))

(def fundamentals-fields
  "TradingView scanner field -> renderer. Order is presentation order.
   Rendering is data, not a wall of conds."
  [[:total_revenue_ttm              #(str "TTM revenue: " (fmt-billions %))]
   [:gross_margin_ttm               #(str "Gross margin: " (format "%.1f%%" (double %)))]
   [:net_margin_ttm                 #(str "Net margin: " (format "%.1f%%" (double %)))]
   [:earnings_per_share_diluted_ttm #(str "Diluted EPS (TTM): " (format "%.2f" (double %)))]
   [:price_free_cash_flow_ttm       #(str "P/FCF (TTM): " (format "%.1f" (double %)))]
   [:revenue_growth_ttm             #(str "Revenue growth (TTM): " (format "%.1f%%" (* 100 (double %))))]
   [:price_book_ttm                 #(str "P/B (TTM): " (format "%.1f" (double %)))]
   [:total_debt_total_equity        #(str "Debt/equity: " (format "%.2f" (double %)))]])

(defn fundamentals-block
  "Labeled lines for the non-null fundamentals of a quote map. Null and
   missing fields are omitted — the writer sees facts or nothing."
  [quote]
  (->> fundamentals-fields
       (keep (fn [[k f]] (when (get quote k) (f (get quote k)))))
       (str/join "\n")))

(defn- fetch-fundamentals
  "Second TradingView call for the dossier-grade fields. Missing fields
   come back nil and are simply omitted from the prompt."
  [ticker]
  (let [fields "total_revenue_ttm,gross_margin_ttm,net_margin_ttm,earnings_per_share_diluted_ttm,price_free_cash_flow_ttm,revenue_growth_ttm,price_book_ttm,total_debt_total_equity"]
    (some (fn [exch]
            (let [sym (if (str/blank? exch) ticker (str exch ":" ticker))]
              (tv-fetch sym fields)))
          ["NASDAQ" "NYSE" ""])))

(defn report-caption
  "Document caption: names ticker, date, and file kind."
  [ticker date]
  (format "%s — deep report (%s), full .md attached" ticker date))

(defn- extract-summary
  "Executive Summary section of a report, for the in-thread message.
   Falls back to the report head when the section header is missing."
  [text]
  (let [start (str/index-of text "Executive Summary")]
    (if start
      (subs text start (min (count text) (+ start 1200)))
      (subs text 0 (min 1200 (count text))))))

(defn- deliver-document!
  "Dossier delivery: the full report as a Telegram document (the .md file,
   saved by the caller first), then the Executive Summary as an in-thread
   message. Returns a drain-map shape {:sent n :dead d} for the state gate."
  [telegram-cfg path ticker today summary-text]
  (let [doc (upload/send-file! telegram-cfg chat-id path
                               :document {:thread-id thread-id
                                          :caption (report-caption ticker today)})
        msg (delivery/post-api telegram-cfg "sendMessage"
                               {:headers {"content-type" "application/json"}
                                :body (json/generate-string
                                       {:chat_id chat-id
                                        :message_thread_id thread-id
                                        :text summary-text})}
                               {:chat-id chat-id :thread-id thread-id}
                               delivery/default-runtime)]
    (if (and (:message-id doc) (:message-id msg))
      {:sent 2 :dead 0}
      {:sent 0 :dead 2})))

;; ---------- impure: generate + deliver ----------

(defn latest-brief-file
  "Newest dated brief filename for ticker from a list of filenames, or nil
   if the ticker was never covered."
  [files ticker]
  (->> files
       (keep #(re-find (re-pattern (str "^\\d{4}-\\d{2}-\\d{2}-" ticker "\\.md$")) %))
       (sort)
       (last)))

(defn- prior-coverage-block
  "Reruns must earn their slot: the previous brief is injected verbatim and
   the writer is ordered to add only new material, never restate the old take."
  [ticker {:keys [date text] :as previous}]
  (if previous
    (format (str "RE-RUN RULE: %s was already covered on %s. The previous brief "
                 "is reproduced below in full. This hour you must produce a FOLLOW-UP, "
                 "not a rewrite:\n"
                 "- Open with what is NEW: developments since %s, fresh news, changed numbers, new catalysts.\n"
                 "- Bring NEW angles the prior brief did not examine (a different section lens, a competitor contrast, a historical episode). Do not retread it.\n"
                 "- Any section whose content would merely repeat the prior take must be replaced with new substance or omitted — repetition is failure.\n"
                 "- Same output format (frontmatter + all sections), same tone. Use the current verified numbers above, not the old ones.\n\n"
                 "PREVIOUS BRIEF (%s):\n%s")
            ticker date date date text)
    (format "This is the first coverage of %s — write a complete company analysis per the format below." ticker)))

(def section-plan
  "Three passes instead of one 3,500-6,000-word completion (2026-10-02):
   the provider times out on long generations, so the dossier is written in
   sections and stitched. Each pass sees the verified facts, the rerun rule,
   and the verbatim text of the passes before it — the verdict stays
   coherent while every request stays small."
  [{:ask (str "PASS 1 OF 3 — the opening sections of the report. Emit ONLY: the markdown "
              "frontmatter, **Executive Summary**, **The Stat Block**, **The Story Right Now**, and "
              "**What Changed Since the Last Look** (first coverage: retitle this last one **The Three Questions**). "
              "Start immediately with the `---` frontmatter. Stop when these sections are done — emit no later sections. "
              "Sections marked (+) in the format spec still get multiple substantive paragraphs with numbers woven in.")}
   {:ask (str "PASS 2 OF 3 — continue the SAME report. Emit ONLY: **What They Sell and Who Buys**, "
              "**How They Make Money and Revenue Quality**, **The Weakness Section**, "
              "**Capital Intensity and the Funding Model**, **Growth Drivers**, **Competitive Edge (The Moat)**, "
              "and **Industry Structure and Position**. No frontmatter, no code fences, no repetition of earlier sections. "
              "Every number you cite must come from the verified facts above or be marked as an estimate.")}
   {:ask (str "PASS 3 OF 3 — the closing sections of the SAME report. Emit ONLY: **Capital Allocation**, "
              "**Valuation: Update the Model** (bear/base/bull with explicit assumptions, then "
              "what-does-the-work and the skew), **Catalysts and Time Horizon**, and **Bottom Line and Verdict** "
              "ending with the exact disclaimer footer line. Stop at the footer — nothing after it. "
              "Your verdict, rating, and every number must stay consistent with the sections already written.")}])

(defn build-prompt
  "Fill the template. The 7-arity carries the sectioned-generation slots:
   which sections this pass emits, and the text of the passes before it."
  ([template ticker quote headlines today previous]
   (build-prompt template ticker quote headlines today previous nil nil))
  ([template ticker quote headlines today previous section-ask earlier-passes]
   (-> template
       (str/replace "{{SECTION_ASK}}"
                    (or section-ask "write the complete report now, every section, in one pass"))
       (str/replace "{{EARLIER_PASSES}}"
                    (if (str/blank? (str/trim (str earlier-passes)))
                      "(none yet — this pass opens the report)"
                      (str "TEXT ALREADY WRITTEN IN EARLIER PASSES — stay consistent with it, never repeat it:\n"
                           earlier-passes)))
       (str/replace "{{PRIOR_COVERAGE}}" (prior-coverage-block ticker previous))
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
       (str/replace "{{HEADLINES}}" (str/join "\n" headlines))
       (str/replace "{{FUNDAMENTALS}}" (fundamentals-block quote)))))

(defn generate-dossier!
  "Write the dossier in section-plan passes and stitch them in document
   order. Every pass gets the full template — facts, rerun rule, format —
   plus the verbatim text of the earlier passes, so the final verdict and
   the frontmatter agree. run-turn!* is injected for testability."
  [run-turn!* cfg template ticker quote headlines today previous]
  (let [pass-n (atom 0)
        step (fn [earlier {:keys [ask]}]
               (let [prompt (build-prompt template ticker quote headlines
                                          today previous ask earlier)
                     {:keys [assistant/final]} (run-turn!* cfg prompt)
                     part (str/trim (str final))]
                 (when (str/blank? part)
                   (throw (ex-info "dossier pass returned empty text"
                                   {:ticker ticker :pass ask})))
                 (log! (str "dossier pass " (swap! pass-n inc) "/3 done (" (count part) " chars)"))
                 (str/trim (str part))))
        parts (reduce (fn [acc pass]
                        (conj acc (step (str/join "\n\n" acc) pass)))
                      []
                      section-plan)]
    (str/join "\n\n" parts)))



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

(defn generation-cfg
  "The brief lane's own generation config: the owner-pinned model and the
   long dossier timeout, layered over whatever the daemon default is."
  [base]
  (assoc base
         :timeout-ms generation-timeout-ms
         :provider (:provider generation-model)
         :model (:model generation-model)))

(defn run-brief! [{:keys [dry-run?]}]
  (let [today (str (java.time.LocalDate/now))
        state (parse-state (slurp (state-file)))
        {:keys [ticker state]}
        (if-let [queued (next-ticker state)]
          {:ticker queued :state state}
          (or (recycle-oldest state)
              (throw (ex-info "rotation queue is empty — refill state.md" {}))))
        _ (when (str/blank? ticker)
            (throw (ex-info "rotation queue is empty — refill state.md" {})))
        _ (when-not (next-ticker state)
            (log! (str "queue exhausted — recycling oldest covered: " ticker)))
        _ (log! (str "brief start: " ticker
                     " model=" (:model generation-model)
                     (when dry-run? " (dry-run)")))
        quote (or (fetch-quote ticker)
                  (throw (ex-info (str "no quote for " ticker) {})))
        fundamentals (fetch-fundamentals ticker)
        headlines (fetch-headlines ticker)
        template (slurp (str (brief-dir) "/prompt.md"))
        previous (when-let [pf (latest-brief-file
                                (map (comp str fs/file-name)
                                     (fs/list-dir (brief-dir)))
                                ticker)]
                   (log! (str "rerun detected — prior brief: " pf))
                   {:date (subs pf 0 10)
                    :text (slurp (str (brief-dir) "/" pf))})
        fundamentals* (merge quote fundamentals)
        cfg (generation-cfg (config/load-config))
        text (generate-dossier! core/run-turn! cfg template ticker
                                fundamentals* headlines today previous)]
    (when-not (str/starts-with? text "---")
      (throw (ex-info "generation did not start with frontmatter — refusing to post"
                      {:ticker ticker :head (subs text 0 (min 120 (count text)))})))
    (let [path (str (brief-dir) "/" today "-" ticker ".md")
          summary (extract-summary text)]
      (log! (str "generated " (count text) " chars"))
      (if dry-run?
        (do (spit (str path ".draft") text)
            (log! (str "dry-run: draft at " path ".draft, nothing sent, state untouched")))
        (let [{:keys [sent dead]} (deliver-document! (:telegram cfg) path ticker today summary)]
          (if (and (pos? sent) (zero? dead))
            (do
              (spit path text)
              (spit (state-file) (render-state (advance-state state ticker today)))
              (log! (str "brief posted: " ticker " (document + summary message)")))
            (throw (ex-info "dossier delivery incomplete — state NOT advanced, will retry"
                            {:sent sent :dead dead :ticker ticker}))))))))

(defn -main [& args]
  (with-lock
    #(try
       (run-brief! {:dry-run? (some #{"--dry-run"} args)})
       (catch Exception e
         (log! (str "brief FAILED: " (ex-message e)
                    (when-let [d (ex-data e)] (str " " (pr-str d)))))
         (throw e)))))
