(ns bb-agent.skill-economy
  "Skill economy measurement (V4, bk-a1ae): the v0.9.0 dogfood found 1 of
   5 recommended skills was load-bearing. This namespace turns every goal
   workspace's evidence into one honest ledger — claims (`:skills-used` in
   project.edn, gated on read-before-claim by bk-d8a0) vs reads (read_file
   receipts in the authoring session logs) — and names the verdicts:
   LOAD-BEARING (claimed AND read), DECORATION (claimed, never read),
   INVENTORY (read, never claimed).
   Pure functions over file facts; no provider, no Telegram."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn read-project
  "project.edn of a workspace as a map, or nil."
  [ws]
  (let [f (io/file ws "project.edn")]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn claimed-skills
  "The workspace's :skills-used claims (lowercased names). Empty when the
   author declared none — absence is data, not an error."
  [ws]
  (->> (map str/lower-case (map str (:skills-used (read-project ws))))
       (set)))

(defn read-receipts
  "Skill names whose SKILL.md was read during this workspace's authoring:
   scans the workspace's authoring artifacts for skill path mentions.
   Reads authoring.edn/logs evidence files that exist; a workspace with no
   receipts simply has none."
  [ws]
  (let [evidence (filter fs/exists?
                         [(fs/path ws "authoring.edn") (fs/path ws "session.log")
                          (fs/path ws "evidence.log")])
        text (apply str (map (fn [p] (slurp (str p))) evidence))]
    (->> (re-seq #"skills/([a-z0-9_-]+)/SKILL\.md" text)
         (map second)
         (set))))

(defn workspace-report
  "Per-workspace verdict pairs: {:ws :claims :reads}."
  [ws]
  (let [claims (claimed-skills ws)
        reads (read-receipts ws)]
    {:ws (str (fs/file-name (str ws)))
     :claims claims :reads reads
     :load-bearing (set/intersection claims reads)
     :decoration (set/difference claims reads)
     :inventory (set/difference reads claims)}))

(defn fleet-report
  "Aggregate over every workspace in goals-root: skill → {:claims :reads},
   plus the actionable list. Prune candidates = skills claimed somewhere
   but never read anywhere — claims without a single read receipt."
  [goals-root]
  (let [ws-dirs (->> (fs/list-dir goals-root)
                     (filter fs/directory?)
                     (map str))
        reports (map workspace-report ws-dirs)
        bump (fn [acc ks field]
               (reduce (fn [a k] (update-in a [k field] (fnil inc 0))) acc ks))
        tally (let [raw (reduce (fn [acc {:keys [claims reads]}]
                                  (-> acc
                                      (bump claims :claims)
                                      (bump reads :reads)))
                                {} reports)]
            ;; every skill gets both fields — explicit zeros read better
            ;; than missing keys for every consumer downstream
            (into (sorted-map)
                  (map (fn [[k v]] [k {:claims (:claims v 0) :reads (:reads v 0)}]))
                  raw))
        never-read (->> tally
                        (filter (fn [[_ v]] (pos? (:claims v 0))))
                        (remove (fn [[_ v]] (pos? (:reads v 0))))
                        (map first)
                        (set))]
    {:skills tally
     :workspaces (count ws-dirs)
     :prune-candidates never-read
     :per-workspace reports}))

(defn render
  "One human screen from fleet-report."
  [{:keys [skills workspaces prune-candidates]}]
  (let [lines (map (fn [[k v]]
                     (format "%-28s claimed %2d · read %2d"
                             (name k) (:claims v 0) (:reads v 0)))
                   skills)]
    (str "📊 skill economy over " workspaces " workspaces\n"
         (when (seq lines)
           (str (str/join "\n" lines) "\n"))
         "prune candidates (claimed, never read): "
         (if (seq prune-candidates) (str/join ", " prune-candidates) "none"))))
