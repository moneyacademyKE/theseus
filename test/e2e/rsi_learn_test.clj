(ns e2e.rsi-learn-test
  (:require [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [bb-agent.rsi-learn :as learn]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-home! []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "brain"))
    dir))

(def ^:private now (java.time.Instant/parse "2026-09-25T03:00:00Z"))

(defn- fake-item [n pushed]
  {:full-name (str "someone/repo-" n) :url (str "https://github.com/someone/repo-" n)
   :description (str "desc " n) :stars n :pushed-at pushed})

(defn- fake-search [items]
  (fn [{:keys [query]}]
    (case query
      "topic:self-learning" (vec items)
      [])))

(def ^:private fake-readme
  (fn [full-name] (str "readme of " full-name)))

(deftest fetch-writes-digest-and-remembers-seen
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [items [(fake-item 1 "2026-09-24T10:00:00Z")
                   (fake-item 2 "2026-09-23T10:00:00Z")]
            {:keys [digest-path new]}
            (learn/fetch! {:now now
                           :search-fn (fake-search items)
                           :readme-fn fake-readme})]
        (is (= 2 new))
        (is (str/includes? (slurp digest-path) "someone/repo-1")
            "the freshest repo is in the digest")
        (is (str/includes? (slurp digest-path) "readme of someone/repo-1"))
        ;; second pass: nothing new, seen ledger prevents re-digestion
        (let [{n2 :new} (learn/fetch! {:now (.plusSeconds now 60)
                                       :search-fn (fake-search items)
                                       :readme-fn fake-readme})]
          (is (zero? n2) "already-digested repos are not re-digested"))
        (is (= 2 (count (learn/load-seen)))
            "the seen ledger grew by exactly the new repos")))))

(deftest fetch-dry-run-leaves-the-ledger-open
  "A probe must not starve tomorrow's scout of its own finds: dry-run
   writes the digest but never closes the seen ledger."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (learn/fetch! {:now now
                     :dry-run? true
                     :search-fn (fake-search [(fake-item 1 "2026-09-24T10:00:00Z")])
                     :readme-fn fake-readme})
      (is (empty? (learn/load-seen))))))

(deftest fetch-honors-configured-queries
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (with-redefs [bb-agent.config/load-config
                    (constantly {:rsi/learn-queries ["topic:custom"]})]
        (let [called (atom [])
              {:keys [new]}
              (learn/fetch! {:now now
                             :search-fn (fn [{:keys [query]}]
                                          (swap! called conj query)
                                          [(fake-item 9 "2026-09-24T00:00:00Z")])
                             :readme-fn fake-readme})]
          (is (= ["topic:custom"] @called) "config overrides the default topics")
          (is (= 1 new)))))))

(deftest propose-appends-once-per-repo
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [recs (str "## someone/repo-1\n- steal: adopt its seen-ledger shape\n"
                      "- act: knowledge\n\n"
                      "## someone/repo-2\n- steal: none — incidental complexity\n"
                      "- act: code\n")
            recs-path (str (fs/path home "state" "recs.md"))]
        (fs/create-dirs (fs/parent recs-path))
        (spit recs-path recs)
        (let [{:keys [added skipped]} (learn/propose! recs-path)]
          (is (= 2 added))
          (is (zero? skipped)))
        (let [improvements (slurp (str (fs/path home "brain" "improvements.md")))]
          (is (str/includes? improvements "adopt its seen-ledger shape"))
          (is (str/includes? improvements "someone/repo-2")))
        ;; same file again: every block is known, nothing re-files
        (let [{:keys [added skipped]} (learn/propose! recs-path)]
          (is (zero? added) "a reviewed-and-refused proposal must not nag")
          (is (= 2 skipped)))))))

(deftest propose-refuses-a-blockless-file
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [path (str (fs/path home "state" "empty.md"))]
        (fs/create-dirs (fs/parent path))
        (spit path "just some prose, no headings")
        (is (thrown-with-msg? Exception #"no '## ' recommendation blocks"
                              (learn/propose! path)))))))

(deftest propose-refuses-a-raw-digest-and-appends-nothing
  "The 2026-10-07/08 mistake, re-run as a test: a fetch digest was handed to
   propose! and its README headings (## 📸 Screenshots, ## Supported Providers)
   were filed as proposals. A digest block carries no '- act: knowledge|code'
   line, so the guard must throw, name the offending headings, and leave
   brain/improvements.md byte-identical."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [digest (str "# RSI learn digest — 2026-10-10T00:00:00Z\n\n"
                        "## someone/repo-1\n- url: https://github.com/someone/repo-1 | stars: 0\n"
                        "- a description\n\n```markdown\n## 📸 Screenshots\nsome readme prose\n```\n")
            digest-path (str (fs/path home "state" "digest.md"))
            improvements (str (fs/path home "brain" "improvements.md"))]
        (fs/create-dirs (fs/parent digest-path))
        (spit digest-path digest)
        (spit improvements "pre-existing ledger content\n")
        (let [before (slurp improvements)]
          (is (thrown-with-msg? Exception #"no '- act: knowledge\|code' line"
                                (learn/propose! digest-path)))
          (is (= before (slurp improvements))
              "a refused file must leave the ledger byte-identical"))
        ;; a well-formed recommendations file from the same digest still lands
        (let [recs "## someone/repo-1\n- steal: none\n- lens: refuse\n- act: code\n"
              recs-path (str (fs/path home "state" "recs.md"))]
          (spit recs-path recs)
          (is (= {:added 1 :skipped 0} (learn/propose! recs-path)))
          (is (str/includes? (slurp improvements) "someone/repo-1")))))))

