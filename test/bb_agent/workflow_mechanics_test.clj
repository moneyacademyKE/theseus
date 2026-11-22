(ns bb-agent.workflow-mechanics-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.skill :as skill]
            [bb-agent.tool :as tool]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest skill-globs-parsing-and-matching
  (let [raw "---\nname: clj-discipline\ndescription: Clojure rules\nglobs: *.clj, src/**/*.edn\n---\nAlways use pure functions.\n"
        parsed (skill/parse-skill raw)]
    (is (= ["**/*.clj" "*.clj" "src/**/*.edn"]
           (vec (sort (:globs parsed)))))
    (is (= ["clj-discipline"]
           (mapv :name (skill/matching-skills-for-path [parsed] "src/bb_agent/core.clj"))))
    (is (empty? (skill/matching-skills-for-path [parsed] "README.md")))))

(deftest skill-gate-blocks-once-injects-body-then-allows-retry
  (let [dir (fs/create-temp-dir {:prefix "theseus-skill-gate-"})
        target (fs/path dir "app.clj")
        sk (skill/parse-skill "---\nname: clj-rules\ndescription: Clojure safety\nglobs: *.clj\n---\nRule 1: Keep it pure.\n")
        seen (atom #{})
        cfg {:approval/mode :auto-all
             :skill/skills [sk]
             :skill/seen seen}]
    (try
      (with-redefs [config/home (constantly (str dir))]
        (spit (str target) "(def x 1)\n")
        (testing "first edit on matching glob is blocked with skill body injected"
          (let [r1 (tool/handle-tool-request
                    {:tool/name "edit_file"
                     :tool/args {:path (str target) :old_text "1" :new_text "2"}}
                    cfg)]
            (is (= :denied (:status r1)))
            (is (= :skill-gate (:denial/source r1)))
            (is (= "clj-rules" (:skill/name r1)))
            (is (str/includes? (str (:skill/body r1)) "Rule 1: Keep it pure."))
            (is (= "(def x 1)\n" (slurp (str target))) "file untouched on gate block")
            (is (contains? @seen "clj-rules"))))
        (testing "immediate retry succeeds because skill is now seen in this epoch"
          (let [r2 (tool/handle-tool-request
                    {:tool/name "edit_file"
                     :tool/args {:path (str target) :old_text "1" :new_text "2"}}
                    cfg)]
            (is (= :ok (:status r2)))
            (is (= "(def x 2)\n" (slurp (str target)))))))
      (finally
        (fs/delete-tree dir)))))

(deftest parallel-read-only-tool-batching-preserves-order-and-runs-concurrently
  (let [dir (fs/create-temp-dir {:prefix "theseus-par-tools-"})
        f1 (fs/path dir "a.txt")
        f2 (fs/path dir "b.txt")
        f3 (fs/path dir "c.txt")
        in-flight (atom 0)
        peak-in-flight (atom 0)
        round (atom 0)]
    (try
      (spit (str f1) "AAA")
      (spit (str f2) "BBB")
      (spit (str f3) "CCC")
      (with-redefs [config/home (constantly (str dir))
                    provider/complete
                    (fn [_ _req]
                      (let [n (swap! round inc)]
                        (if (= 1 n)
                          {:role :assistant
                           :tool/requests [{:tool/name "read_file" :tool/args {:path (str f1)}}
                                           {:tool/name "read_file" :tool/args {:path (str f2)}}
                                           {:tool/name "read_file" :tool/args {:path (str f3)}}]}
                          {:role :assistant :content "done"})))
                    tool/handle-tool-request
                    (let [orig tool/handle-tool-request]
                      (fn [req cfg]
                        (let [cur (swap! in-flight inc)]
                          (swap! peak-in-flight max cur)
                          (Thread/sleep 40)
                          (try
                            (orig req cfg)
                            (finally
                              (swap! in-flight dec))))))]
        (let [turn (core/run-turn! {:provider :fake
                                    :model "test-model"
                                    :approval/mode :auto-safe
                                    :session/id "par-test"}
                                   "read all three")]
          (is (= "done" (:assistant/final turn)))
          (is (= ["AAA" "BBB" "CCC"] (mapv :content (:tool/results turn)))
              "results preserve exact request order")
          (is (>= @peak-in-flight 2)
              (str "expected concurrent execution, peak in-flight was " @peak-in-flight))))
      (finally
        (fs/delete-tree dir)))))

(deftest anthropic-marks-last-tool-with-ephemeral-cache-control
  (let [captured (atom nil)
        reply {:status 200
               :body (json/generate-string
                      {:content [{:type "text" :text "ok"}]
                       :stop_reason "end_turn"
                       :usage {:input_tokens 10 :output_tokens 2}})}]
    (with-redefs [http/post (fn [_url opts]
                              (reset! captured (json/parse-string (:body opts) true))
                              reply)]
      (provider/complete :anthropic-compatible
                         {:model "claude-sonnet-4"
                          :messages [{:role :system :content "sys"}
                                     {:role :user :content "hi"}]
                          :provider/config {:base-url "http://127.0.0.1:1"
                                            :api-key "sk-test"}}))
    (let [tools-sent (:tools @captured)]
      (is (>= (count tools-sent) 2))
      (is (= {:type "ephemeral"} (:cache_control (peek (vec tools-sent))))
          "last tool schema carries ephemeral cache_control")
      (is (nil? (:cache_control (first tools-sent)))
          "earlier tool schemas do not waste cache breakpoints"))))
