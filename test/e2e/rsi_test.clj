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

;; Signal tests use a neutral provider name: :fake is RSI's default
;; excluded test double, so a fixture written against it would be testing
;; a provider the analyzer is configured to ignore.
(def ^:private base-event
  {:session-id "s1" :provider :test-provider :model "fake-model"
   :prompt "p" :final "f"})

(deftest event-carries-ok-and-roundtrips
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (let [ev (usage/append-event! (usage/event (assoc base-event :ok true)))
            loaded (usage/load-events)]
        (is (true? (:ok (last loaded))))
        (is (true? (:ok (first loaded))))
        (is (= :test-provider (:provider (first loaded))))))))

(deftest digest-aggregates-per-provider
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (usage/append-event! (usage/event (assoc base-event :ok false)))
      (usage/append-event! (usage/event (assoc base-event :ok true
                                               :provider :anthropic
                                               :fallback-served :test-provider
                                               :fallback-tried [{:fallback/provider :anthropic
                                                                 :fallback/kind :infra
                                                                 :fallback/reason "status 503"}])))
      (let [d (rsi/digest)
            stub (get (:providers d) :test-provider)]
        (is (= 3 (:events d)))
        (is (= {:turns 2 :ok 1 :fail 1 :fallback-hits 1} stub))))))

(deftest digest-excludes-configured-noise-providers
  "A test double's failures are fixtures, not a fact about the world. Left
   in the signal, :fake's 6 synthetic failures already read 27% against a
   30% flag — one more e2e run and RSI proposes fixing a provider that
   doesn't exist. Event counts stay honest; only the signal is filtered."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 3]
        (usage/append-event! (usage/event (assoc base-event :provider :fake :ok false))))
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (let [d (rsi/digest)]
        (is (= 4 (:events d)) "excluded events are still counted")
        (is (not (contains? (:providers d) :fake)) ":fake is out of the signal")
        (is (contains? (:providers d) :test-provider))))))

(deftest digest-exclusion-is-config-driven
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)
                  bb-agent.config/load-config (constantly {:rsi/exclude-providers #{:test-provider}})]
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (usage/append-event! (usage/event (assoc base-event :provider :fake :ok true)))
      (let [d (rsi/digest)]
        (is (= 2 (:events d)))
        (is (not (contains? (:providers d) :test-provider))
            "config replaces the default set, it doesn't add to it")
        (is (contains? (:providers d) :fake))))))

(deftest nearest-signals-omit-excluded-providers
  "A quiet cycle reports its near-misses. A test double must not be one
   of them — that's the noise the exclusion exists to remove."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event (assoc base-event :provider :fake :ok false)))
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (let [r (rsi/cycle! {:min-events 2})]
        (is (not (some #(= :fake (:provider %)) (:nearest-signals r))))))))

(deftest write-digest-creates-readable-file
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event base-event))
      (let [path (rsi/write-digest!)
            text (slurp (str path))]
        (is (fs/regular-file? path))
        (is (str/includes? text ":test-provider"))
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

(deftest analyze-never-flags-an-excluded-provider
  "The whole point: a failing test double must not generate a proposal,
   no matter how loud its failure rate is."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [ok [false false false false]]
        (usage/append-event! (usage/event (assoc base-event :provider :fake :ok ok))))
      (doseq [ok [true true]]
        (usage/append-event! (usage/event (assoc base-event :ok ok))))
      (let [{:keys [opportunities]} (rsi/analyze {:min-events 2})]
        (is (empty? opportunities) "100% failure on a test double is not news")))))

(deftest analyze-flags-fallback-pressure
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [_ [1 2]]
        (usage/append-event! (usage/event (assoc base-event :provider :anthropic
                                                  :ok true :fallback-served :test-provider
                                                  :fallback-tried [{:fallback/provider :anthropic
                                                                    :fallback/kind :infra
                                                                    :fallback/reason "status 503"}]))))
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
      (is (str/includes? (:out result) ":test-provider")))))