;; ---------- verified zero vs not-found (2026-10-16) ----------

(deftest propose-accepts-a-verified-empty-digest
  "Fetch reported `new: 0` on 2026-10-15 and again on 2026-10-16, so the
   scout had nothing to analyze and propose! threw `no '## ' recommendation
   blocks in file` — exit 1, the same shape as the 2026-10-07/08 damage
   where a raw digest was filed as proposals. A measured zero is not a
   not-found (aden-hive/hive). The digest states its own zero in fetch's own
   header line, so refusing it is refusing a true statement."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [digest (str "# RSI learn digest — 2026-10-16T06:01:52Z\n\n"
                        "queries: [\"topic:self-learning\"] | since: 2026-10-09T00:00:00Z | new: 0\n\n"
                        "nothing new under the topics since the window opened.\n")
            path (str (fs/path home "state" "digest.md"))
            improvements (str (fs/path home "brain" "improvements.md"))]
        (fs/create-dirs (fs/parent path))
        (spit path digest)
        (is (= {:added 0 :skipped 0 :empty true} (learn/propose! path))
            "an explicitly empty day is a measurement, not a malformed analysis")
        (is (not (fs/exists? improvements))
            "a verified empty files nothing — it does not even create a ledger")))))

(deftest propose-accepts-a-scout-no-op-analysis
  "The sibling receipt: an empty day still writes a -recommendations.md so the
   day on disk says `analyzed, nothing to propose` instead of leaving the next
   scout to guess whether the day was analyzed or merely unopened. The marker
   is a public constant so the writer and the reader cannot drift."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [path (str (fs/path home "state" "recs.md"))]
        (fs/create-dirs (fs/parent path))
        (spit path (str "# RSI learn recommendations — 2026-10-16T00:00:00Z\n\n"
                        learn/no-op-marker "\n\n"
                        "0 new repos fetched; no repo blocks to analyze.\n"))
        (is (= {:added 0 :skipped 0 :empty true} (learn/propose! path)))))))

(deftest propose-still-refuses-a-non-zero-digest
  "The relaxation is scoped to an explicit zero. A digest that did fetch repos
   and carries no `- act:` line is still the 2026-10-07/08 mistake, and the
   zero pattern must not match `new: 10` on its way to matching `new: 0`."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [digest (str "# RSI learn digest — 2026-10-16T06:01:52Z\n\n"
                        "queries: [\"topic:self-learning\"] | since: 2026-10-09T00:00:00Z | new: 10\n\n"
                        "## someone/repo-1\n- url: https://github.com/someone/repo-1 | stars: 3\n"
                        "- a description\n\n```markdown\n## 📸 Screenshots\nprose\n```\n")
            path (str (fs/path home "state" "digest.md"))
            improvements (str (fs/path home "brain" "improvements.md"))]
        (fs/create-dirs (fs/parent path))
        (spit path digest)
        (spit improvements "pre-existing ledger content\n")
        (let [before (slurp improvements)]
          (is (thrown-with-msg? Exception #"no '- act: knowledge\|code' line"
                                (learn/propose! path)))
          (is (= before (slurp improvements))
              "a refused non-zero digest still leaves the ledger byte-identical"))))))

(deftest propose-blocks-beat-a-contradictory-marker
  "Precedence is content over claim: a file that asserts `proposals: 0` while
   carrying a well-formed block files the block. The marker is consulted only
   when there is nothing else to judge, so it can never suppress real work —
   and `:empty` stays absent, keeping the return shape additive."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [path (str (fs/path home "state" "recs.md"))]
        (fs/create-dirs (fs/parent path))
        (spit path (str "# recommendations\n\n" learn/no-op-marker "\n\n"
                        "## someone/repo-1\n- steal: one small thing\n"
                        "- lens: take the bit, refuse the cathedral\n- act: knowledge\n"))
        (is (= {:added 1 :skipped 0} (learn/propose! path))
            "blocks win, and the non-empty return shape is unchanged")))))

;; ---------- substance signals in the digest (2026-10-17) ----------
;;
;; Five of six repos in the 2026-10-17 digest came from one generator: four
;; `tinyforge` accounts sharing a byte-identical README blob (e7c257f05e) whose
;; `src/core.py` and `tests/test_core.py` are the same 6-line `print("ok")` stub
;; plus shuffled filler comments, and a `claude-soul` re-host whose
;; `src/index.ts` is `// minimal stub`. The digest showed none of it: `search`
;; fetched created_at/fork/language and threw them away, and nothing named the
;; four identical READMEs. These tests pin the fix — data already in hand gets
;; printed, clones get named, nothing gets filtered.

(defn- full-item [n pushed extra]
  (merge {:full-name (str "someone/repo-" n)
          :url (str "https://github.com/someone/repo-" n)
          :description (str "desc " n) :stars n :pushed-at pushed
          ;; `n` doubles as the repo id on purpose: fake-search returns
          ;; normalized rows straight to fetch!, so anything the digest prints
          ;; must be supplied here. Measured 2026-11-03 - the fixture shipped
          ;; without :id and the test asserting "the durable identity is on the
          ;; row" failed with `- id: ? |`: a red test about a green
          ;; implementation. The gap was in the fixture.
          :id n}
         extra))

