(ns bb-agent.release
  "Changelog-driven release cutting (V6, bk-5e88). The contract: a clean
   tree and a `## Unreleased` section in CHANGELOG.md; `bb release vX.Y.Z`
   promotes Unreleased to a dated section, bumps version.clj, commits and
   tags. Pushing is a separate, explicit `git push` — cutting a release is
   reversible, publishing one isn't."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn git!
  "One git command; trimmed stdout. continue?=true tolerates failure."
  ([args] (git! true args))
  ([continue? args]
   (let [res (apply p/shell (cond-> {:out :string :err :string}
                              continue? (assoc :continue true))
                    "git" args)]
     (str/trim (str (:out res))))))

(defn clean-tree? []
  (str/blank? (git! true ["status" "--porcelain"])))

(defn promote-changelog
  "Unreleased → dated section. Returns the new changelog, or nil when
   there's no Unreleased section (a release without notes is a lie)."
  [changelog version today]
  (when (str/includes? changelog "## Unreleased")
    (str/replace-first changelog "## Unreleased"
                       (str "## " version " - " today))))

(defn current-version []
  (-> (slurp (io/file "src" "bb_agent" "version.clj"))
      (str/replace #"(?s).*\"([^\"]+)\".*" "$1")))

(defn bump-version-file! [version]
  (spit (io/file "src" "bb_agent" "version.clj")
        (str "(ns bb-agent.version\n"
             "  \"Single source of truth for the running Theseus release. Printed at\n"
             "  poller boot (bk-173e) so 'what version is running' is answered by the\n"
             "  log, not by ps and prayer. Bump together with the git tag.\")\n"
             "(def v \"" version "\")\n")))

(defn plan
  "The dry-run view: what WOULD happen. Pure-ish (reads git, writes nothing)."
  [version]
  [(str "version:      " (current-version) " → " version)
   (str "clean tree:   " (if (clean-tree?) "yes" "NO — commit or stash first"))
   (str "changelog:    " (if (str/includes? (slurp "CHANGELOG.md") "## Unreleased")
                          "## Unreleased present — will be dated"
                          "NO ## Unreleased section — refusing"))
   "git:          promote changelog + bump version → commit → tag"])

(defn run! [args]
  (let [[version & flags] args
        dry-run? (some #{"--dry-run"} flags)
        today (str (java.time.LocalDate/now))]
    (cond
      (or (str/blank? version) (str/starts-with? version "--"))
      (println "Usage: bb release vX.Y.Z [--dry-run]")

      (not (str/starts-with? version "v"))
      (println "Version must start with 'v' (e.g. v1.0.0)")

      :else
      (if dry-run?
        (do (println (str "🔎 dry run — release plan for " version))
            (doseq [line (plan version)] (println "  " line)))
        (do
          (when-not (clean-tree?)
            (println "🚫 tree is dirty — commit or stash first")
            (System/exit 1))
          (let [changelog (slurp "CHANGELOG.md")]
            (if-let [promoted (promote-changelog changelog version today)]
              (do (spit "CHANGELOG.md" promoted)
                  (bump-version-file! version)
                  (git! false ["add" "CHANGELOG.md" "src/bb_agent/version.clj"])
                  (git! false ["commit" "-q" "-m" (str "release " version)])
                  (git! false ["tag" version])
                  (println (str "🏷  " version " cut — changelog dated, version bumped, tag created."))
                  (println "Push when ready: git push origin main --follow-tags"))
              (do (println "🚫 CHANGELOG.md has no ## Unreleased section — write the notes first.")
                  (System/exit 1)))))))))
