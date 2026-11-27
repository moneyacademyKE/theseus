(ns bb-agent.conflict-blocks-test
  (:require [bb-agent.conflict-blocks :as cb]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest extract-bounded-git-conflict-blocks-test
  (testing "parses standard git conflict markers into structured {:file :ours :theirs} blocks"
    (let [text (str "before\n"
                    "<<<<<<< HEAD\n"
                    "(def x 1)\n"
                    "=======\n"
                    "(def x 2)\n"
                    ">>>>>>> feature\n"
                    "after\n")
          blocks (cb/extract-blocks "src/a.clj" text)]
      (is (= 1 (count blocks)))
      (is (= "src/a.clj" (:file (first blocks))))
      (is (= "(def x 1)" (:ours (first blocks))))
      (is (= "(def x 2)" (:theirs (first blocks))))))

  (testing "caps oversized sides at 40 lines and respects max-blocks"
    (let [many-lines (str/join "\n" (map #(str "line-" %) (range 60)))
          text (str "<<<<<<< HEAD\n" many-lines "\n=======\nshort\n>>>>>>> b\n")
          blocks (cb/extract-blocks "big.clj" text {:max-blocks 5})]
      (is (= 1 (count blocks)))
      (is (str/includes? (:ours (first blocks)) "truncated")))))