(deftest readme-fingerprint-is-stable-and-nil-safe
  "A pure sha1 prefix over text fetch! already holds — no extra API call.
   The golden values pin the algorithm (swap sha1 for md5 and this fails)."
  (is (= "d313696819a0" (learn/readme-fingerprint "readme of someone/repo-1")))
  (is (= (learn/readme-fingerprint "shared pitch")
         (learn/readme-fingerprint "shared pitch"))
      "same text, same fingerprint")
  (is (not= (learn/readme-fingerprint "shared pitch")
            (learn/readme-fingerprint "shared pitch."))
      "different text, different fingerprint")
  (is (nil? (learn/readme-fingerprint nil)) "no readme is not a fingerprint")
  (is (nil? (learn/readme-fingerprint "   \n ")) "a blank readme is not a fingerprint"))

(deftest digest-prints-the-substance-fields-it-already-fetched
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search
                                       [(full-item 1 "2026-09-24T10:00:00Z"
                                                   {:created-at "2026-09-24T09:58:00Z"
                                                    :fork false :language "Python"})])
                           :readme-fn fake-readme})
            d (slurp digest-path)]
        (is (str/includes? d "created: 2026-09-24T09:58:00Z") "repo age is visible")
        (is (str/includes? d "fork: false") "a fork explains a shared README at a glance")
        (is (str/includes? d "lang: Python"))
        (is (str/includes? d "readme: d313696819a0") "the fingerprint is on the row")))))

(deftest digest-renders-absent-fields-as-unknown-never-as-false
  "Not-found is not zero, inside the digest itself: a payload missing `fork`
   must print `?`, not `false`. `size` is deliberately absent from the row —
   it reported 0 for all five synthetic repos today while their trees held real
   files, so printing it would invite the same bad inference it gave me."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search [(fake-item 1 "2026-09-24T10:00:00Z")])
                           :readme-fn fake-readme})
            d (slurp digest-path)]
        (is (str/includes? d "created: ? | fork: ? | lang: ?"))
        (is (not (str/includes? d "fork: false"))
            "an absent fork flag is an unknown, not a measured false")
        (is (not (re-find #"size" d)) "the field that lied is not printed")))))

(deftest digest-names-duplicate-readmes-without-dropping-any-repo
  "Four identical READMEs must read as one pitch from four accounts, not as four
   finds. Report-only: every clone still appears in the digest AND still lands in
   seen.edn — filtering here would keep a clone un-seen and re-found forever."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [items [(full-item 1 "2026-09-24T10:00:00Z" {})
                   (full-item 2 "2026-09-24T09:00:00Z" {})]
            {:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search items)
                           :readme-fn (fn [_] "shared pitch")})
            d (slurp digest-path)]
        (is (str/includes? d "duplicate README fingerprints"))
        (is (str/includes? d "dcf0d052ebc7") "the shared fingerprint is named")
        (is (str/includes? d "someone/repo-1, someone/repo-2") "and so are its members")
        (is (str/includes? d "## someone/repo-1") "clone 1 still gets its own block")
        (is (str/includes? d "## someone/repo-2") "clone 2 still gets its own block")
        (is (= 2 (count (learn/load-seen))) "both are marked seen, so neither returns tomorrow")))))

(deftest digest-omits-the-duplicate-section-when-nothing-is-shared
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search [(fake-item 1 "2026-09-24T10:00:00Z")
                                                    (fake-item 2 "2026-09-23T10:00:00Z")])
                           :readme-fn fake-readme})]
        (is (not (str/includes? (slurp digest-path) "duplicate README fingerprints"))
            "a section that always prints is a section nobody reads")))))

(deftest digest-header-still-states-its-own-zero
  "No-regression on 2026-10-16: the new rows and the duplicate section must not
   move `| new: N` off the end of the header line, or `verified-empty?` stops
   matching and an empty day starts exiting 1 again."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now :search-fn (fake-search []) :readme-fn fake-readme})
            d (slurp digest-path)]
        (is (re-find #"(?m)^queries:.*\|\s*new:\s*0\s*$" d)
            "the header contract verified-empty? depends on is intact")
        (is (= {:added 0 :skipped 0 :empty true} (learn/propose! digest-path))
            "and an empty day is still a measurement, not a malformed analysis")))))

;; Red-first tests, 2026-10-18: two measured defects in the rsi-learn fetch lane.
;; Appended to test/e2e/rsi_learn_test.clj (snapshot: .bak-20261018).
;; Requires [babashka.process :refer [sh]] added to the ns form.

;; ---------- 2026-10-18: the fetch lane lost a day's digest, and hashed an excerpt ----------

