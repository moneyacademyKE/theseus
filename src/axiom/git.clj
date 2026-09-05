(ns axiom.git
  "Checkpoint/rollback via git tags.

  Per decision-log D3: git is the preferred checkpoint mechanism --
  ubiquitous, reversible, cheap. Axiom tags before every act; on stall it
  resets hard to the tag so a corrupting act cannot poison the next attempt.
  Repos without git degrade gracefully (no-op checkpoint)."
  (:require [babashka.process :refer [sh]]
            [clojure.string :as str]))

(defn- git
  "Run a git command in workdir. Robust to non-zero exits (returns map).
  {:ok? :out :err}"
  [workdir & args]
  (try
    (let [res (apply sh {:out :string :err :string :dir workdir
                         :continue true} "git" args)]
      {:ok? (zero? (:exit res))
       :out (-> res :out str/trim)
       :err (-> res :err str/trim)})
    (catch Exception e
      {:ok? false :out "" :err (str (.getMessage e))})))

(defn git-repo? "True if workdir is inside a git repo." [workdir]
  (:ok? (git workdir "rev-parse" "--is-inside-work-tree")))

(defn tag!
  "Create/move a checkpoint tag to HEAD. Returns tag name, or nil if not a repo."
  [workdir tag-name]
  (when (git-repo? workdir)
    (git workdir "tag" "-f" tag-name)
    tag-name))

(defn rollback!
  "Hard-reset workdir to a tag. Returns true on success (or nil if not a repo)."
  [workdir tag-name]
  (when (git-repo? workdir)
    (:ok? (git workdir "reset" "--hard" tag-name))))

(defn next-tag "Generate a unique checkpoint tag name." [prefix]
  (str prefix "-" (System/currentTimeMillis)))