(deftest cycle-writes-digest-and-proposals
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [ok [true false false true true]]
        (usage/append-event! (usage/event (assoc base-event :ok ok))))
      (let [first-run (rsi/cycle! {:min-events 2})
            second-run (rsi/cycle! {:min-events 2})]
        (is (= 1 (:opportunities first-run)))
        (is (= 1 (:added first-run)))
        (is (fs/regular-file? (fs/path home "state" "rsi" "digest.md")))
        (is (str/includes? (slurp (str (fs/path home "brain" "improvements.md")))
                           "provider-failures"))
        (is (zero? (:added second-run)))
        (is (= 1 (:skipped second-run)))))))

(deftest cycle-quiet-carries-nearest-signals
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 3]
        (usage/append-event! (usage/event (assoc base-event :ok true))))
      (let [r (rsi/cycle! {:min-events 2})]
        (is (zero? (:opportunities r)))
        (is (seq (:nearest-signals r)))
        (is (zero? (:fallback-rate (first (:nearest-signals r)))))))))

(deftest cycle-dry-run-writes-no-proposals
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (doseq [ok [true false false true true]]
        (usage/append-event! (usage/event (assoc base-event :ok ok))))
      (let [r (rsi/cycle! {:min-events 2 :propose? false})]
        (is (pos? (:opportunities r)))
        (is (zero? (:added r)))
        (is (not (fs/regular-file? (fs/path home "brain" "improvements.md"))))))))

(deftest cli-e2e-cycle-dry-run-subprocess
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 50]
        (usage/append-event! (usage/event (assoc base-event :ok true)))))
    (let [result (p/shell {:out :string
                           :err :string
                           :continue true
                           :env {"OPENCRABS_HOME" home}}
                          "bb rsi cycle --dry-run")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "dry-run"))
      (is (not (fs/regular-file? (fs/path home "brain" "improvements.md")))))))

(defn- verified-rescue [base served model]
  (assoc base :ok true
         :fallback-served served
         :fallback-tried [{:fallback/provider :primary
                           :fallback/kind :infra
                           :fallback/reason "Provider request failed with status 503"}]
         :fallback-model model))

(deftest digest-counts-only-verified-rescues
  "A :fallback/served tag with no :fallback/tried ledger behind it is a
  first-try success wearing a costume, not a rescue. The live ledger had
  63 of them; counting them reported 31% fallback pressure where the
  real rate was 1-in-195."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (usage/append-event! (usage/event (assoc base-event :fallback-served :test-provider)))
      (usage/append-event! (usage/event (verified-rescue base-event :test-provider "rescuer-y")))
      (let [d (rsi/digest)]
        (is (= 1 (:fallback-hits (get (:providers d) :test-provider)))
            "only the event with a real failure behind it counts")))))

(deftest analyze-ignores-phantom-fallback-pressure
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 60]
        (usage/append-event! (usage/event (assoc base-event :ok true
                                                  :fallback-served :test-provider))))
      (let [{:keys [opportunities]} (rsi/analyze {:min-events 2})]
        (is (not (contains? (set (map :kind opportunities)) :fallback-pressure))
            "60 unverified tags are not 60 rescues")))))

(deftest analyze-fallback-pressure-names-the-serving-model
  "The live chain is model-level — same provider on every step — so a
  provider-granular signal can't say what to promote. 'Promote
  :openai-compatible' is a no-op when :openai-compatible is already the
  primary."
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 2]
        (usage/append-event! (usage/event (verified-rescue base-event :test-provider "rescuer-y"))))
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (let [op (->> (rsi/analyze {:min-events 2})
                    :opportunities
                    (filter #(= :fallback-pressure (:kind %)))
                    first)]
        (is (= "rescuer-y" (:model op)) "the opportunity names what actually answered")
        (is (str/includes? (:suggestion op) "rescuer-y")
            "the suggestion is actionable: it names a model to promote")))))