(def ^:private long-readme
  (str "## a pitch longer than the digest excerpt\n"
       (str/join "\n" (map #(str "filler line %03d" %) (range 900)))))

(defn- shasum-12
  "Expected fingerprints come from the shell command a human would run, not from
   the code under test — an independent SHA-1 implementation, so a change in
   readme-fingerprint cannot make its own test pass."
  [s]
  (let [f (fs/create-temp-file)]
    (spit (str f) s)
    (-> (sh "shasum" (str f)) :out (str/split #"\s+") first (subs 0 12))))

(deftest fetch-readme-returns-the-whole-readme-so-the-row-is-reproducible
  "Measured defect: fetch-readme truncated to max-readme-chars BEFORE
   readme-fingerprint hashed, so the 2026-10-18 digest printed `2c2296ba6b34`
   for a README whose `shasum` prefix is `cc3301cafcf1` — and the 10-17 ledger
   carried the latter for the same bytes, so one campaign had two fingerprints
   across two days and a human could not reproduce the row with one command."
  []
  (let [http-get (fn [_url _opts] {:status 200 :body long-readme})]
    (is (> (count long-readme) 4000) "the fixture must cross the excerpt boundary")
    (is (= long-readme (learn/fetch-readme "someone/repo-1" http-get))
        "the reader returns whole bytes; the excerpt is the renderer's job")
    (is (= (shasum-12 long-readme)
           (learn/readme-fingerprint (learn/fetch-readme "someone/repo-1" http-get)))
        "a digest row's `readme:` must equal `curl … /readme | shasum | cut -c1-12`")
    (is (nil? (learn/fetch-readme "someone/repo-1"
                                  (fn [_ _] {:status 404 :body "nope"})))
        "a missing README is still nil, not an empty string")))

(deftest digest-excerpts-the-readme-but-fingerprints-the-whole
  "No-regression half: moving the truncation to render time must not ship the
   corpus inside the digest."
  []
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search [(fake-item 1 "2026-10-17T10:00:00Z")])
                           :readme-fn (fn [_] long-readme)})
            d (slurp digest-path)]
        (is (str/includes? d (str "readme: " (shasum-12 long-readme)))
            "the row carries the whole-text fingerprint")
        (is (not (str/includes? d "filler line 899"))
            "the embedded excerpt is still cut at the boundary")
        (is (< (count d) 6000) "and the digest stays small")))))

(deftest a-second-same-day-fetch-preserves-the-first-digest
  "Measured loss, 2026-10-18: run 1 digested 6 repos and marked them seen; run 2
   found the 7th, wrote `new: 1` over the same path, and the 6 repos' digest text
   was destroyed while seen.edn guaranteed they would never be found again.
   A second run is a continuation of the day, not a replacement for it."
  []
  (let [home (temp-home!)
        items [(fake-item 1 "2026-10-17T10:00:00Z")
               (fake-item 2 "2026-10-17T09:00:00Z")
               (fake-item 3 "2026-10-17T08:00:00Z")]]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [first-run (learn/fetch! {:now now
                                     :search-fn (fake-search (take 2 items))
                                     :readme-fn fake-readme})
            first-text (slurp (:digest-path first-run))
            second-run (learn/fetch! {:now now
                                      :search-fn (fake-search items)
                                      :readme-fn fake-readme})]
        (is (= 2 (:new first-run)) "run 1 digested both of its finds")
        (is (= 1 (:new second-run)) "run 2 sees only what run 1 left")
        (is (= first-text (slurp (:digest-path first-run)))
            "the earlier digest is byte-identical after the second run")
        (is (not= (:digest-path first-run) (:digest-path second-run))
            "the continuation wrote its own file")
        (is (= (:digest-path first-run) (:preserved second-run))
            "and names what it refused to overwrite")
        (is (str/includes? (slurp (:digest-path second-run)) "someone/repo-3")
            "the new find is digested, not dropped")))))

(deftest an-empty-refetch-never-blanks-the-days-digest
  "The same loss wearing a different hat: a re-run that finds nothing used to
   write `nothing new under the topics` over a digest that had real blocks."
  []
  (let [home (temp-home!)
        items [(fake-item 1 "2026-10-17T10:00:00Z")]]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [first-run (learn/fetch! {:now now :search-fn (fake-search items)
                                     :readme-fn fake-readme})
            first-text (slurp (:digest-path first-run))
            second-run (learn/fetch! {:now now :search-fn (fake-search items)
                                      :readme-fn fake-readme})]
        (is (= 0 (:new second-run)) "nothing new on the re-run")
        (is (= first-text (slurp (:digest-path first-run)))
            "the day's analysis material survives an empty re-fetch")
        (is (str/includes? (slurp (:digest-path second-run)) "| new: 0")
            "the continuation still states its own zero")
        (is (= {:added 0 :skipped 0 :empty true}
               (learn/propose! (:digest-path second-run)))
            "and verified-empty? still reads it as a measurement, not a refusal")
        (is (str/includes? (slurp (:digest-path first-run)) "## someone/repo-1")
            "the preserved digest still carries its block")))))

;; ---------- 2026-10-19: a digest fetched but never analyzed ----------
;; Measured today: three fetches (08:52, 09:02, 09:33) wrote <day>.md,
;; <day>-r2.md and <day>-r3.md, and all 11 repos went into seen.edn at fetch
;; time. The standing recipe says "analyze the latest digest on disk", so the 6
;; repos in the first two files were marked seen and then dropped — and a repo in
;; seen.edn is never fetched again, so the lane loses it silently. That is
;; not-found reported as done, in this lane's own bookkeeping.

