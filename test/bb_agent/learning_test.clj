(ns bb-agent.learning-test
  (:require [babashka.fs :as fs]
            [bb-agent.beliefs :as beliefs]
            [bb-agent.config :as config]
            [bb-agent.memory :as memory]
            [bb-agent.turn-signals :as signals]
            [bb-agent.usage :as usage]
            [clojure.test :refer [deftest is testing]]))

(deftest belief-claim-matching-dedupes-and-flags-contradictions
  (testing "same claim with synonyms deduplicates and promotes status"
    (is (true? (beliefs/same-claim? "I prefer Babashka scripting"
                                    "I like bb scripting")))
    (is (true? (beliefs/contradicts? "use npm for builds"
                                     "never use npm for builds"))))
  (testing "memory/add-memory! promotes repeated claims and contests contradictions"
    (let [home (fs/create-temp-dir {:prefix "theseus-beliefs-"})]
      (try
        (with-redefs [config/home (constantly (str home))]
          (let [m1 (memory/add-memory! "use concise replies")
                m2 (memory/add-memory! "prefer brief replies")
                m3 (memory/add-memory! "like short replies")]
            (is (= (:memory/id m1) (:memory/id m2) (:memory/id m3))
                "reworded duplicate merges into existing belief instead of duplicating")
            (is (= :confirmed (:memory/status m3)))
            (is (= 3 (:memory/evidence-count m3))))
          (let [_contra (memory/add-memory! "never use concise replies")
                all (memory/load-memories)
                hits (memory/search-memories "concise replies" 5)]
            (is (= 2 (count all)) "older claim preserved non-destructively as :contested")
            (is (= :contested (:memory/status (first all))))
            (is (= 1 (count hits)) "search excludes :contested older claim")
            (is (= "never use concise replies" (:memory/text (first hits))))))
        (finally
          (fs/delete-tree home))))))

(deftest turn-signals-extracts-corrections-brevity-autonomy-and-denials
  (is (nil? (signals/extract-turn-signals {:prompt "what time is it?"
                                           :prev-prompts ["hello"]
                                           :tool-results [{:status :ok}]})))
  (let [s (signals/extract-turn-signals
           {:prompt "no, I meant keep it shorter and don't ask"
            :prev-prompts ["previous prompt"]
            :tool-results [{:status :denied} {:status :ok}]})]
    (is (true? (:correction? s)))
    (is (= :shorter (:brevity s)))
    (is (= :just-do-it (:autonomy s)))
    (is (= 1 (:approval-denials s))))
  (let [rep (signals/extract-turn-signals
             {:prompt "run the tests!"
              :prev-prompts ["Run the tests"]
              :tool-results []})]
    (is (true? (:repeated-prompt? rep)))))

(deftest usage-event-carries-turn-signals-when-present
  (let [ev (usage/event {:session-id "s1" :provider :openai-compatible :model "m"
                         :prompt "no, I meant shorter" :final "ok"
                         :signals {:correction? true :brevity :shorter}})]
    (is (= {:correction? true :brevity :shorter} (:turn/signals ev)))))
