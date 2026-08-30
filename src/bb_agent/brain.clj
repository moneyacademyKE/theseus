(ns bb-agent.brain
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(defn brain-dir []
  (fs/path (config/home) "brain"))

(defn load-brain
  "Concatenate every .md file in <home>/brain, sorted by filename,
  each under a `## filename` header. Empty string when the directory
  is missing or holds no markdown — the presence of files is the
  gate; no config flag needed."
  ([]
   (load-brain (str (brain-dir))))
  ([dir]
   (if-not (fs/exists? dir)
     ""
     (let [files (sort-by fs/file-name (fs/glob dir "*.md"))]
       (if (seq files)
         (str/join "\n\n"
                   (map (fn [f]
                          (str "## " (fs/file-name f) "\n" (slurp (str f))))
                        files))
         "")))))
