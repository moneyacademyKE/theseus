(ns bb-agent.autonomy-test
  (:require [babashka.fs :as fs]
            [bb-agent.autonomy :as au]
            [clojure.test :refer [deftest is testing]]))

(defn- ev [kind at]
  {:at at :kind kind :file "brain/knowledge/x.md" :note nil})

(defn- applies [n] (mapv (partial ev :applied) (map #(str "t" %) (range n))))
(defn- reverts [from n]
  (mapv (partial ev :reverted) (map #(str "z" %) (range from (+ from n)))))

(deftest stats-refuse-small-samples
  (let [s (au/stats {:applied (applies 1) :reverted []})]
    (testing "below the honesty gate the rate is not computed"
      (is (false? (:sufficient s)))
      (is (nil? (:retention s))))))

(deftest stats-sufficient-at-the-bar
  (let [s (au/stats {:applied (applies 8) :reverted []})]
    (is (:sufficient s))
    (is (= 1.0 (:retention s)))
    (is (zero? (:recent-reverts s)))))

(deftest recent-window-counts-only-the-tail
  (testing "7 applied then 2 reverts: last 5 events = 3 applied + 2 reverts"
    (let [s (au/stats {:applied (applies 7) :reverted (reverts 100 2)})]
      (is (= 2 (:recent-reverts s)))
      (is (= 7 (:applied s)))
      (is (= 5 (:survived s))))))

(deftest retention-decay-blocks-the-bar
  (testing "8 applied, 3 reverted → 62.5% — under the 80% bar"
    (let [s (au/stats {:applied (applies 8) :reverted (reverts 100 3)})]
      (is (false? (au/meets-bar? s (:apply-knowledge au/promotion-bar)))))))

(deftest earned-tier-walks-then-stops
  (let [ledger {:applied (applies 8) :reverted []}]
    (is (= :apply-knowledge (au/earned-tier ledger)))
    (testing "apply-all needs 20 — the walk stops short"
      (is (not= :apply-all (au/earned-tier ledger))))))

(deftest earned-tier-falls-with-evidence
  (testing "a revert spike decays retention and drops the floor"
    (let [ledger {:applied (applies 8) :reverted (reverts 100 3)}]
      (is (= :propose (au/earned-tier ledger))))))

(deftest effective-is-the-min
  (let [mid {:applied (applies 8) :reverted []}
        top {:applied (applies 20) :reverted []}]
    (testing "the walk reaches the top only on the full ledger"
      (is (= :apply-all (au/earned-tier top))))
    (is (= :apply-knowledge (au/effective-tier :apply-all mid)))
    (is (= :propose (au/effective-tier :propose mid)))
    (testing "unknown grant means the constitution default"
      (is (= :propose (au/effective-tier :bogus mid))))))

(deftest tier-allows-is-narrow
  (is (false? (au/tier-allows? :propose "brain/knowledge/x.md")))
  (is (true? (au/tier-allows? :apply-knowledge "brain/knowledge/x.md")))
  (is (false? (au/tier-allows? :apply-knowledge "brain/SOUL.md")))
  (is (true? (au/tier-allows? :apply-all "brain/SOUL.md")))
  (is (false? (au/tier-allows? :apply-all "src/bb_agent/core.clj")))
  (is (false? (au/tier-allows? :mystery "brain/x.md"))))

(deftest ledger-roundtrip-in-temp-home
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [bb-agent.config/home (constantly home)]
      (au/record! :applied "brain/knowledge/a.md" "first")
      (au/record! :reverted "brain/knowledge/a.md" "did not stick")
      (let [l (au/load-ledger)]
        (is (= 1 (count (:applied l))))
        (is (= 1 (count (:reverted l))))
        (is (= "first" (:note (first (:applied l)))))
        (is (= :reverted (:kind (first (:reverted l)))))))))

(deftest status-report-names-the-parties
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [out (with-out-str (au/-main))]
        (is (re-find #"earned: :propose" out))
        (is (re-find #"granted: :propose" out))
        (is (re-find #"insufficient evidence" out))
        (is (re-find #"owner grants" out))))))
