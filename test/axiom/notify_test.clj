(ns axiom.notify-test
  "Phase 3 -- notifier abstraction tests.

  Targets the PURE `build-message` (no I/O) and the `notify!` dispatch,
  including a `:transport` override that lets a test inject a fake transport
  fn (no live HTTP is ever exercised). The :noop transport is also covered
  directly (returns the message, no network)."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.notify :as notify]
            [axiom.core :as core]
            [axiom.log :as log]
            [babashka.fs :as fs]))

;; ---------- build-message (pure) ----------

(def halt-action
  {:type :halt :reason :no-convergence :iterations 3
   :world {:level-count 0 :build-ok "pass"}})

(def bundle
  {:ts "2026-07-07T00:00:00Z" :reason :no-convergence :halt halt-action
   :iterations [{:event :halt :iteration 3}]})

(deftest build-message-captures-the-halt-essentials
  (let [cfg {:name "demo-notify" :log-dir "/tmp/x"}
        msg (notify/build-message cfg halt-action bundle)]
    (is (= "demo-notify" (:name msg)))
    (is (= "no-convergence" (:reason msg)))
    (is (= 3 (:iterations msg)) "the halt iteration count is surfaced")
    (is (= {:level-count 0 :build-ok "pass"} (:last-world msg)))
    (is (= "/tmp/x/halt-bundle.edn" (:bundle-path msg)))
    (is (= "2026-07-07T00:00:00Z" (:ts msg)))))

(deftest build-message-tolerates-absent-world-and-bundle
  (let [cfg {:name "demo-sparse"}
        msg (notify/build-message cfg {:type :halt :reason :stall} nil)]
    (is (= "stall" (:reason msg)))
    (is (nil? (:last-world msg)) "nil bundle -> no world derived")
    (is (nil? (:ts msg)) "nil bundle -> no ts")
    (is (nil? (:bundle-path msg)) "no :log-dir -> no bundle path")))

;; ---------- notify! dispatch (no live network) ----------

(defn- fake-transport
  "Capture the message handed to the transport; return a tag identifying it
  ran. Proves the :transport override is used INSTEAD of :type dispatch."
  [atom]
  (fn [msg] (reset! atom msg) ::fake-transport-ran))

(deftest notify-noop-is-absent-config
  (testing "no :notify key -> notify! is a no-op returning nil"
    (is (nil? (notify/notify! {:name "x"} halt-action bundle)))))

(deftest notify-noop-transport-returns-the-message
  (testing ":type :noop -> the built message is returned, no network touched"
    (let [res (notify/notify! {:name "demo" :notify {:type :noop}}
                              halt-action bundle)]
      (is (= "no-convergence" (:reason res)))
      (is (= "demo" (:name res))))))

(deftest notify-transport-override-injects-a-fake-transport
  (testing "a :transport fn overrides :type dispatch (testability hook, no HTTP)"
    (let [captured (atom nil)
          res (notify/notify! {:name "demo-override"
                               :notify {:type :http :url "http://example.invalid"
                                        :transport (fake-transport captured)}}
                             halt-action bundle)]
      (is (= ::fake-transport-ran res)
          "the fake transport's return value is what notify! returns")
      (is (= "no-convergence" (:reason @captured))
          "the fake transport received the built message"))))

(deftest notify-end-to-end-through-a-halt
  (testing "the real run! halts and fires notify! via a captured transport"
    (let [dir (str (fs/create-temp-dir))
          log-dir (str dir "/logs")
          captured (atom nil)]
      (try
        (spit (str dir "/act.count") "0\n")
        ;; drive the real loop: a non-progressing act that halts on stall,
        ;; with a :notify spec whose :transport captures the message.
        (let [cfg {:name "demo-e2e" :workdir dir
                   :lock (str dir "/.axiom.lock") :log-dir log-dir
                   :observers {:level-count {:sh "echo 0" :parse :int}}
                   :goal {:op :>= :ref :level-count :value 3}
                   :integrity [] :progress :level-count
                   :act {:sh "n=$(cat act.count 2>/dev/null||echo 0); echo $((n+1))>act.count"}
                   :stall-after 2 :max-rollbacks 0
                   :notify {:type :http :url "http://invalid"
                            :transport (fake-transport captured)}}
              res (core/run! cfg {:max-iters 20})]
          (is (= :halt (:status res)))
          (is (some? @captured) "notify! fired and handed the transport a message")
          (is (= "stall" (:reason @captured))
              "the captured message carries the actual halt reason"))
        (finally (fs/delete-tree dir))))))

(defn -main
  "Run the Phase 3 notify tests; exit non-zero on any failure or error."
  [& _]
  (let [summary (clojure.test/run-tests 'axiom.notify-test)]
    (System/exit (if (pos? (+ (:fail summary 0) (:error summary 0))) 1 0))))