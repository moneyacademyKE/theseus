(ns bb-agent.goal.git
  "Checkpoint/rollback via git tags.

  Per decision-log D3: git is the preferred checkpoint mechanism --
  ubiquitous, reversible, cheap. The runner tags before every act; on stall it
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
  "Create/move a checkpoint tag to HEAD. Returns tag name, or nil if not a
  repo (degraded no-op checkpoint per D3). Throws on a failed tag: a silent
  failure would leave the tag pointing at a stale commit, so a later
  rollback! would rewind legitimate progress instead of undoing one act."
  [workdir tag-name]
  (when (git-repo? workdir)
    (let [res (git workdir "tag" "-f" tag-name)]
      (when-not (:ok? res)
        (throw (ex-info (str "checkpoint tag failed: " (:err res))
                        {:workdir workdir :tag tag-name :git-err (:err res)})))
      tag-name)))

(defn rollback!
  "Hard-reset workdir to a tag. Returns the git result map {:ok? :out :err}
  on a repo (check :ok? -- false means the reset FAILED and the world is
  untrusted), or nil if not a repo (degraded mode)."
  [workdir tag-name]
  (when (git-repo? workdir)
    (git workdir "reset" "--hard" tag-name)))

(defn next-tag "Generate a unique checkpoint tag name." [prefix]
  (str prefix "-" (System/currentTimeMillis)))
