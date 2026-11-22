(ns bb-agent.checkpoints
  "Shadow-git per-turn workspace checkpoints for zero-collision diff/undo.
   Snapshots a workspace into a bare shadow repo under
   `<home>/state/checkpoints/<dir-hash>/.git` by clearing ambient GIT_* env
   vars and passing GIT_DIR / GIT_WORK_TREE — never touching the user's .git."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private excludes-content
  (str ".git/\n"
       "node_modules/\n"
       ".env\n"
       ".env.*\n"
       "*.sqlite\n"
       "*.sqlite-wal\n"
       "*.sqlite-shm\n"
       "*.db\n"
       "target/\n"
       "dist/\n"))

(defn- canonical-path [dir]
  (try
    (str (fs/canonicalize dir))
    (catch Exception _ nil)))

(defn refusal-reason
  "Refuse dangerous root/home/desktop/documents/downloads paths via canonical
   realpath (defeating symlink bypasses). Returns nil when safe to checkpoint."
  [dir]
  (let [canon (canonical-path dir)
        home (canonical-path (System/getProperty "user.home"))]
    (cond
      (nil? canon) :nonexistent-path
      (not (fs/directory? canon)) :not-a-directory
      (= canon "/") :root-dir
      (= canon home) :home-dir
      (some #(= canon (str home "/" %)) ["Desktop" "Documents" "Downloads"]) :user-personal-dir
      :else nil)))

(defn- dir-hash [canon]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes canon StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff (long %))) (take 8 bs)))))

(defn- shadow-git-dir [canon]
  (fs/path (config/home) "state" "checkpoints" (dir-hash canon) ".git"))

(defn- clean-git-env [git-dir work-tree]
  (let [base (into {} (remove (fn [[k _]] (str/starts-with? (str k) "GIT_"))
                              (System/getenv)))]
    (assoc base
           "GIT_DIR" (str git-dir)
           "GIT_WORK_TREE" (str work-tree)
           "GIT_AUTHOR_NAME" "theseus-checkpoint"
           "GIT_AUTHOR_EMAIL" "checkpoint@theseus.local"
           "GIT_COMMITTER_NAME" "theseus-checkpoint"
           "GIT_COMMITTER_EMAIL" "checkpoint@theseus.local")))

(defn- git! [git-dir work-tree & args]
  (apply p/sh {:env (clean-git-env git-dir work-tree) :dir (str work-tree)}
         "git" "-c" "commit.gpgsign=false" "-c" "gc.auto=0" args))

(defn- ensure-shadow-repo! [git-dir]
  (when-not (fs/exists? (fs/path git-dir "HEAD"))
    (fs/create-dirs git-dir)
    (let [clean-env (into {} (remove (fn [[k _]] (str/starts-with? (str k) "GIT_"))
                                     (System/getenv)))]
      (p/sh {:env clean-env} "git" "init" "--bare" "-q" (str git-dir)))
    (let [info-dir (fs/path git-dir "info")]
      (fs/create-dirs info-dir)
      (spit (str (fs/path info-dir "exclude")) excludes-content))))

(defn snapshot!
  "Take a shadow-git checkpoint of `dir`. Returns {:ok? true :commit sha} or
   {:ok? false :reason ...}."
  [dir label]
  (if-let [why (refusal-reason dir)]
    {:ok? false :reason why}
    (let [canon (canonical-path dir)
          gdir (shadow-git-dir canon)]
      (ensure-shadow-repo! gdir)
      (git! gdir canon "add" "-A")
      (git! gdir canon "commit" "-q" "--allow-empty" "-m" (str (or label "checkpoint")))
      (let [rev (git! gdir canon "rev-parse" "HEAD")]
        (if (zero? (:exit rev))
          {:ok? true :commit (str/trim (:out rev)) :shadow-dir (str gdir)}
          {:ok? false :reason :git-commit-failed :stderr (:err rev)})))))

(defn diff-since
  "Return unified diff between checkpoint `commit-sha` and current `dir`."
  [dir commit-sha]
  (let [canon (canonical-path dir)
        gdir (shadow-git-dir canon)
        res (git! gdir canon "diff" (str commit-sha))]
    (str (:out res))))

(defn restore!
  "Restore tracked files in `dir` back to shadow checkpoint `commit-sha`."
  [dir commit-sha]
  (if-let [why (refusal-reason dir)]
    {:ok? false :reason why}
    (let [canon (canonical-path dir)
          gdir (shadow-git-dir canon)
          res (git! gdir canon "checkout" (str commit-sha) "--" ".")]
      {:ok? (zero? (:exit res))
       :commit (str commit-sha)
       :stderr (:err res)})))
