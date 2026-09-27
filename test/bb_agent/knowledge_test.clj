(ns bb-agent.knowledge-test
  (:require [babashka.fs :as fs]
            [bb-agent.knowledge :as k]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *dir* nil)

(defn tmp-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "knowledge-test"}))]
    (binding [*dir* d]
      (spit (str d "/fresh.md") "# Fresh\nlast-reviewed: 2026-09-20\n")
      (spit (str d "/old.md") "# Old\nlast-reviewed: 2026-08-01\n")
      (spit (str d "/unmarked.md") "# Unmarked\nbody\n")
      (f))
    (fs/delete-tree d)))

(use-fixtures :each tmp-dir)

(def ^:private today (java.time.LocalDate/parse "2026-09-27"))
(def ^:private opts {:today today :max-age-days 30})

(deftest stale-pages-test
  (testing "flags stale and unmarked, skips fresh"
    (let [found (k/stale-pages *dir* opts)]
      (is (= ["old.md" "unmarked.md"]
             (mapv (comp fs/file-name fs/path :file) found)))
      (is (= [:stale :unmarked] (mapv :status found)))))
  (testing "empty dir = empty report, not an error"
    (let [d (str (fs/create-temp-dir))]
      (is (empty? (k/stale-pages d opts)))
      (fs/delete-tree d))))

(deftest stamp-review-test
  (let [p (str *dir* "/old.md")]
    (testing "replaces an existing mark"
      (k/stamp-review! p today)
      (is (= :fresh (:status (k/page-status p {:today today :max-age-days 30})))))
    (testing "adds a mark to an unmarked page"
      (let [u (str *dir* "/unmarked.md")]
        (k/stamp-review! u today)
        (is (re-find #"last-reviewed: 2026-09-27" (slurp u))))
      (testing "and left a snapshot behind"
        (is (seq (fs/glob *dir* "*.bak-*")))))))

(deftest record-win-test
  (let [w (str *dir* "/wins.md")]
    (testing "blank evidence is refused, loudly"
      (is (thrown? Exception (k/record-win! w {:what "x" :evidence ""})))
      (is (not (fs/exists? w))))
    (testing "evidence-bearing win appends"
      (k/record-win! w {:what "fallback fix" :evidence "tests 125/467 green, commit 0b1c54b"})
      (let [s (slurp w)]
        (is (re-find #"fallback fix" s))
        (is (re-find #"tests 125/467 green" s))))))

(deftest tier-of-test
  (testing "tier lookup is data, not judgment"
    (is (= :forbidden (k/tier-of "brain/rules.clj")))
    (is (= :forbidden (k/tier-of "config.edn")))
    (is (= :strict (k/tier-of "brain/improvements.md")))
    (is (= :soft (k/tier-of "state/usage.edn")))
    (is (= :unclassified (k/tier-of "src/bb_agent/knowledge.clj")))))

(deftest check-write-test
  (testing "forbidden always throws"
    (is (thrown? Exception (k/check-write! "brain/rules.clj" {:justification "please"}))))
  (testing "strict requires a written justification"
    (is (thrown? Exception (k/check-write! "brain/x.md" {})))
    (is (:allowed (k/check-write! "brain/x.md" {:justification "moe approved 2026-09-27"}))))
  (testing "soft passes through"
    (is (:allowed (k/check-write! "state/foo.edn" {})))))

(deftest snapshot-test
  (let [p (str *dir* "/snap.md")]
    (spit p "v1")
    (testing "existing file gets a timestamped copy"
      (let [bak (k/snapshot! p)]
        (is (some? bak))
        (is (= "v1" (slurp bak)))))
    (testing "missing file returns nil, no crash"
      (is (nil? (k/snapshot! (str *dir* "/nope.md")))))))

(deftest curator-candidates-test
  (testing "dry-run flags problems and duplicate titles, archives nothing"
    (spit (str *dir* "/dupe.md") "# Old\nlast-reviewed: 2026-09-01\n")
    (let [r (k/curator-candidates *dir* opts)]
      (is (contains? (set (map (comp str fs/path :file) (:problems r)))
                     (str *dir* "/old.md")))
      (is (= [{:title "Old" :files [(str *dir* "/dupe.md") (str *dir* "/old.md")]}]
             (:duplicate-titles r)))
      ;; dry-run: nothing archived, nothing written
      (is (= 4 (count (fs/glob *dir* "*.md")))))))

(deftest promote-gate-test
  (testing "all green = :promote"
    (let [r (k/promote-gate {"new-check" (constantly {:ok true :detail "new ✓"})
                             "prior-suite" (constantly {:ok true :detail "125/467 ✓"})})]
      (is (= :promote (:verdict r)))
      (is (every? :ok (vals (:results r))))))
  (testing "any regression blocks — recorded, not silent"
    (let [r (k/promote-gate {"new-check" (constantly {:ok true :detail "new ✓"})
                             "prior-suite" (fn [] {:ok false :detail "2 broke"})})]
      (is (= :block (:verdict r)))
      (is (= "2 broke" (get-in r [:results "prior-suite" :detail])))))
  (testing "a throwing check blocks rather than crashing the gate"
    (let [r (k/promote-gate {"boom" (fn [] (throw (ex-info "x" {})))})]
      (is (= :block (:verdict r)))
      (is (re-find #"threw" (get-in r [:results "boom" :detail]))))))
