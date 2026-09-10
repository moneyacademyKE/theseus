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
                                               :fallback-served :fake
                                               :fallback-tried [{:fallback/provider :anthropic
                                                                 :fallback/kind :infra
                                                                 :fallback/reason "status 503"}])))
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
                                                  :ok true :fallback-served :fake
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
      (is (str/includes? (:out result) ":fake")))))

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
      (usage/append-event! (usage/event (assoc base-event :fallback-served :fake)))
      (usage/append-event! (usage/event (verified-rescue base-event :fake "rescuer-y")))
      (let [d (rsi/digest)]
        (is (= 1 (:fallback-hits (get (:providers d) :fake)))
            "only the event with a real failure behind it counts")))))

(deftest analyze-ignores-phantom-fallback-pressure
  (let [home (temp-home!)]
    (with-redefs [bb-agent.config/home (constantly home)]
      (dotimes [_ 60]
        (usage/append-event! (usage/event (assoc base-event :ok true
                                                  :fallback-served :fake))))
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
        (usage/append-event! (usage/event (verified-rescue base-event :fake "rescuer-y"))))
      (usage/append-event! (usage/event (assoc base-event :ok true)))
      (let [op (->> (rsi/analyze {:min-events 2})
                    :opportunities
                    (filter #(= :fallback-pressure (:kind %)))
                    first)]
        (is (= "rescuer-y" (:model op)) "the opportunity names what actually answered")
        (is (str/includes? (:suggestion op) "rescuer-y")
            "the suggestion is actionable: it names a model to promote")))))
