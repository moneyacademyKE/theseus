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

(defn same-repo?
  "Do two full-names name the SAME GitHub repository? GitHub repo names are
   case-insensitive: `0xSneaks/SUSHILOOP` and `0xSneaks/sushiloop` are one
   repo (id 1242406598 both ways), and the API returns whichever spelling is
   canonical today — a rename changes it under us.

   Measured 2026-11-02, not hypothetical: this lane analyzed the UPPERCASE
   spelling on 2026-10-31, `seen.edn` held only that spelling, and today's
   search returned the lowercase canonical name — so `contains?` missed, the
   repo was re-fetched, re-marked-seen under a second spelling, and
   re-digested. `propose!`'s exact-heading dedupe missed it too and would
   have appended a second block for an already-reviewed repo. Identity is
   the repo, never the casing of its name.

   Scope, so this is not over-read: folding CASE is correct, not a heuristic
   — two names differing only by case are always one repo on GitHub. A
   genuine rename to a DIFFERENT spelling (`foo` -> `bar`) is not caught
   here; the durable identity is the repo id, which search already returns
   and this lane discards."
  [a b]
  (= (some-> a str str/lower-case) (some-> b str str/lower-case)))

(defn seen-row
  "One seen-ledger entry as a row. The ledger on disk has always been a vector of
   bare NAME strings and 177 of them still are, so a string is read as a name
   with no id rather than migrated: it dedupes by name exactly as before, and
   acquires an id the first time fetch! meets that repo again."
  [row]
  (if (string? row)
    {:name row :id nil}
    (select-keys row [:name :id])))

(defn- row-name [row] (str/lower-case (str (:name (seen-row row)))))

(defn load-seen-rows
  "The seen ledger as rows. Not-found is not zero: a missing file is an empty
   ledger, never an exception."
  []
  (let [path (seen-file)]
    (if (fs/regular-file? path)
      (mapv seen-row (edn/read-string (slurp (str path))))
      [])))

(defn load-seen
  "The names in the seen ledger, as a set — the receipt a human greps. Identity
   is decided by seen-identity, not by this."
  []
  (set (map :name (load-seen-rows))))

(defn- seen-keys
  "Case-folded names of the seen ledger, so membership is by repo rather than by
   the spelling it was recorded under."
  [rows]
  (set (map row-name rows)))

(defn- seen-ids
  "The ids the ledger knows. A repo GitHub renamed keeps its id, so this is the
   half of identity a name cannot carry."
  [rows]
  (set (keep :id rows)))

(defn seen-identity
  "Is `item` (a normalized search row) a repo this lane already digested?

   Two questions, either of which settles it: the same ID, or the same name
   folded for case. Both are facts about GitHub rather than heuristics — an id
   is immutable and a repo name is case-insensitive — so this is not a
   similarity guess. A rename to a genuinely different spelling is caught by
   the id half, which is exactly what same-repo? alone could not do.

   Scope, so it is not over-read: an id GitHub never gave us (a legacy row, or
   an injected fixture without one) falls back to name identity and says
   nothing about renames. Not-found is not a negative answer."
  [item rows]
  (or (and (:id item) (contains? (seen-ids rows) (:id item)))
      (contains? (seen-keys rows) (str/lower-case (str (:full-name item))))))

