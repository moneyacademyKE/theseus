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

(defn- scope-line
  "One-line scope marker for a knowledge file: its `Owns:` line, else
  the first non-empty line. Nil when the file has neither."
  [path]
  (let [lines (str/split-lines (slurp (str path)))]
    (or (some #(when (re-find #"Owns:" %) (str/trim %)) lines)
        (some #(when (seq (str/trim %)) (str/trim %)) lines))))

(defn knowledge-index
  "One line per brain/knowledge/*.md: `name — scope`. The index rides in
  the system message; full content loads on demand (cat the file) when
  its scope matches the current task. Empty when the dir is missing or
  holds no markdown — top-level brain files stay always-loaded identity."
  ([]
   (knowledge-index (str (brain-dir))))
  ([dir]
   (let [kd (fs/path dir "knowledge")]
     (if-not (fs/exists? (str kd))
       []
       (->> (sort-by fs/file-name (fs/glob (str kd) "*.md"))
            (keep (fn [f]
                    (when-let [scope (scope-line f)]
                      (str (fs/file-name f) " — " scope)))))))))

(defn load-brain-context
  "Identity (brain/*.md) always in-context; knowledge (brain/knowledge/*.md)
  enters as a one-line index with an on-demand instruction, so per-turn cost
  stays flat as knowledge grows. Without knowledge/ this is exactly
  load-brain — existing behavior is the no-knowledge case."
  ([]
   (load-brain-context (str (brain-dir))))
  ([dir]
   (let [idx (knowledge-index dir)]
     (if (seq idx)
       (str (load-brain dir)
            "\n\n## Knowledge files (index only — cat brain/knowledge/<name> when its scope matches the task)\n"
            (str/join "\n" idx))
       (load-brain dir)))))