(deftest unanalyzed-digests-names-the-ones-nobody-read-test
  (let [dir (str (fs/create-temp-dir {:prefix "unanalyzed-"}))
        _ (spit (str dir "/2026-10-19T00:00:00Z.md") "# d1\n## a/b\n")
        _ (spit (str dir "/2026-10-19T00:00:00Z-r2.md") "# d2\n## c/d\n")
        _ (spit (str dir "/2026-10-19T00:00:00Z-r3.md") "# d3\n## e/f\n")
        _ (spit (str dir "/2026-10-18T00:00:00Z.md") "# yesterday\n")
        _ (spit (str dir "/2026-10-18T00:00:00Z-recommendations.md") "## a/b\n- act: code\n")]
    (testing "a digest with no recommendations sibling is unanalyzed"
      (is (= ["2026-10-19T00:00:00Z-r2.md"
              "2026-10-19T00:00:00Z-r3.md"
              "2026-10-19T00:00:00Z.md"]
             (mapv #(fs/file-name (fs/path %))
                   (learn/unanalyzed-digests dir)))
          "yesterday's has a sibling, so it is analyzed; today's three do not"))
    (testing "the sibling name follows the real on-disk convention, -rN included"
      (is (str/ends-with? (str (learn/recommendations-for
                                (str dir "/2026-10-19T00:00:00Z-r2.md")))
                          "2026-10-19T00:00:00Z-r2-recommendations.md")))
    (testing "`except` drops the digest this turn is about to analyze"
      (is (= 2 (count (learn/unanalyzed-digests dir
                                                (str dir "/2026-10-19T00:00:00Z-r3.md"))))))
    (testing "recommendations files are never themselves listed"
      (is (empty? (filter #(str/ends-with? % "-recommendations.md")
                          (learn/unanalyzed-digests dir)))))
    (testing "an empty dir is a genuine zero, not a rotted scan"
      (let [empty-dir (str (fs/create-temp-dir {:prefix "unanalyzed-empty-"}))]
        (is (= [] (learn/unanalyzed-digests empty-dir)))))))

(deftest fetch-reports-unanalyzed-earlier-digests-test
  (testing "fetch!'s result names the digests a previous turn left unread"
    (let [home (str (fs/create-temp-dir {:prefix "fetch-unanalyzed-"}))
          dir (str home "/state/rsi/learn")]
      (fs/create-dirs dir)
      (spit (str dir "/2026-10-19T00:00:00Z.md") "# earlier\n## a/b\n")
      (let [res (with-redefs [bb-agent.config/home (constantly home)]
                  (learn/fetch! {:now (java.time.Instant/parse "2026-10-19T09:40:00Z")
                                 :search-fn (constantly [])
                                 :readme-fn (constantly nil)
                                 :dry-run? true}))]
        (is (= 1 (count (:unanalyzed res)))
            "the earlier digest is named; the one just written is not a hole yet")
        (is (str/ends-with? (first (:unanalyzed res)) "2026-10-19T00:00:00Z.md"))))))

;; 2026-11-02 — the seen-ledger dedupe defect, reproduced from a live refetch.
;; GitHub canonicalized `0xSneaks/SUSHILOOP` -> `0xSneaks/sushiloop` (a rename;
;; same repo id 1242406598). The ledger analyzed the UPPERCASE spelling on
;; 2026-10-31 (## 0xSneaks/SUSHILOOP, act: code, implemented) and seen.edn held
;; only that spelling, so today's search — returning the lowercase full_name —
;; did not match `contains?`, was re-fetched, re-marked-seen under a second
;; spelling, and re-digested. Identity is the repo, not the casing of its name:
;; GitHub repo names are case-insensitive. Red first: before the fix `new` was 1
;; and the propose block was appended as a duplicate of an already-refused repo.

(deftest fetch-dedupes-a-repo-github-renamed-to-a-different-case-test
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      ;; Day one: the repo is found under its original casing and marked seen.
      (learn/fetch! {:now now
                     :search-fn (fake-search [{:full-name "0xSneaks/SUSHILOOP"
                                               :url "https://github.com/0xSneaks/sushiloop"
                                               :description "guardrail engine"
                                               :stars 3 :id 1242406598
                                               :pushed-at "2026-10-31T16:43:49Z"}])
                     :readme-fn fake-readme})
      (is (= 1 (count (learn/load-seen))) "marked seen under the spelling GitHub returned")
      ;; Day two: GitHub now returns the canonical lowercase full_name for the
      ;; SAME repo id. It is not a new repo.
      (let [{n2 :new} (learn/fetch! {:now (.plusSeconds now (* 24 3600))
                                     :search-fn (fake-search [{:full-name "0xSneaks/sushiloop"
                                                               :url "https://github.com/0xSneaks/sushiloop"
                                                               :description "guardrail engine"
                                                               :stars 3 :id 1242406598
                                                               :pushed-at "2026-11-02T04:03:39Z"}])
                                     :readme-fn fake-readme})]
        (is (zero? n2)
            "a repo seen under one casing is the same repo under another — GitHub names are case-insensitive, so it must not be re-digested")
        (is (= 1 (count (learn/load-seen)))
            "and it must not be marked seen twice under two spellings")))))

