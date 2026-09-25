(ns bb-agent.rsi-learn
  "The self-learning scout: one leg of RSI that looks outward instead of
   at the usage ledger. A daily cron fetches the newest self-learning
   repos from GitHub, writes a digest an LLM turn can reason over, and
   files that turn's gap-analysis recommendations into
   brain/improvements.md behind a dedupe gate.

   Division of labor is deliberate: THIS namespace does data — search,
   dedupe, digests, ledger append. Judgment (which idea is worth
   stealing, through the Hickey lens) lives in the schedule prompt,
   where the brain context already carries the methodology. No keyword
   matching pretending to be analysis.

   The act boundary is named here so the prompt can't drift: a
   recommendation tagged :knowledge becomes a brain/knowledge/ page —
   cheap, index-notated, reversible. A :code recommendation waits for
   the owner. No pushes, no PRs, no source edits from a cron lane."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private user-agent "theseus-rsi-learn/1.0")
(def ^:private max-readme-chars 4000)
(def ^:private default-limit 6)

(def default-queries
  "Topic queries, ANDed with the freshness window by fetch!. Two topics
   beat one keyword salad: they're curated by humans and stay small."
  ["topic:self-learning" "topic:self-improving"])

(defn- learning-dir []
  (fs/path (config/home) "state" "rsi" "learn"))

(defn- seen-file []
  (fs/path (learning-dir) "seen.edn"))

(defn queries [cfg]
  (or (:rsi/learn-queries cfg) default-queries))

(defn load-seen []
  (let [path (seen-file)]
    (if (fs/regular-file? path)
      (set (edn/read-string (slurp (str path))))
      #{})))

(defn- save-seen! [full-names]
  (let [path (seen-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str (vec (sort full-names))))))

;; ---------- transport (injected in tests) ----------

(defn- get-json [url params]
  (let [{:keys [status body]}
        (http/get url {:query-params params
                       :headers {"accept" "application/vnd.github+json"
                                 "user-agent" user-agent}
                       :throw false
                       :timeout 15000})]
    (when-not (and (integer? status) (<= 200 status 299))
      (throw (ex-info "GitHub search failed" {:url url :status status})))
    (json/parse-string (str body) true)))

(defn search
  "One GitHub repository search page, normalized. `query` is a raw
   GitHub search expression; the freshness window is appended here so
   callers pass ideas, not date arithmetic."
  [{:keys [query since limit]}]
  (let [res (get-json "https://api.github.com/search/repositories"
                      {:q (str query " pushed:>=" since)
                       :sort "updated" :order "desc"
                       :per_page (or limit 10)})]
    (mapv (fn [i]
            {:full-name (:full_name i)
             :url (:html_url i)
             :description (:description i)
             :stars (:stargazers_count i)
             :pushed-at (:pushed_at i)})
          (or (:items res) []))))

(defn fetch-readme
  "Raw README of one repo, truncated at max-readme-chars on a line
   boundary — enough to judge an idea, not enough to ship the corpus."
  [full-name]
  (let [{:keys [status body]}
        (http/get (str "https://api.github.com/repos/" full-name "/readme")
                  {:headers {"accept" "application/vnd.github.raw+json"
                             "user-agent" user-agent}
                   :throw false
                   :timeout 15000})]
    (when (and (integer? status) (<= 200 status 299))
      (let [s (str body)]
        (if (<= (count s) max-readme-chars)
          s
          (subs s 0 (min max-readme-chars
                         (inc (or (str/last-index-of s "\n" max-readme-chars)
                                  max-readme-chars)))))))))

(defn- dedupe-by
  "First-wins dedupe on a key fn — babashka has no distinct-by."
  [kf coll]
  (map first (vals (group-by kf coll))))

;; ---------- fetch + digest ----------

(defn- iso-day [^java.time.Instant t]
  (str (.truncatedTo t java.time.temporal.ChronoUnit/DAYS)))

(defn- digest-markdown [repos {:keys [since]}]
  (str "# RSI learn digest — " (java.time.Instant/now) "\n\n"
       "queries: " (pr-str (queries (config/load-config)))
       " | since: " since " | new: " (count repos) "\n\n"
       (if (empty? repos)
         "nothing new under the topics since the window opened.\n"
         (str/join "\n\n"
                   (map (fn [{:keys [full-name url description stars pushed-at readme]}]
                          (str "## " full-name "\n"
                               "- url: " url " | stars: " stars " | pushed: " pushed-at "\n"
                               "- " (or description "no description") "\n\n"
                               "```markdown\n" (or readme "(no readme)") "\n```"))
                        repos)))))

(defn fetch!
  "One scout pass: search every configured query, drop what the seen
   ledger already digested, keep the freshest `limit`, attach READMEs,
   write state/rsi/learn/<day>.md. Dry-run skips the ledger write so a
   probe can't starve tomorrow's scout of its own finds."
  [{:keys [now limit dry-run? search-fn readme-fn]
    :or {limit default-limit
         search-fn search
         readme-fn fetch-readme}}]
  (let [now (or now (java.time.Instant/now))
        since (iso-day (.minus now (* 7 24 3600) java.time.temporal.ChronoUnit/SECONDS))
        seen (load-seen)
        found (->> (for [q (queries (config/load-config))]
                     (search-fn {:query q :since since :limit 10}))
                   (apply concat)
                   (dedupe-by :full-name)
                   (remove #(contains? seen (:full-name %)))
                   (sort-by :pushed-at #(compare %2 %1))
                   (take limit)
                   (mapv #(assoc % :readme (readme-fn (:full-name %)))))
        day (iso-day now)
        path (fs/path (learning-dir) (str day ".md"))]
    (fs/create-dirs (learning-dir))
    (spit (str path) (digest-markdown found {:since since}))
    (when-not dry-run?
      (save-seen! (into seen (map :full-name found))))
    {:digest-path (str path)
     :new (count found)
     :already-seen (count seen)}))

;; ---------- propose ----------

(defn- block-headings
  "Text -> map of heading -> block, split on '## ' lines. The heading
   is the dedupe identity: one proposal per repo, ever — a repo that
   was reviewed and refused must not nag tomorrow's cycle."
  [text]
  (let [blocks (->> (str/split text #"(?m)^## ")
                    (drop 1)
                    (map (fn [b]
                           (let [heading (str/trim (first (str/split-lines b)))]
                             [heading (str "## " (str/trimr b))]))))]
    (into {} blocks)))

(defn propose!
  "Append the recommendation blocks of `path` to brain/improvements.md,
   skipping any whose '## ' heading is already present. Returns
   {:added n :skipped m}. Refuses a file with no blocks — an empty or
   malformed recommendations file must fail loudly, not file silence."
  [path]
  (let [text (slurp path)
        blocks (block-headings text)]
    (when (empty? blocks)
      (throw (ex-info "no '## ' recommendation blocks in file" {:path path})))
    (let [improvements (fs/path (config/home) "brain" "improvements.md")
          known (if (fs/regular-file? improvements)
                  (set (map (comp str/trim second)
                            (re-seq #"(?m)^## (.+)$" (slurp (str improvements)))))
                  #{})
          fresh (remove (comp known key) blocks)]
      (when (seq fresh)
        (fs/create-dirs (fs/parent improvements))
        (spit (str improvements)
              (str (if (fs/regular-file? improvements) "\n" "")
                   (str/join "\n\n" (map val fresh))
                   "\n")
              :append (fs/regular-file? improvements)))
      {:added (count fresh)
       :skipped (- (count blocks) (count fresh))})))
