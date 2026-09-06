(ns bb-agent.brain-test
  (:require [babashka.fs :as fs]
            [bb-agent.brain :as brain]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- tmp-brain
  "Temp brain dir with an empty knowledge/ subdir."
  []
  (let [d (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path d "knowledge"))
    d))

(deftest knowledge-index-uses-owns-line
  (let [d (tmp-brain)
        k (fs/path d "knowledge" "30-jobs.md")]
    (spit (str k) "# 30-jobs\n> **Owns:** durable job queue facts.\n\nbody")
    (is (= ["30-jobs.md — > **Owns:** durable job queue facts."]
           (brain/knowledge-index d)))))

(deftest knowledge-index-falls-back-to-first-line
  (let [d (tmp-brain)]
    (spit (str (fs/path d "knowledge" "40-notes.md")) "\nFirst line scope\n")
    (is (= ["40-notes.md — First line scope"]
           (brain/knowledge-index d)))))

(deftest knowledge-index-empty-when-missing
  (let [d (str (fs/create-temp-dir))]
    (is (= [] (brain/knowledge-index d)))
    (is (= [] (brain/knowledge-index (fs/path d "nowhere"))))))

(deftest identity-files-never-indexed
  (let [d (tmp-brain)]
    (spit (str (fs/path d "00-soul.md")) "soul")
    (is (= [] (brain/knowledge-index d)))))

(deftest context-includes-both-layers
  (let [d (tmp-brain)]
    (spit (str (fs/path d "00-soul.md")) "soul-text")
    (spit (str (fs/path d "knowledge" "30-jobs.md")) "Owns: jobs")
    (let [ctx (brain/load-brain-context d)]
      (is (str/includes? ctx "## 00-soul.md"))
      (is (str/includes? ctx "soul-text"))
      (is (str/includes? ctx "index only"))
      (is (str/includes? ctx "30-jobs.md")))))

(deftest context-without-knowledge-is-plain-load
  (let [d (tmp-brain)]
    (spit (str (fs/path d "00-soul.md")) "soul-text")
    (is (= (brain/load-brain d)
           (brain/load-brain-context d)))))
