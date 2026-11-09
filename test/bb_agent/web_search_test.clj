(ns bb-agent.web-search-test
  "FTS search over session files: index build, incremental refresh,
  query sanitization, and snippet markers — all under a sandboxed home."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [bb-agent.config :as config]
            [bb-agent.web-search :as ws]))

(def ^:private home (atom nil))

(defn- seed-session!
  [id turns]
  (let [dir (fs/path @home "state" "sessions")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir (str id ".edn"))) (pr-str turns))))

(use-fixtures :each
  (fn [f]
    (reset! home (fs/create-temp-dir {:prefix "websearch-"}))
    (fs/create-dirs (fs/path @home "state" "sessions"))
    (with-redefs [config/home (constantly @home)]
      (f))
    (fs/delete-tree @home)))

(deftest search-finds-and-ranks
  (seed-session! "alpha" [{:user/input "where does the render budget go"
                           :assistant/final "the render budget skips offscreen paint"
                           :source :web :turn/ok true}])
  (seed-session! "beta" [{:user/input "tell me about searchneedle valley"
                          :assistant/final "searchneedle valley is fictional"
                          :source :web :turn/ok true}])
  (let [hits (ws/search "searchneedle")]
    (is (= 1 (count hits)))
    (is (= "beta" (:id (first hits))))
    (is (str/includes? (str (:snippet (first hits))) "searchneedle"))
    ;; the marker pair wraps the term: odd split positions are terms
    (is (re-find #"\u0001searchneedle\u0002" (str (:snippet (first hits)))))))

(deftest query-sanitization
  (seed-session! "alpha" [{:user/input "plain text" :assistant/final "plain answer"
                           :source :web :turn/ok true}])
  ;; FTS5 grammar characters must not throw; they reduce to tokens
  (is (vector? (ws/search "colons:(AND) OR \"quotes\"")))
  (is (empty? (ws/search "")))
  (is (empty? (ws/search "!?!?"))))

(deftest incremental-refresh-picks-up-changes
  (seed-session! "live" [{:user/input "first version" :assistant/final "no signal word"
                          :source :web :turn/ok true}])
  (is (empty? (ws/search "kanarphrase")))
  ;; rewrite the file with new content; the next query must see it
  ;; without anything but the mtime changing
  (seed-session! "live" [{:user/input "second version" :assistant/final "the kanarphrase appears"
                          :source :web :turn/ok true}])
  (is (= 1 (count (ws/search "kanarphrase")))))

(deftest deleted-session-drops-from-index
  (seed-session! "gone" [{:user/input "vanishing act" :assistant/final "poof"
                          :source :web :turn/ok true}])
  (is (= 1 (count (ws/search "vanishing"))))
  (fs/delete (fs/path @home "state" "sessions" "gone.edn"))
  (is (empty? (ws/search "vanishing"))))

(deftest empty-home-is-empty
  (is (empty? (ws/search "anything"))))