(deftest propose-skips-a-block-whose-heading-differs-only-by-case-test
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      ;; The ledger already carries the repo under its original casing.
      (spit (str (fs/path home "brain" "improvements.md"))
            "## 0xSneaks/SUSHILOOP\n- steal: two rules\n- act: code\n")
      ;; Today's analysis re-files it under the canonical lowercase spelling.
      (let [recs (str (fs/path home "state" "recs.md"))]
        (fs/create-dirs (fs/parent recs))
        (spit recs "## 0xSneaks/sushiloop\n- steal: none new\n- act: code\n")
        (let [{:keys [added skipped]} (learn/propose! recs)]
          (is (zero? added)
              "the same repo under a different casing is already reviewed-and-refused; re-filing it would nag, which the exact-heading dedupe exists to stop")
          (is (= 1 skipped)))
        (is (= 1 (count (re-seq #"(?mi)^## 0xsneaks/sushiloop"
                                (slurp (str (fs/path home "brain"
                                                     "improvements.md"))))))
            "exactly one block for the repo, not two")))))


;; 2026-11-02 (afternoon) — the seen-ledger dedupe defect, SECOND occurrence and
;; a different shape. This morning's fix folded CASE, because GitHub repo names
;; are case-insensitive (`0xSneaks/SUSHILOOP` became `0xSneaks/sushiloop`). A few
;; hours later the same lane re-digested `iAlirezaaa/second-brain-os` — the repo
;; analyzed on 2026-11-01 as `iAlirezaaa/second-brain`. One GitHub RENAME, id
;; 1435733061 both ways, measured via `curl -sIL .../repos/iAlirezaaa/second-brain`
;; which answers 200 and redirects to `/repositories/1435733061`, whose body
;; reports the new name. Case folding cannot see that, and `propose!`'s
;; case-folded heading dedupe cannot either: the two headings are genuinely
;; different strings. So the repo was re-fetched, re-marked-seen under a second
;; name, and re-digested — and `same-repo?`'s own docstring had already named the
;; missing piece: "the durable identity is the repo id, which search already
;; returns and this lane discards."

(deftest fetch-dedupes-a-repo-github-renamed-to-a-different-spelling-test
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      ;; Day one: the repo is found under the name it had then.
      (learn/fetch! {:now now
                     :search-fn (fake-search [{:full-name "iAlirezaaa/second-brain"
                                               :url "https://github.com/iAlirezaaa/second-brain-os"
                                               :description "a local plain-markdown second brain"
                                               :stars 1 :id 1435733061
                                               :pushed-at "2026-11-01T09:29:23Z"}])
                     :readme-fn fake-readme})
      (is (= 1 (count (learn/load-seen))) "marked seen once")
      ;; Day two: GitHub renamed the repo. Same id, different name. It is not a
      ;; new repo — but a name-keyed ledger says it is.
      (let [{n2 :new} (learn/fetch! {:now (.plusSeconds now (* 24 3600))
                                     :search-fn (fake-search [{:full-name "iAlirezaaa/second-brain-os"
                                                               :url "https://github.com/iAlirezaaa/second-brain-os"
                                                               :description "Second Brain OS, engine codename HADES v3"
                                                               :stars 1 :id 1435733061
                                                               :pushed-at "2026-11-02T07:22:38Z"}])
                                     :readme-fn fake-readme})]
        (is (zero? n2)
            "the same repo id under a new name is not a new repo — identity is the id, never the spelling")
        (is (= 1 (count (learn/load-seen)))
            "and it must not be marked seen twice under two names")))))

(deftest seen-ledger-records-the-id-that-fetch-already-had-test
  ;; The durable identity has to survive normalization AND the write, or no later
  ;; run can use it. Injected search-fn, no network.
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (learn/fetch! {:now now
                     :search-fn (fake-search [{:full-name "someone/repo-1"
                                               :url "u" :description "d"
                                               :stars 1 :id 4242
                                               :pushed-at "2026-11-02T05:00:00Z"}])
                     :readme-fn fake-readme})
      (let [on-disk (edn/read-string (slurp (str (fs/path home "state" "rsi" "learn" "seen.edn"))))
            row (first on-disk)
            row (if (string? row) {:name row :id nil} row)]
        (is (= 1 (count on-disk)))
        (is (map? row) "a recorded row carries the id beside the name, not the name alone")
        (is (= "someone/repo-1" (:name row)))
        (is (= 4242 (:id row)) "the id this lane discarded is the identity a rename keeps")
        (is (some #(= 4242 (:id (if (string? %) {:name % :id nil} %))) on-disk)
            "and the id is ON DISK, not only in memory — a later run has to see it")))))

(deftest a-legacy-name-only-seen-ledger-still-dedupes-test
  ;; Migration is at read time, not a rewrite of 177 recorded names: the ledger on
  ;; disk has always been a vector of strings, and an entry with no id must still
  ;; dedupe by name, so this fix cannot re-find yesterday's repos.
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [dir (fs/path home "state" "rsi" "learn")]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "seen.edn")) "[\"someone/repo-1\" \"someone/repo-2\"]")
        (let [{n :new} (learn/fetch! {:now now
                                      :search-fn (fake-search [{:full-name "someone/repo-1"
                                                                :url "u" :description "d"
                                                                :stars 1 :id 7
                                                                :pushed-at "2026-11-02T05:00:00Z"}])
                                      :readme-fn fake-readme})]
          (is (zero? n) "a name recorded without an id still matches by name")
          (is (= 2 (count (learn/load-seen))) "no row lost, none duplicated")
          (is (some #(= "someone/repo-2" (if (string? %) % (:name %)))
                    (edn/read-string (slurp (str (fs/path home "state" "rsi" "learn" "seen.edn")))))
              "an untouched legacy entry survives a fetch that did not see it"))))))


