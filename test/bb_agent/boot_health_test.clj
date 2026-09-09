(ns bb-agent.boot-health-test
  "bk-1825 + bk-e6ff: boot health check surfacing and poller log cap."
  (:require [babashka.fs :as fs]
            [bb-agent.doctor :as doctor]
            [bb-agent.log-cap :as log-cap]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest telegram-config-check
  (testing "usable telegram config passes"
    (is (= :ok (:status (doctor/check-telegram-config
                         {:telegram {:token "123:abc"}})))))
  (testing "missing :telegram section errors loudly"
    (let [c (doctor/check-telegram-config {})]
      (is (= :error (:status c)))
      (is (str/includes? (:message c) "missing :telegram"))))
  (testing "blank token errors loudly"
    (let [c (doctor/check-telegram-config {:telegram {:token ""}})]
      (is (= :error (:status c)))
      (is (str/includes? (:message c) ":token")))))

(deftest degraded-summary-shape
  (testing "no errors → nil (nothing to announce)"
    (is (nil? (doctor/degraded-summary
               [{:status :ok :check :a :message "fine"}
                {:status :warning :check :b :message "meh"}]))))
  (testing "errors → one loud string naming each failing check"
    (let [s (doctor/degraded-summary
             [{:status :ok :check :a :message "fine"}
              {:status :error :check :telegram-config :message "no token"}
              {:status :error :check :config-parse :message "bad edn"}])]
      (is (string? s))
      (is (str/includes? s "BOOT DEGRADED"))
      (is (str/includes? s "2 health check"))
      (is (str/includes? s "telegram-config"))
      (is (str/includes? s "config-parse"))
      (is (not (str/includes? s ":a"))))))

(defn- write-lines!
  "n numbered lines, each ~64 bytes, returning the full expected content."
  [path n]
  (let [content (apply str (map #(format "line-%06d padding-padding-padding-padding-padding-pad\n" %)
                                (range n)))]
    (spit (str path) content)
    content))

(deftest log-cap-noop-cases
  (testing "missing file → nil, no throw"
    (is (nil? (log-cap/cap-log! (fs/path (fs/create-dirs "/tmp/bk-e6ff-missing")
                                         "nope.log")
                                100 50))))
  (testing "small file → nil, content untouched"
    (let [dir (fs/create-dirs "/tmp/bk-e6ff-small")
          f (fs/path dir "small.log")
          content (write-lines! f 3)]
      (is (nil? (log-cap/cap-log! f 100000 50000)))
      (is (= content (slurp (str f)))))))

(deftest log-cap-truncates-keeping-whole-line-tail
  (let [dir (fs/create-dirs "/tmp/bk-e6ff-big")
        f (fs/path dir "big.log")
        content (write-lines! f 4000) ;; ~256 KB
        max-bytes 100000
        keep-bytes 50000
        report (log-cap/cap-log! f max-bytes keep-bytes)
        capped (slurp (str f))
        lines (str/split-lines capped)]
    (testing "it reports the cap with before/after sizes"
      (is (:capped? report))
      (is (= (fs/size f) (:after-bytes report)))
      (is (< (:after-bytes report) (:before-bytes report))))
    (testing "result is roughly keep-bytes, not the old size"
      (is (< (count capped) (+ keep-bytes 200)))
      (is (> (count capped) (- keep-bytes 5000))))
    (testing "starts with a marker recording the drop"
      (is (str/starts-with? (first lines) "…[log capped at boot: dropped ")))
    (testing "tail begins on a complete original line, never a fragment"
      (let [tail-lines (rest lines)]
        (is (every? #(re-matches #"line-\d{6} .*" %) tail-lines))))
    (testing "newest content survives verbatim"
      (is (str/ends-with? capped "line-003999 padding-padding-padding-padding-padding-pad\n")))))

(deftest log-cap-never-throws
  (testing "a directory where the log should be → nil, no throw"
    (is (nil? (log-cap/cap-log! (fs/create-dirs "/tmp/bk-e6ff-dir") 10 5)))))
