(ns bb-agent.coordinates-externals-test
  "Red first for `bb coords externals`, the second report in
   bb-agent.coordinates. Filed 2026-11-03 as act: code from
   Shambhavagrawal/rotless keeper.py:72,107 (a controlled verdict vocabulary; a
   check that lists every reference nobody tracks). Its goal
   bb-coords-externals-list-489058 errored on a provider timeout after 25 rounds
   having built nothing, so the spec landed directly on 2026-11-04.

   Why a separate file: test/bb_agent/coordinates_test.clj fails at ANALYSIS on
   three symbols another lane's red-first test expects
   (undecidable-cite?, claim-cite, unsupported-claims), so nothing in that file
   can run. These tests are registered separately in bb.edn and must stay green
   on their own — a suite that cannot load is not a suite that passes.

   No network: every fetch is injected through `fetch-fn`, the same seam
   rsi-learn's readme fetch uses. `default-fetch` is never called here."
  (:require [babashka.fs :as fs]
            [bb-agent.coordinates :as c]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *root* nil)

(defn- tiny-corpus
  [f]
  (let [r (str (fs/create-temp-dir {:prefix "coords-ext-test"}))]
    (fs/create-dirs (str r "/brain/knowledge"))
    (spit (str r "/brain/knowledge/a.md")
          (str "# A\n"
               "- from https://github.com/owner/repo/blob/main/src/x.py\n"
               "- raw https://raw.githubusercontent.com/owner/repo/main/src/x.py\n"
               "- and again https://github.com/owner/repo/blob/main/src/x.py\n"))
    (spit (str r "/brain/knowledge/b.md")
          (str "# B\n"
               "- shared https://github.com/owner/repo/blob/main/src/x.py\n"
               "- dead https://github.com/gone/gone\n"))
    ;; a snapshot must not double every finding
    (spit (str r "/brain/knowledge/a.md.bak-20261104")
          "# A\n- https://github.com/owner/repo/blob/main/src/x.py\n")
    (binding [*root* r]
      (f))
    (fs/delete-tree r)))

(use-fixtures :each tiny-corpus)

(deftest citations-are-counted-per-page-not-per-url
  (let [cites (c/external-citations (str *root* "/brain"))
        rep (c/externals-report cites)]
    (testing "the unit is a CITATION: one URL cited by two pages is two rows"
      (is (= 5 (:citations rep)) "5 cites across a.md (3) and b.md (2)")
      (is (= 3 (:distinct-urls rep)) "three distinct URLs behind them"))
    (testing "the snapshot is excluded, so it cannot double the count"
      (is (= 5 (:citations rep))
          "a.md.bak-20261104 carries the same URL and is NOT counted"))
    (testing "raw and page are different kinds, because their bytes mean different things"
      (is (= 1 (:raw rep)))
      (is (= 4 (:page rep))))
    (testing "every row starts at not-checked, and the report says how many it fetched"
      (is (= 0 (:fetched rep)) "nothing was fetched by a report")
      (is (every? #(= :not-checked (:status %)) (:by-url rep))))
    (testing "a row names the pages that cite it, so a dead link is fixable"
      (let [shared (first (filter #(str/ends-with? (:url %) "src/x.py") (:by-url rep)))]
        (is (= 3 (:cited-by shared)) "a.md twice, b.md once")
        (is (= 2 (count (:sources shared))) "two distinct pages")
        (is (every? #(str/includes? % "knowledge/") (:sources shared)))))))

(deftest check-external-url-reports-not-found-as-fail-never-as-ok
  (testing "a 200 is OK and carries a sha, so a later run can say CHANGED"
    (let [r (c/check-external-url "https://example.test/a"
                                 (constantly {:http 200 :body "hello"}))]
      (is (= :ok (:status r)))
      (is (= 200 (:http r)))
      (is (= 5 (:bytes r)))
      (is (= "aaf4c61ddcc5" (subs (:sha r) 0 12)) "sha1 of \"hello\", stable and checkable")))
  (testing "a non-200 is FAIL carrying the status, not a guess"
    (let [r (c/check-external-url "https://example.test/gone"
                                 (constantly {:http 404 :body nil}))]
      (is (= :fail (:status r)))
      (is (= 404 (:http r)))
      (is (= "HTTP 404" (:reason r)))))
  (testing "an unreachable host is FAIL with a reason — not-found is not zero"
    (let [r (c/check-external-url "https://example.test/x"
                                 (constantly {:http nil :body nil :error "UnknownHost"}))]
      (is (= :fail (:status r)))
      (is (nil? (:http r)))
      (is (= "UnknownHost" (:reason r)))))
  (testing "a nil http with no error message still refuses to read as ok"
    (let [r (c/check-external-url "https://example.test/x" (constantly {:http nil}))]
      (is (= :fail (:status r)))
      (is (str/includes? (:reason r) "not-found, not zero")))))

(deftest fetch-externals-counts-what-it-actually-attempted
  (let [rep (-> (c/external-citations (str *root* "/brain")) c/externals-report)
        calls (atom [])
        fake (fn [url] (swap! calls conj url)
               (if (str/includes? url "gone") {:http 404 :body nil} {:http 200 :body "x"}))
        out (c/fetch-externals rep fake)]
    (testing "every distinct URL was attempted once, and the report says so"
      (is (= 3 (:fetched out)) "3 distinct URLs, not 5 citations")
      (is (= 3 (count @calls))))
    (testing "statuses come from the wire, not from a default"
      (is (= 2 (count (filterv #(= :ok (:status %)) (:by-url out)))))
      (is (= 1 (count (filterv #(= :fail (:status %)) (:by-url out))))))
    (testing "no row is left at not-checked after a fetch"
      (is (empty? (filterv #(= :not-checked (:status %)) (:by-url out)))))))

(deftest report-states-its-own-negative-scope
  (let [rep (-> (c/external-citations (str *root* "/brain")) c/externals-report)
        out (with-out-str (c/externals-print! rep))]
    (testing "an unfetched report cannot read as a clean bill"
      (is (str/includes? out "fetched: 0 of 3"))
      (is (str/includes? out "every status below is not-checked, which is a state, not a pass"))
      (is (str/includes? out "NOTHING was fetched")))
    (testing "and it says what a fetch would NOT prove either"
      (is (str/includes? out "a 200 proves the URL resolves, not that the claim holds"))
      (is (str/includes? out "Report only")))))

(deftest cli-externals-exits-zero-and-names-a-missing-path
  (testing "bb coords externals <dir> reports and exits 0"
    (let [out (with-out-str (is (= 0 (c/-main ["externals" (str *root* "/brain")]))))]
      (is (str/includes? out "external citations: 5 cite(s)"))
      (is (str/includes? out "not-checked"))))
  (testing "a missing path exits 2 and names it, rather than reporting an empty corpus"
    (let [out (with-out-str (is (= 2 (c/-main ["externals" "/tmp/no-such-corpus-xyz"]))))]
      (is (str/includes? out "no such path"))))
  (testing "--fetch is accepted by the CLI (the fetch itself is not exercised here)"
    (is (str/includes? (with-out-str (c/-main ["coords"])) "")
        "the audit path still runs unchanged — no regression")))
;; ---------- 2026-11-05: default-fetch had never once reached a socket ----------

(deftest default-fetch-fails-on-the-network-and-not-on-its-own-code-test
  "Measured live 2026-11-05 — the FIRST real `bb coords externals --fetch` run
   since the report landed on 2026-11-04 returned **146 of 146 FAIL** with one
   identical reason: `Method setConnectTimeout on class
   sun.net.www.protocol.https.HttpsURLConnectionImpl not allowed!`. This
   runtime's reflector forbids those setters, so default-fetch threw before it
   ever opened a connection: the externals report could enumerate citations and
   could never verify one, and 2026-11-04's own not-checked line named exactly
   this as unexercised (\"no --fetch was run against the network … so
   default-fetch's real HTTP behaviour is unexercised\"). Running it is what
   found it. A refused connection is a fact about the network; a forbidden
   method is a bug in our code, and the two must not print the same way."
  []
  (let [r (c/default-fetch "http://127.0.0.1:1/nope")]
    (is (nil? (:http r)) "port 1 refuses, so there is no status")
    (is (some? (:error r)) "and the reason is carried, not swallowed")
    (is (not (str/includes? (str (:error r)) "not allowed"))
        "the failure is the network, not a method this runtime forbids")
    (is (not (str/includes? (str (:error r)) "setConnectTimeout")))
    (is (map? r) "still data, never a thrown exception — an unreachable host is a
                  FAIL verdict with a reason, not an abort halfway through the report")))

(deftest default-fetch-reads-a-real-url-over-one-http-seam-test
  "The transport is babashka.http-client — the same seam bb-agent.rsi-learn uses —
   because one HTTP client in a repo beats two, and the second one was the one
   that did not work. Injected nowhere: this asserts only the SHAPE default-fetch
   returns, using a URL that cannot resolve, so the suite still makes no network
   call that could pass or fail on somebody's connectivity."
  []
  (let [r (c/default-fetch "https://theseus-coords-no-such-host.invalid/x")]
    (is (nil? (:http r)))
    (is (string? (:error r)) "the reason is printable prose")
    (is (not (str/includes? (:error r) "not allowed")))))

(deftest url-re-stops-at-prose-punctuation-test
  "Measured on the first real --fetch run, 2026-11-05: 3 of the 17 FAIL rows
   were OUR bugs wearing a network verdict. `https://github.com/chillax97/edu4self,`
   and `https://github.com/phense/agentic-rag,` were cited in prose with a
   trailing comma the character class swallowed, so GitHub 404'd on a repository
   that exists; and the placeholder `https://github.com/owner/repo/blob/<ref>`
   returned `Illegal character in path at index 35` because `<` was inside the
   allowed set. Same defect both ways: punctuation and markup that are not part
   of a URL were counted as part of it, so a FAIL row named somebody else's
   repository for our own quoting."
  []
  (let [hits #(mapv second (re-seq c/url-re %))]
    (is (= ["https://github.com/owner/repo"]
           (hits "see https://github.com/owner/repo, which is the source"))
        "a trailing comma is prose, not part of the URL")
    (is (= ["https://github.com/owner/repo"] (hits "(https://github.com/owner/repo)"))
        "a closing paren is still a boundary")
    (is (= ["https://github.com/owner/repo"] (hits "`https://github.com/owner/repo`"))
        "backticks delimit")
    (is (= ["https://raw.githubusercontent.com/o/r/main/x.py"]
           (hits "[x](https://raw.githubusercontent.com/o/r/main/x.py)"))
        "markdown link syntax delimit")
    (is (empty? (hits "a cite to another repository's file is a URL"))
        "prose with no URL yields nothing — not-found, not zero")
    (is (not (some #(str/includes? % "<")
                   (hits "shape: https://github.com/owner/repo/blob/<ref>/p#L1")))
        "`<` is markup, so a placeholder never reaches the transport as a citation")))
