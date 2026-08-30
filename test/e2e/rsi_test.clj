(ns e2e.rsi-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.rsi :as rsi]
            [bb-agent.usage :as usage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-home! []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "state"))
    dir))

(def ^:private base-event
  {:session-id "s1" :provider :fake :model "fake-model"
   :prompt "p" :final "f"})

(deftest event-carries-ok-and-roundtrips
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [ev (usage/append-event! (usage/event (assoc base-event :ok true)))
            loaded (usage/load-events)]
        (is (true? (:ok (last loaded))))
        (is (true? (:ok (first loaded))))
        (is (= :fake (:provider (first loaded))))))))

(deftest digest-aggregates-per-provider
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (usage/append-event! (usage/event (assoc base-event :ok false)))
      (usage/append-event! (usage/event (assoc base-event :ok true
                                               :provider :anthropic
                                               :fallback-served :fake)))
      (let [d (rsi/digest)
            fake (get (:providers d) :fake)]
        (is (= 3 (:events d)))
        (is (= {:turns 2 :ok 1 :fail 1 :fallback-hits 1} fake))))))

(deftest write-digest-creates-readable-file
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event base-event))
      (let [path (rsi/write-digest!)
            text (slurp (str path))]
        (is (fs/regular-file? path))
        (is (str/includes? text ":fake"))
        (is (str/includes? text "events"))))))

(deftest analyze-blocked-under-minimum
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event base-event))
      (let [r (rsi/analyze {:min-events 5})]
        (is (:blocked r))
        (is (str/includes? (:blocked r) "4 more"))))))

(deftest analyze-flags-failure-rate
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [ok [true false false true true]]
        (usage/append-event! (usage/event (assoc base-event :ok ok))))
      (let [{:keys [opportunities]} (rsi/analyze {:min-events 2})
            kind (set (map :kind opportunities))]
        (is (contains? kind :provider-failures))))))

(deftest analyze-flags-fallback-pressure
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [_ [1 2]]
        (usage/append-event! (usage/event (assoc base-event :provider :anthropic
                                                  :ok true :fallback-served :fake))))
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (let [{:keys [opportunities]} (rsi/analyze {:min-events 2})
            kind (set (map :kind opportunities))]
        (is (contains? kind :fallback-pressure))))))

(deftest propose-appends-and-dedupes
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [ok [true false false true true]]
        (usage/append-event! (usage/event (assoc base-event :ok ok))))
      (let [first-run (rsi/propose! {:min-events 2})
            second-run (rsi/propose! {:min-events 2})
            text (slurp (str (fs/path home "brain" "improvements.md")))]
        (is (= 1 (:added first-run)))
        (is (zero? (:added second-run)))
        (is (= 1 (:skipped second-run)))
        (is (str/includes? text "provider-failures"))))))

(deftest cli-e2e-digest-runs-subprocess
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event base-event)))
    (let [result (p/shell {:out :string
                           :err :string
                           :continue true
                           :env {"OPENCRABS_HOME" home}}
                          "bb rsi digest")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) ":fake")))))