;; Two more tests on the same fix, because the three above drive fetch! through an
;; injected search-fn — which cannot prove that the REAL `search` keeps the id it
;; receives, nor that identity is a predicate rather than a migration. Both are
;; cheap: `with-redefs` on the private transport makes no network call, and the
;; predicate test touches no disk.

(deftest search-normalization-keeps-the-repo-id-test
  (with-redefs [bb-agent.rsi-learn/get-json
                (fn [_ _] {:items [{:full_name "someone/x" :id 99
                                    :html_url "u" :created_at "c" :fork false}]})]
    (let [rows (bb-agent.rsi-learn/search {:query "q" :since "s"})]
      (is (= 1 (count rows)))
      (is (= 99 (:id (first rows))) "the id survives normalization, or nothing downstream can use it")
      (is (= "someone/x" (:full-name (first rows)))))))

(deftest seen-identity-is-a-predicate-not-a-migration-test
  ;; Reached through `resolve` on purpose. A direct reference to a symbol that
  ;; does not exist yet aborts the whole namespace at analysis, so no test in
  ;; this file could produce a failure receipt at all — and "the file would not
  ;; load" is a weaker receipt than "the defect is here".
  (let [rows [{:name "someone/old-name" :id 5} {:name "someone/no-id" :id nil}]
        si (or (resolve 'bb-agent.rsi-learn/seen-identity) (constantly nil))]
    (is (some? (resolve 'bb-agent.rsi-learn/seen-identity))
        "the identity predicate is part of the namespace's public contract")
    (is (si {:full-name "someone/new-name" :id 5} rows)
        "same id, different name: the repo GitHub renamed")
    (is (si {:full-name "someone/OLD-NAME" :id nil} rows)
        "same name, different case: still one repo")
    (is (si {:full-name "someone/no-id" :id 999} rows)
        "a legacy name matches even when today's payload carries an id we never recorded")
    (is (not (si {:full-name "someone/new-name" :id nil} rows))
        "no id and no name is a repo this lane has not seen")
    (is (not (si {:full-name "someone/other" :id 6} rows))
        "a different id is a different repo, however similar the name")))

;; ---------- 2026-11-03: the rename hole in propose!'s dedupe ----------
;; Measured live, not hypothetical: the ledger holds `## iAlirezaaa/second-brain`
;; (analyzed 2026-11-01, block at brain/improvements.md:887, `created
;; 2026-10-27T12:58:06Z`), and today's digest serves `## iAlirezaaa/second-brain-os`
;; with the SAME `created 2026-10-27T12:58:06Z` and id 1435733061 (GET
;; /repos/iAlirezaaa/second-brain-os). Case-folding — landed 2026-11-02 — cannot
;; see a rename to a different spelling, which is exactly the scope
;; `same-repo?`'s docstring names and defers to the repo id. So propose! would
;; have appended a SECOND block for an already-reviewed repo, permanently: the
;; repo is in seen.edn and never fetched again. The durable identity is the id,
;; and `search` already carries it (the 2026-11-02 fix) while the digest row
;; threw it away at render time — the same move as 2026-10-17's substance fields.

(deftest digest-prints-the-repo-id-it-already-fetched
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search
                                       [(full-item 4242 "2026-09-24T10:00:00Z"
                                                   {:created-at "2026-09-24T09:58:00Z"
                                                    :fork false :language "Python"})])
                           :readme-fn fake-readme})
            d (slurp digest-path)]
        (is (str/includes? d "- id: 4242 |") "the durable identity is on the row")
        (is (str/includes? d "created: 2026-09-24T09:58:00Z")
            "no-regression: the substance fields still render")))))

(deftest digest-renders-an-absent-id-as-unknown
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search [(fake-item 1 "2026-09-24T10:00:00Z")])
                           :readme-fn fake-readme})
            d (slurp digest-path)]
        (is (str/includes? d "- id: ? |") "not-found is not zero, in the row itself")))))

(deftest propose-skips-a-renamed-repo-by-id-test
  (let [home (temp-home!)
        imp (fs/path home "brain" "improvements.md")]
    (fs/create-dirs (fs/parent imp))
    (spit (str imp)
          (str "## someone/old-name\n"
               "- id: 5 | created: c | fork: false | lang: X | readme: r\n"
               "- steal: None.\n- lens: Refuse.\n- act: code\n"))
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec)
              (str "## someone/new-name\n"
                   "- id: 5 | created: c | fork: false | lang: X | readme: r\n"
                   "- steal: None.\n- lens: Refuse.\n- act: code\n"))
        (let [r (learn/propose! (str rec))
              after (slurp (str imp))]
          (is (= 0 (:added r)) "a rename of an already-reviewed repo is not a new proposal")
          (is (= 1 (:skipped r)))
          (is (= 1 (:skipped-by-id r)) "the skip is attributable to the id, not the name")
          (is (= 1 (count (re-seq #"(?m)^## " after))) "one block in the ledger, not two")
          (is (not (str/includes? after "## someone/new-name"))))))))

