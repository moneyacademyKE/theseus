(ns e2e.rsi-learn-test
  (:require [babashka.fs :as fs]
            [bb-agent.rsi-learn :as learn]
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
                      "- act: none\n")
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