(defn identity-verdict
  "seen-identity's answer as DATA about the answer, so a miss can say whether
   the ledger was able to decide. Returns {:status :seen-by-name|:seen-by-id|:fresh}
   plus :id-blind true only when a rename would have been undetectable.

   Why this exists — measured 2026-11-08, not hypothetical: vicfei/deepseek-bot
   IS vicfei/dsh-dot, refused 2026-11-01 at improvements.md:875. Three
   independent measurements this turn: GET /repos/vicfei/dsh-dot returns HTTP 301
   with location: /repositories/1410001857 (GitHub's own rename redirect to the
   immutable id); both READMEs are byte-identical at 13718 B with sha1 prefix
   e172e25bfcce; created_at is 2026-10-08T08:38:27Z on today's API row and on the
   11-01 ledger line. seen.edn holds {:name \"vicfei/dsh-dot\" :id nil} beside
   {:name \"vicfei/deepseek-bot\" :id 1410001857} — two rows, no shared key, so
   seen-identity's id half had nothing to match and its name half cannot see a
   rename to a different spelling (the scope same-repo?'s own docstring defers to
   the id). The answer was a bare false: correct, and uninformatively so. A false
   over a ledger that could not decide reads as a measured no — the
   not-found-reported-as-zero shape this lane keeps meeting (aden-hive/hive,
   mechanized 2026-10-14; ledger-mentions 10-14; jq-on-an-error-object 10-17;
   a README 403 read as a 404 on 11-05).

   :id-blind is deliberately narrow: it fires only when the item carries an id,
   no row matched, and at least one row has a nil id — the exact shape where a
   rename is undetectable. It is an annotation and never a verdict; the status is
   still :fresh, because inferring a rename from name similarity is the heuristic
   refusal-classes.md forbids and same-repo?'s docstring explicitly declines."
  [item rows]
  (let [by-name (contains? (seen-keys rows) (str/lower-case (str (:full-name item))))
        by-id (boolean (and (:id item) (contains? (seen-ids rows) (:id item))))]
    ;; :id-blind is ALWAYS present as a boolean, never an absent key: an absent
    ;; key and a false value read the same to a caller, and "the ledger decided
    ;; this is fresh" vs "the ledger could not decide" is the whole point.
    {:status (cond by-name :seen-by-name by-id :seen-by-id :else :fresh)
     :id-blind (boolean (and (not by-name) (not by-id) (:id item)
                             (some #(nil? (:id %)) rows)))}))

(defn seen-coverage
  "The seen ledger's own denominator: how many rows CAN settle a rename and how
   many cannot. {:rows n :with-id n :id-blind n}.

   Measured live 2026-11-08: 177 of 221 rows carry :id nil, so the rename gate
   landed 2026-11-02 can fire only on the recent 44 rows — 20% of this lane's
   history is rename-blind, and vicfei/dsh-dot sits in the blind 80%. A coverage
   number printed beside a gate is what stops the gate reading as armed when it
   is not (zeph's pattern-rot FATAL, 10-19; armory's PASS — 28555 valid over
   eval_score: null, 10-20). An empty ledger is a measured zero, not a missing
   measurement."
  [rows]
  {:rows (count rows)
   :with-id (count (keep :id rows))
   :id-blind (count (remove :id rows))})

(defn ledger-id-coverage
  "The propose-side denominator: of the ledger's repo blocks — a ## heading whose
   body carries a `- url:` line, so dated narrative headings are excluded because
   the gate never reads them — how many carry the `- id:` line that block-id and
   ledger-ids match on. {:repo-blocks n :with-id n :without-id n}.

   Measured live 2026-11-08: 87 `- url:` repo lines and 0 `- id:` lines, so
   ledger-ids returned the empty set and propose!'s rename gate — landed
   2026-11-03 for exactly the second-brain -> second-brain-os case — has never had
   an input to match against. A gate with an empty input reads as a gate that
   passed."
  [text]
  (let [blocks (->> (str/split (str text) #"(?m)^## ")
                    (drop 1)
                    (filter #(re-find #"(?m)^-\s*url:\s" %)))
        with (count (filter #(re-find #"(?m)^-\s*id:\s*\d+\b" %) blocks))]
    {:repo-blocks (count blocks)
     :with-id with
     :without-id (- (count blocks) with)}))

(defn id-gate-unarmed?
  "True when the ledger holds repo blocks and NONE carries an id — the shape
   where propose!'s rename gate exists, runs, and can never fire. Reported rather
   than thrown: a gate with no input is a coverage fact about the corpus, and
   refusing today's real proposals over yesterday's missing history would strand
   the lane (the claudemy refusal we refused on 2026-10-14 — a guard that rejects
   truthful work is worse than a report that names the gap). An empty ledger is
   not an unarmed gate; it is no gate to arm."
  [text]
  (let [c (ledger-id-coverage text)]
    (and (pos? (:repo-blocks c)) (zero? (:with-id c)))))

(defn id-gate-report
  "Coverage of propose!'s rename gate, read from the live ledger.
   {:repo-blocks n :with-id n :without-id n :id-gate-armed bool}.

   A separate call rather than keys on propose!'s return, because two
   pre-existing tests pin that return exactly — one of them with the message
   `the non-empty return shape is unchanged` — and adding keys there would have
   meant editing another test's assertion to accommodate this one. The
   2026-10-16 `verified-empty?` change set the precedent: keep the old shape
   byte-identical, and add a key only when it carries news. So propose! reports
   `:id-gate-armed false` ONLY when the gate was unarmed, and this is how you ask
   in full.

   A missing ledger is {:repo-blocks 0 ...} with :id-gate-armed false — no gate
   to arm, never a manufactured pass."
  []
  (let [p (fs/path (config/home) "brain" "improvements.md")
        text (when (fs/regular-file? p) (slurp (str p)))
        c (ledger-id-coverage text)]
    ;; Armed means the gate HAS input: at least one repo block carries an id.
    ;; Deriving this from `(not (id-gate-unarmed? text))` reported armed=true for
    ;; a ledger that does not exist at all — 0 repo blocks is not an armed gate,
    ;; it is no gate, and not-found must not read as a pass. Caught by the
    ;; missing-ledger test on 2026-11-08, the same day it shipped.
    (assoc c :id-gate-armed (pos? (:with-id c)))))



(defn- merge-seen
  "Existing rows + today's finds, one row per repo. A name-only row that
   resurfaces with an id gains it, so the ledger learns identity from contact
   rather than from a migration script, and the name on disk stays the one the
   API returned when the repo was first digested.

   Scope, stated because 2026-11-08 is the day it cost something: backfill is
   keyed by NAME, so a repo GitHub RENAMED never teaches the ledger its old row's
   id — the old row keeps :id nil forever and the new name arrives as a stranger.
   Repairing that by crawling the 177 nil-id rows would spend 177 calls against a
   60/hour unauthenticated budget (three hours of this lane's whole allowance)
   re-fetching repos already refused, so coverage is REPORTED (seen-coverage)
   instead of repaired. Not building the crawl is the decision, not an
   oversight."
  [rows found]
  (let [by-name (into {} (map (juxt row-name identity) rows))]
    (reduce (fn [acc item]
              (let [k (str/lower-case (str (:full-name item)))
                    prev (get by-name k)
                    row {:name (or (:name prev) (:full-name item))
                         :id (or (:id item) (:id prev))}]
                (conj (if prev (filterv #(not= (row-name %) k) acc) acc) row)))
            (vec rows)
            found)))

(defn- save-seen! [rows]
  (let [path (seen-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str (vec (sort-by row-name rows))))))

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
             :pushed-at (:pushed_at i)
             ;; Substance signals the search payload already carries and this
             ;; ns used to discard. `size` is deliberately NOT kept: on
             ;; 2026-10-17 it reported 0 for five synthetic repos whose trees
             ;; held real files, so printing it would invite the inference it
             ;; cannot support.
             :created-at (:created_at i)
             :fork (:fork i)
             ;; The repo id is the durable identity: GitHub renames repos and
             ;; serves the new full_name for the same id, so a name-keyed
             ;; seen-ledger re-digests a repo it already reviewed. Measured
             ;; twice on 2026-11-02 — `0xSneaks/SUSHILOOP` -> `sushiloop`
             ;; (case only, which same-repo? now folds) and
             ;; `iAlirezaaa/second-brain` -> `second-brain-os` (id 1435733061
             ;; both ways, which no case folding can see). One field the
             ;; search payload already carried and this ns threw away.
             :id (:id i)
             :language (:language i)})
          (or (:items res) []))))

(defn readme-result
  "One repo's README as DATA about the read, not just the bytes:
     {:status :ok :text s}                    — read
     {:status :absent :detail \"404 ...\"}     — measured absence
     {:status :unreadable :detail \"403 ...\"} — the read was refused or failed

   Why a third state exists — measured defect, reproduced 2026-11-05:
   `bb rsi learn fetch` printed `readme: ?` and `(no readme)` for BOTH of the
   day's repos (Abhijithreddydasari/ask-Y, munitkala/selfsemester) while
   raw.githubusercontent serves them 200 at 25035 and 10238 bytes. The cause is
   that GitHub's `/readme` endpoint was answering HTTP 403 `API rate limit
   exceeded` (core remaining=0, reset 2026-11-05T07:15:15Z), and fetch-readme's
   single `when 2xx` branch made a 403 indistinguishable from a 404. The
   consequence is already in the corpus: the 2026-11-03 ledger block for
   mianadilofficial/social-agent-suite records **no README at all** — it has
   one, 13792 bytes, sha1 prefix cb1fa5dff941. Not-found reported as zero,
   fifth occurrence in this lane and the first that wrote a false claim into
   brain/. An unreadable README is an unknown, and unknown must never render as
   a measured absence (aden-hive/hive, mechanized 2026-10-14).

   Scope, so the third state is not over-read: :unreadable names a failed READ,
   not a repo problem. It says nothing about whether the README exists.

   `http-get` is injectable, so this network boundary is testable; a transport
   exception is caught and becomes :unreadable with the message, because a
   timeout is a refusal to answer, not an answer."
  ([full-name] (readme-result full-name http/get))
  ([full-name http-get]
   (let [url (str "https://api.github.com/repos/" full-name "/readme")]
     (try
       (let [{:keys [status body]}
             (http-get url
                       {:headers {"accept" "application/vnd.github.raw+json"
                                  "user-agent" user-agent}
                        :throw false
                        :timeout 15000})]
         (cond
           (and (integer? status) (<= 200 status 299))
           {:status :ok :text (str body)}

           (= 404 status)
           {:status :absent :detail "404 — GitHub reports no README"}

           :else
           {:status :unreadable
            :detail (str (or status "no status") " — "
                         (-> (str body) (subs 0 (min 160 (count (str body)))))
                         (when (= 403 status)
                           " (rate limit or auth, not an absent README)"))}))
       (catch Exception e
         {:status :unreadable :detail (str "threw: " (ex-message e))})))))

(defn fetch-readme
  "Raw README of one repo, in whole, or nil — the text-or-nil contract its
   callers and its own 2026-10-18 test pin. readme-result is the richer reader
   that keeps the reason; this is the projection of it onto text.

   Excerpting is the digest renderer's job, not the reader's — measured defect
   this reverses (2026-10-18): the 4000-char excerpt was what
   readme-fingerprint hashed, so a digest row printed `2c2296ba6b34` for a
   README whose shasum prefix is `cc3301cafcf1`, and one campaign carried two
   different fingerprints across two days.

   Whole bytes make a row reproducible with one command, which is the point:
     curl -sH 'accept: application/vnd.github.raw+json' \\\\
       https://api.github.com/repos/<full-name>/readme | shasum | cut -c1-12

   `http-get` is injectable so this network boundary is testable at all; the
   default is the real client."
  ([full-name] (fetch-readme full-name http/get))
  ([full-name http-get]
   (:text (readme-result full-name http-get))))

(defn- truncate-readme
  "The excerpt embedded in a digest: enough to judge an idea, not enough to ship
   the corpus. Cut on a line boundary at max-readme-chars. Applied at render time
   so nothing downstream of a README — the fingerprint above all — sees a
   partial one."
  [s]
  (if (<= (count s) max-readme-chars)
    s
    (subs s 0 (min max-readme-chars
                   (inc (or (str/last-index-of s "\n" max-readme-chars)
                            max-readme-chars))))))

(defn- dedupe-by
  "First-wins dedupe on a key fn — babashka has no distinct-by."
  [kf coll]
  (map first (vals (group-by kf coll))))

(defn- field
  "Render one already-fetched payload value, with `?` for absent — a missing
   value must never print as a measured `false` or `0`. Not-found is not zero
   (aden-hive/hive, mechanized in knowledge.clj 2026-10-14), applied to the
   digest row itself."
  [v]
  (if (nil? v) "?" (str v)))

(defn readme-fingerprint
  "First 12 hex chars of the SHA-1 of a README's WHOLE text — the same bytes
   `curl -sH 'accept: application/vnd.github.raw+json' .../readme | shasum`
   sees, so a digest row is reproducible by hand; nil when there is no README. Pure function over bytes fetch! already holds — no extra API call,
   which matters because the unauthenticated budget is 60/hour and reading
   five repos' trees by hand exhausted it on 2026-10-17.

   Scope, stated so the number is not over-read: two repos sharing a
   fingerprint is a fact. What it means — a fork, a mirror, or one generator
   wearing four accounts — stays a judgment for the turn. refusal-classes.md
   forbids scoring or auto-tagging repos, and this ns's contract is data, not
   verdicts."
  [readme]
  (when (and (string? readme) (seq (str/trim readme)))
    (let [md (java.security.MessageDigest/getInstance "SHA-1")
          bs (.digest md (.getBytes ^String readme "UTF-8"))]
      (subs (apply str (map #(format "%02x" (bit-and % 0xff)) bs)) 0 12))))

(defn duplicate-readme-groups
  "repos -> [[fingerprint [full-name ...]] ...] for fingerprints shared by two
   or more repos, sorted so the digest is stable run to run.

   Report-only by construction: fetch! never drops a clone. Dropping one would
   keep it out of seen.edn and re-find it forever, and a shared README is not
   by itself a crime — a fork legitimately shares one with its parent, which is
   why the row also prints `fork:`."
  [repos]
  (->> repos
       (filter (comp some? :readme-fp))
       (group-by :readme-fp)
       (keep (fn [[fp rs]]
               (when (> (count rs) 1)
                 [fp (vec (sort (map :full-name rs)))])))
       (sort-by first)))

(defn- dup-section
  "The duplicate-README note, or an empty string when nothing is shared — a
   section that always prints is a section nobody reads."
  [repos]
  (let [groups (duplicate-readme-groups repos)]
    (if (empty? groups)
      ""
      (str "duplicate README fingerprints — same pitch, possibly one source;"
           " judgment stays in the turn:\n"
           (str/join "\n"
                     (map (fn [[fp names]] (str "- " fp " -> " (str/join ", " names)))
                          groups))
           "\n\n"))))

;; ---------- fetch + digest ----------

(defn- iso-day [^java.time.Instant t]
  (str (.truncatedTo t java.time.temporal.ChronoUnit/DAYS)))

(defn- readme-mark
  "The row's `readme:` field, rendered from the READ rather than from the bytes
   alone. `?` is gone, and with it the conflation: a fingerprint means read,
   `absent` means GitHub measured none, `unreadable` means the read was refused.

   Why three values — measured 2026-11-05: today's fetch printed `readme: ?` for
   both of its repos while raw.githubusercontent serves them 200 at 25035 and
   10238 bytes. GitHub's `/readme` was answering 403 `API rate limit exceeded`
   (core remaining=0, reset 07:15:15Z), and one `?` made a refused read look
   like a bare repo — which had already written a false claim into brain/: the
   2026-11-03 block for mianadilofficial/social-agent-suite records **no README
   at all**, and it has one, 13792 bytes. Not-found is not zero, and a 403 is
   not a 404."
  [{:keys [readme-fp readme-status]}]
  (case readme-status
    :absent "absent"
    :unreadable "unreadable"
    (field readme-fp)))

(defn- readme-excerpt
  "The fenced slot under a digest row. An unread README says so, names the
   refusal, and hands the turn the command that costs no API budget — because the
   one thing worse than a missing measurement is a missing measurement nobody
   knows how to get. raw.githubusercontent was serving 200 at the same moment the
   API was serving 403 (measured 2026-11-05)."
  [{:keys [full-name readme readme-status readme-detail]}]
  (case readme-status
    :absent "(no readme — GitHub reports none for this repo)"
    :unreadable
    (str "(README NOT READ — the fetch was refused, this is not a bare repo: "
         readme-detail ")\n"
         "Re-read it by hand, it costs no API budget:\n"
         "  curl -s https://raw.githubusercontent.com/" full-name "/HEAD/README.md")
    (truncate-readme readme)))

(defn- unread-section
  "The header warning, or an empty string when every README was read — a section
   that always prints is a section nobody reads (dup-section's rule)."
  [repos]
  (let [n (count (filter #(= :unreadable (:readme-status %)) repos))]
    (if (zero? n)
      ""
      (str "README(s) NOT READ: " n " row(s) below carry `readme: unreadable` —"
           " the GitHub API refused the read (rate limit or auth), so those"
           " fingerprints are missing rather than measured. An unread README is"
           " NOT evidence of a bare repo; raw.githubusercontent usually still"
           " serves it, and the row prints that command.\n\n"))))
(defn- digest-markdown [repos {:keys [since]}]
  (str "# RSI learn digest — " (java.time.Instant/now) "\n\n"
       "queries: " (pr-str (queries (config/load-config)))
       " | since: " since " | new: " (count repos) "\n\n"
       (when (seq repos) (str (unread-section repos) (dup-section repos)))
       (if (empty? repos)
         "nothing new under the topics since the window opened.\n"
         (str/join "\n\n"
                   (map (fn [{:as repo
                              :keys [full-name url description stars pushed-at id
                                     created-at fork language]}]
                          (str "## " full-name "\n"
                               "- url: " url " | stars: " stars " | pushed: " pushed-at "\n"
                               ;; The repo id rides the row because a name is
                               ;; not the repo: GitHub renames one and serves
                               ;; the new full_name for the SAME id. Measured
                               ;; 2026-11-03 — `iAlirezaaa/second-brain`
                               ;; (analyzed 2026-11-01) is `second-brain-os`
                               ;; today, id 1435733061 both ways, and the old
                               ;; name 301s to /repositories/1435733061.
                               ;; `search` already carried :id (2026-11-02) and
                               ;; this renderer threw it away — the same move
                               ;; as 2026-10-17's substance fields. Absent
                               ;; renders as `?`, never as a measured 0.
                               "- id: " (field id)
                               " | created: " (field created-at)
                               " | fork: " (field fork)
                               " | lang: " (field language)
                               " | readme: " (readme-mark repo) "\n"
                               "- " (or description "no description") "\n\n"
                               "```markdown\n" (readme-excerpt repo) "\n```"))
                        repos)))))

(defn- day-digest-has-blocks?
  "Does this digest already carry `## ` repo blocks? Text, not a claim: an empty
   or absent file is not a digest worth preserving."
  [p]
  (and (fs/exists? p) (boolean (re-find #"(?m)^## " (slurp (str p))))))

(defn- digest-path-for
  "Where today's digest goes. A same-day refetch is a CONTINUATION of the day,
   never a replacement for it: if `<day>.md` already carries blocks, this run
   writes `<day>-r2.md`, `-r3.md`, … and leaves the earlier file byte-identical.

   Measured loss this closes (2026-10-18): run 1 digested 6 repos and marked them
   seen; run 2 found the 7th, wrote `new: 1` over the same path, and the 6 repos'
   digest text was gone while seen.edn guaranteed they would never be found
   again. The day's analysis material is not recoverable from the search API, so
   overwriting it is the one irreversible move in this lane."
  [dir day]
  (let [base (fs/path dir (str day ".md"))]
    (if (day-digest-has-blocks? base)
      (first (for [n (range 2 1000)
                   :let [p (fs/path dir (str day "-r" n ".md"))]
                   :when (not (fs/exists? p))]
               p))
      base)))

(defn recommendations-for
  "The sibling that records a digest as analyzed: `<digest>.md` ->
   `<digest>-recommendations.md`, so `<day>-r2.md` -> `<day>-r2-recommendations.md`.
   One rule for both shapes, because guessing it is how a digest gets skipped."
  [digest]
  (fs/path (str (str/replace (str digest) #"\.md$" "") "-recommendations.md")))

(defn unanalyzed-digests
  "Digests in `dir` with no recommendations sibling, as sorted path strings: each
   one was fetched, its repos were written into seen.edn, and nobody analyzed it.

   Why this is a hole and not a curiosity — measured 2026-10-19: three fetches
   that day (08:52, 09:02, 09:33) wrote `<day>.md`, `<day>-r2.md` and
   `<day>-r3.md`, all 11 repos went into seen.edn at fetch time, and the standing
   recipe says 'analyze the latest digest on disk'. So the 6 repos in the first
   two files were marked seen and then dropped, and a repo in seen.edn is never
   fetched again — the lane loses it silently. Not-found reported as done, in
   this lane's own bookkeeping.

   `except` names the digest the current turn is about to analyze, which is not
   yet a hole."
  ([dir] (unanalyzed-digests dir nil))
  ([dir except]
   (let [name-of #(str (fs/file-name (fs/path %)))]
     (->> (fs/glob (fs/path dir) "*.md")
          (remove #(str/ends-with? (str %) "-recommendations.md"))
          (remove #(and except (= (name-of %) (name-of except))))
          (remove #(fs/exists? (recommendations-for %)))
          (mapv str)
          sort
          vec))))

(defn fetch!
  "One scout pass: search every configured query, drop what the seen
   ledger already digested, keep the freshest `limit`, attach READMEs,
   write state/rsi/learn/<day>.md — or <day>-rN.md when today's digest already
   has blocks, so a refetch never destroys an earlier run's finds. Dry-run skips
   the seen-ledger write so a probe can't starve tomorrow's scout of its own
   finds; it still writes a digest, which is why the no-clobber rule matters for
   probes too. Returns :preserved naming the file it refused to overwrite."
  [{:keys [now limit dry-run? search-fn readme-fn]
    :or {limit default-limit
         search-fn search
         ;; readme-result, not fetch-readme: the digest row must be able to say
         ;; WHY a fingerprint is missing — a refused read vs a measured absence.
         ;; fetch-readme's text-or-nil projection is exactly what made a 403
         ;; look like a 404 on 2026-11-03. A fixture returning a bare string is
         ;; still read as :ok, so no existing caller changes meaning.
         readme-fn readme-result}}]
  (let [now (or now (java.time.Instant/now))
        since (iso-day (.minus now (* 7 24 3600) java.time.temporal.ChronoUnit/SECONDS))
        seen (load-seen-rows)
        found (->> (for [q (queries (config/load-config))]
                     (search-fn {:query q :since since :limit 10}))
                   (apply concat)
                   ;; Within one pass the id is the better key too: two queries
                   ;; can return the same repo under two spellings.
                   (dedupe-by #(or (:id %) (str/lower-case (str (:full-name %)))))
                   (remove #(seen-identity % seen))
                   (sort-by :pushed-at #(compare %2 %1))
                   (take limit)
                   (mapv (fn [r]
                           (let [res (readme-fn (:full-name r))
                                 ;; One shape in, one shape out: readme-fn is
                                 ;; readme-result by default (data about the
                                 ;; read), but fetch-readme's text-or-nil
                                 ;; contract is still honoured — a bare string
                                 ;; is :ok and nil is :absent — so no existing
                                 ;; caller or test fixture changes meaning.
                                 {:keys [status text detail]}
                                 (if (map? res)
                                   res
                                   {:status (if (some? res) :ok :absent)
                                    :text res})]
                             (assoc r
                                    :readme text
                                    :readme-status status
                                    :readme-detail detail
                                    :readme-fp (readme-fingerprint text))))))
        day (iso-day now)
        dir (learning-dir)
        _ (fs/create-dirs dir)
        base (fs/path dir (str day ".md"))
        path (digest-path-for dir day)
        ;; Named AFTER the write, excluding the file just written: today's digest
        ;; is this turn's job, not a hole. See unanalyzed-digests.
        unanalyzed (unanalyzed-digests dir path)
        result (merge {:digest-path (str path)
                       :new (count found)
                       :already-seen (count seen)
                       ;; The denominator beside the gate, and the finds the
                       ;; ledger could NOT identify — a rename that slips through
                       ;; is then attributable to coverage rather than to luck.
                       ;; Both keys appear only when there is something to say:
                       ;; :id-blind-finds is omitted when empty, so a clean pass
                       ;; stays quiet and the two pre-existing fetch tests keep
                       ;; their exact return shape (no-regression-promotion).
                       :seen-coverage (seen-coverage seen)
                       :id-blind-finds (let [blind (mapv :full-name
                                                         (filter #(-> (identity-verdict % seen)
                                                                      :id-blind)
                                                                 found))]
                                         (when (seq blind) blind))}
                      (when (not= (str path) (str base))
                        {:preserved (str base)})
                      (when (seq unanalyzed)
                        {:unanalyzed unanalyzed}))]
    (spit (str path) (digest-markdown found {:since since}))
    (when-not dry-run? (save-seen! (merge-seen seen found)))
    result))

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

(def no-op-marker
  "The one line that makes a recommendations file a *verified* no-op instead
   of a malformed analysis. Written by the scout when fetch reports 0 new
   repos, recognized by propose! so that an empty day exits 0 with
   {:added 0 :skipped 0 :empty true} rather than throwing the same shape as
   the 2026-10-07/08 damage. Public so the writer and the reader of the
   marker cannot drift apart."
  "proposals: 0")

(defn- verified-empty?
  "Text -> true when it carries no proposal blocks AND states its own zero:
   either fetch's header line (`queries: ... | new: 0`) or a scout-written
   no-op marker. An explicit zero is a measurement; an unstated one is an
   unknown, and unknown still fails closed (2026-10-16, the not-found-is-not-
   zero rule applied to this lane's own exit code). Called only when there
   are no blocks to judge, so a contradictory marker can never suppress real
   work — content beats claim."
  [text]
  (boolean (or (re-find #"(?m)^queries:.*\|\s*new:\s*0\s*$" text)
               (re-find #"(?m)^proposals:\s*0\s*$" text))))

(defn- block-id
  "The GitHub repo id a block's own `- id: N` line carries, or nil. The id is
   the durable identity: a rename changes the name under a repo and leaves the
   id alone, so name-keyed dedupe files a SECOND block for a repo this lane
   already reviewed — permanently, because the repo is in seen.edn and is never
   fetched again. Measured 2026-11-03, not hypothetical: the ledger holds
   `## iAlirezaaa/second-brain` (2026-11-01) and today's digest serves
   `## iAlirezaaa/second-brain-os` for id 1435733061 both ways. Case folding
   (2026-11-02) cannot see a rename to a different spelling, which is exactly
   the scope `same-repo?`'s docstring names and defers to the id.

   nil — never 0 — for a block with no id line: every block already in the live
   ledger predates the `- id:` row, so name identity keeps working for them
   unchanged, and not-found stays not-found."
  [text]
  (some-> (re-find #"(?m)^-\s*id:\s*(\d+)\b" (str text)) second parse-long))

(defn- ledger-ids
  "Every repo id the ledger already carries, as a set of longs — membership by
   repo rather than by the spelling it was reviewed under."
  [text]
  (set (keep #(some-> % second parse-long)
             (re-seq #"(?m)^-\s*id:\s*(\d+)\b" (str text)))))

(defn propose!
  "Append the recommendation blocks of `path` to brain/improvements.md,
   skipping any whose '## ' heading is already present, and any whose repo id
   the ledger already carries — a rename of an already-reviewed repo is not a
   new proposal (block-id). Returns {:added n :skipped m}, plus
   :skipped-by-id ONLY when that gate fired, so a skip is attributable to the
   id rather than to the name. Fails closed two ways: a file with no blocks at
   all, and a file whose blocks carry no `- act: knowledge|code` line —
   that second shape is a fetch digest, not a gap analysis, and handing one
   here filed README headings as proposals (2026-10-07/08). Throw, name the
   offenders, append nothing.

   A third shape is not a failure: a file with no blocks that *states* its
   own zero (fetch's `| new: 0` header, or `no-op-marker`) returns
   {:added 0 :skipped 0 :empty true} and appends nothing. Before 2026-10-16
   that day exited 1 with the same message as a malformed file, so `nothing
   to propose` and `this is not an analysis` were indistinguishable — the
   conflation aden-hive/hive names as not-found-reported-as-zero."
  [path]
  (let [text (slurp path)
        blocks (block-headings text)
        unacted (->> blocks
                     (remove (fn [[_ body]]
                               (re-find #"(?mi)^-\s*act:\s*(knowledge|code)\s*$" body)))
                     (map key)
                     vec)]
    (when (and (empty? blocks) (not (verified-empty? text)))
      (throw (ex-info (str "no '## ' recommendation blocks in file, and the file"
                           " states no zero of its own (`" no-op-marker "` line, or"
                           " a fetch header ending `| new: 0`) — an unstated zero"
                           " is an unknown, and unknown fails closed")
                      {:path path})))
    (when (seq unacted)
      (throw (ex-info (str "recommendations file has " (count unacted)
                           " block(s) with no '- act: knowledge|code' line —"
                           " a fetch digest is not a gap analysis: "
                           (str/join ", " (take 5 unacted)))
                      {:path path :headings unacted})))
    (let [improvements (fs/path (config/home) "brain" "improvements.md")
          ;; Headings are compared case-folded: the ledger holds the spelling
          ;; canonical when the repo was reviewed, and GitHub may have renamed
          ;; it since (same-repo?). An exact-string comparison let one repo be
          ;; proposed twice — measured 2026-11-02.
          ledger-text (when (fs/regular-file? improvements)
                        (slurp (str improvements)))
          known (if ledger-text
                  (set (map (comp str/lower-case str/trim second)
                            (re-seq #"(?m)^## (.+)$" ledger-text)))
                  #{})
          known-ids (ledger-ids ledger-text)
          ;; Three outcomes in one pass, and the middle one is attributable:
          ;; :name is the pre-existing gate, :id is the rename gate, :fresh is
          ;; real work. group-by over a map keeps each group's order, so the
          ;; appended text is still in digest order.
          split (group-by
                 (fn [[heading body]]
                   (cond
                     (contains? known (str/lower-case (str heading))) :name
                     (contains? known-ids (block-id body)) :id
                     :else :fresh))
                 blocks)
          fresh (get split :fresh [])
          by-id (count (get split :id []))]
      (when (seq fresh)
        (fs/create-dirs (fs/parent improvements))
        (spit (str improvements)
              (str (if (fs/regular-file? improvements) "\n" "")
                   (str/join "\n\n" (map val fresh))
                   "\n")
              :append (fs/regular-file? improvements)))
      ;; :empty only when there was nothing to judge, so the pre-existing
      ;; {:added n :skipped m} shape stays byte-identical for real analyses
      ;; (no-regression-promotion: additive, never rewritten).
      (cond-> {:added (count fresh)
               :skipped (- (count blocks) (count fresh))}
        (empty? blocks) (assoc :empty true)
        ;; The key appears ONLY when the gate is unarmed — the same convention as
        ;; :skipped-by-id below, and the reason two pre-existing tests (whose
        ;; message is literally "the non-empty return shape is unchanged") stay
        ;; green instead of being weakened to tolerate a new key. An armed gate is
        ;; the normal case and needs no report; an unarmed one is the coverage fact
        ;; worth naming, because :skipped-by-id 0 then means nothing COULD match,
        ;; never checked-and-passed.
        (id-gate-unarmed? (str ledger-text))
        (assoc :id-gate-armed false
               :ledger-repo-blocks (:repo-blocks (ledger-id-coverage (str ledger-text))))
        ;; only when it fired, so the pre-existing return shape stays
        ;; byte-identical for a normal analysis (no-regression-promotion:
        ;; additive, never rewritten)
        (pos? by-id) (assoc :skipped-by-id by-id)))))