(deftest propose-still-files-a-genuinely-new-repo-when-ids-are-present-test
  ;; The other side of the same gate: id-dedupe must not swallow real work.
  (let [home (temp-home!)
        imp (fs/path home "brain" "improvements.md")]
    (fs/create-dirs (fs/parent imp))
    (spit (str imp)
          (str "## someone/old-name\n"
               "- id: 5 | created: c\n- steal: None.\n- lens: Refuse.\n- act: code\n"))
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec)
              (str "## someone/other\n"
                   "- id: 6 | created: c\n- steal: None.\n- lens: Refuse.\n- act: code\n"))
        (let [r (learn/propose! (str rec))]
          (is (= 1 (:added r)) "a different id is a different repo, however similar")
          (is (nil? (:skipped-by-id r)) "the new key appears only when it fired"))))))

(deftest propose-name-dedupe-is-unchanged-for-blocks-without-ids-test
  ;; No-regression: every block already in the live ledger predates the `- id:`
  ;; line, so name identity must keep working exactly as before.
  (let [home (temp-home!)
        imp (fs/path home "brain" "improvements.md")]
    (fs/create-dirs (fs/parent imp))
    (spit (str imp) "## someone/x\n- steal: None.\n- lens: Refuse.\n- act: code\n")
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [rec (fs/path home "rec.md")]
        (spit (str rec) "## SOMEONE/X\n- steal: None.\n- lens: Refuse.\n- act: code\n")
        (let [r (learn/propose! (str rec))]
          (is (= 0 (:added r)))
          (is (= 1 (:skipped r)))
          (is (nil? (:skipped-by-id r))))))))

;; ---------- 2026-11-05: an unread README printed as no README ----------
;; Measured defect, reproduced this turn: `bb rsi learn fetch` returned
;; `readme: ?` + `(no readme)` for BOTH of today's repos
;; (Abhijithreddydasari/ask-Y, munitkala/selfsemester) while
;; raw.githubusercontent serves them 200 at 25035 and 10238 bytes. Cause:
;; fetch-readme hits the GitHub `/readme` endpoint, which was returning
;; HTTP 403 `API rate limit exceeded` (core remaining=0, reset
;; 2026-11-05T07:15:15Z), and a 403 took the same branch as a 404. The
;; 2026-11-03 ledger carries the consequence as a FALSE CLAIM:
;; mianadilofficial/social-agent-suite was recorded as having "no README at
;; all" — it has one, 13792 bytes. Not-found reported as zero, fifth
;; occurrence in this lane, and the first that wrote a lie into the corpus.

(deftest readme-result-separates-absent-from-unreadable-test
  (let [ok (learn/readme-result "someone/repo-1"
                                (fn [_ _] {:status 200 :body "a real pitch"}))]
    (is (= :ok (:status ok)))
    (is (= "a real pitch" (:text ok))))
  (let [gone (learn/readme-result "someone/repo-1"
                                  (fn [_ _] {:status 404 :body "Not Found"}))]
    (is (= :absent (:status gone)) "a 404 is a measured absence")
    (is (nil? (:text gone)))
    (is (str/includes? (:detail gone) "404") "and the detail names the status"))
  (let [limited (learn/readme-result "someone/repo-1"
                                     (fn [_ _] {:status 403
                                                :body "API rate limit exceeded"}))]
    (is (= :unreadable (:status limited))
        "a rate limit is NOT an absence — this is the whole fix")
    (is (nil? (:text limited)))
    (is (str/includes? (:detail limited) "403")))
  (let [boom (learn/readme-result "someone/repo-1"
                                  (fn [_ _] (throw (ex-info "timeout" {}))))]
    (is (= :unreadable (:status boom))
        "a transport failure is not-found-as-unknown, never as zero")
    (is (str/includes? (:detail boom) "timeout"))))

(deftest fetch-readme-still-returns-text-or-nil-so-its-callers-do-not-break-test
  ;; No-regression: fetch-readme's documented contract is text-or-nil, and its
  ;; own 2026-10-18 deftest pins that. readme-result is the richer reader.
  (is (= "a real pitch"
         (learn/fetch-readme "someone/repo-1"
                             (fn [_ _] {:status 200 :body "a real pitch"}))))
  (is (nil? (learn/fetch-readme "someone/repo-1"
                                (fn [_ _] {:status 404 :body "x"}))))
  (is (nil? (learn/fetch-readme "someone/repo-1"
                                (fn [_ _] {:status 403 :body "x"})))))

(deftest digest-says-not-read-rather-than-no-readme-test
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [{:keys [digest-path]}
            (learn/fetch! {:now now
                           :search-fn (fake-search [(fake-item 1 "2026-11-05T10:00:00Z")
                                                    (fake-item 2 "2026-11-05T09:00:00Z")])
                           :readme-fn (fn [full-name]
                                        (learn/readme-result
                                         full-name
                                         (fn [_ _]
                                           (if (= "someone/repo-1" full-name)
                                             {:status 403 :body "API rate limit exceeded"}
                                             {:status 404 :body "Not Found"}))))})
            d (slurp digest-path)]
        (is (str/includes? d "readme: unreadable")
            "a refused read prints its own status, never `?`")
        (is (str/includes? d "readme: absent")
            "a measured absence prints as absent, so the two are never one value")
        (is (str/includes? d "NOT READ")
            "the excerpt slot says the README was not read")
        (is (str/includes? d "403")
            "and names why, so a human can re-run it after the reset")
        (is (str/includes? d "README(s) NOT READ")
            "the header warns that a `?`/unreadable row is not evidence of a bare repo")
        (is (not (str/includes? d "(no readme)"))
            "the ambiguous string is gone from the renderer")))))
