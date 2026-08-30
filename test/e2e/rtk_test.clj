(ns e2e.rtk-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.rtk :as rtk]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private ansi-sample "\u001B[31mred\u001B[0m plain")

(def ^:private noise-rule
  {:name "noisy"
   :cmd #"^noisy-cmd\b"
   :drop-lines [#"^DROPME"]
   :max-lines 2})

(defn- temp-home! []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "state"))
    dir))

(deftest strips-ansi-escapes
  (is (= "red plain" (rtk/strip-ansi ansi-sample))))

(deftest drops-matching-lines
  (is (= "keep1\nkeep2"
         (rtk/compact-text "keep1\nDROPME x\nkeep2" noise-rule))))

(deftest caps-lines-with-marker
  (let [out (rtk/compact-text "a\nb\nc\nd" noise-rule)]
    (is (str/starts-with? out "a\nb"))
    (is (str/includes? out "[rtk: 4\u21922 lines]"))))

(deftest on-empty-message
  (is (= "(no output)"
         (rtk/compact-text "DROPME only\nDROPME too"
                           (assoc noise-rule :on-empty "(no output)")))))

(deftest select-rule-picks-first-match
  (let [rules [noise-rule {:name "other" :cmd #"noisy-cmd-wide"}]]
    (is (= "noisy" (:name (rtk/select-rule "noisy-cmd --all" rules))))
    (is (nil? (rtk/select-rule "unrelated-cmd" rules)))))

(deftest default-rules-include-opencrabs-set
  (is (contains? (set (map :name rtk/default-rules)) "ps"))
  (is (contains? (set (map :name rtk/default-rules)) "lsof"))
  (is (contains? (set (map :name rtk/default-rules)) "git-log"))
  (is (contains? (set (map :name rtk/default-rules)) "dig")))

(deftest user-rules-load-from-home
  (let [home (temp-home!)
        rules-edn "[{:name \"user-rule\" :cmd #\"^user-cmd\\b\"}]"]
    (spit (str (fs/path home "rtk-filters.edn")) rules-edn)
    (with-redefs [config/home (constantly (str home))]
      (let [names (set (map :name (rtk/rules)))]
        (is (contains? names "user-rule"))
        (is (contains? names "ps"))))))

(deftest shell-e2e-compacts-when-enabled
  (let [home (temp-home!)
        rules-edn "[{:name \"noisy\" :cmd #\"^printf\\b\"
                     :drop-lines [#\"^DROPME\"] :max-lines 2}]"]
    (spit (str (fs/path home "rtk-filters.edn")) rules-edn)
    (with-redefs [config/home (constantly (str home))]
      (let [result (tool/handle-tool-request
                    {:tool/name "shell"
                     :approval/policy :auto-all
                     :tool/args {:cmd "printf 'a\\nDROPME-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\\nb\\nDROPME-yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy\\nc\\nd\\n'"}}
                    {:rtk {:enabled true}})]
        (is (= :ok (:status result)))
        (is (not (str/includes? (:stdout result) "DROPME")))
        (is (str/includes? (:stdout result) "[rtk: 4\u21922 lines]"))
        (is (= "noisy" (get-in result [:rtk :rule])))
        (is (> (get-in result [:rtk :raw-chars]) (get-in result [:rtk :out-chars])))))))

(deftest shell-e2e-raw-when-disabled
  (let [home (temp-home!)
        rules-edn "[{:name \"noisy\" :cmd #\"^printf\\b\"
                     :drop-lines [#\"^DROPME\"] :max-lines 2}]"]
    (spit (str (fs/path home "rtk-filters.edn")) rules-edn)
    (with-redefs [config/home (constantly (str home))]
      (let [result (tool/handle-tool-request
                    {:tool/name "shell"
                     :approval/policy :auto-all
                     :tool/args {:cmd "printf 'a\\nDROPME\\nb\\n'"}}
                    {})]
        (is (= :ok (:status result)))
        (is (str/includes? (:stdout result) "DROPME"))
        (is (nil? (get result :rtk)))))))

(deftest shell-e2e-untouched-when-no-rule-matches
  (let [home (temp-home!)]
    (with-redefs [config/home (constantly (str home))]
      (let [result (tool/handle-tool-request
                    {:tool/name "shell"
                     :approval/policy :auto-all
                     :tool/args {:cmd "printf 'hello\\n'"}}
                    {:rtk {:enabled true}})]
        (is (= :ok (:status result)))
        (is (str/includes? (:stdout result) "hello"))
        (is (nil? (get result :rtk)))))))
