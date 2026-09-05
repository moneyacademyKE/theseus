(ns axiom.promote
  "Automates the promotion of staged plans to the permanent specs archive."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn find-highest-adr-num
  "Scan decision-log content for the highest ADR number."
  [content]
  (let [matches (re-seq #"## ADR-(\d+)" content)]
    (if (seq matches)
      (apply max (map #(Integer/parseInt (second %)) matches))
      0)))

(defn prepend-adr
  "Prepend adr-content immediately after the first '---' separator."
  [decision-log-content adr-content]
  (let [divider "---\n"
        idx (.indexOf decision-log-content divider)]
    (if (neg? idx)
      (str decision-log-content "\n\n" adr-content)
      (let [split-pt (+ idx (count divider))]
        (str (subs decision-log-content 0 split-pt)
             adr-content
             "\n\n"
             (subs decision-log-content split-pt))))))

(defn merge-deltas!
  "Copy all markdown delta files from deltas-dir to target-specs-dir."
  [deltas-dir target-specs-dir]
  (when (fs/directory? deltas-dir)
    (fs/create-dirs target-specs-dir)
    (doseq [f (fs/list-dir deltas-dir)]
      (when (and (fs/regular-file? f) (= "md" (fs/extension f)))
        (let [dest (fs/file target-specs-dir (fs/file-name f))]
          (fs/copy f dest {:replace-existing true}))))))

(defn archive-plan!
  "Archive the plan-dir by moving it to the archive-dest-dir."
  [plan-dir archive-dest-dir]
  (when (fs/exists? plan-dir)
    (fs/create-dirs (fs/parent archive-dest-dir))
    (fs/move plan-dir archive-dest-dir {:replace-existing true})))

(defn promote-plan!
  "Promotes a plan folder by merging ADR, merging deltas, and archiving the folder.
   Returns a map with :status, and :adr-tag or :message."
  [opts]
  (let [{:keys [plan-dir decision-log-path specs-dir archive-root-dir]} opts
        plan-path (fs/file plan-dir "plan.md")
        adr-path (fs/file plan-dir "adr.md")
        deltas-dir (fs/file plan-dir "deltas")]
    (cond
      (not (fs/directory? plan-dir))
      {:status :error :message (str "Plan directory not found: " plan-dir)}

      (not (fs/exists? plan-path))
      {:status :error :message (str "plan.md not found in " plan-dir)}

      :else
      (let [log-content (if (fs/exists? decision-log-path)
                          (slurp (str decision-log-path))
                          "# Decision Log\n\n---\n")
            next-adr-num (inc (find-highest-adr-num log-content))
            formatted-adr (format "%04d" next-adr-num)
            adr-tag (str "ADR-" formatted-adr)]
        (when (fs/exists? adr-path)
          (let [adr-template (slurp (str adr-path))
                adr-final (-> adr-template
                              (str/replace #"ADR-00NN" adr-tag)
                              (str/replace #"ADR-XXXX" adr-tag))
                new-log (prepend-adr log-content adr-final)]
            (spit (str decision-log-path) new-log)))
        (merge-deltas! deltas-dir specs-dir)
        (let [dest-dir (fs/file archive-root-dir (fs/file-name plan-dir))]
          (archive-plan! plan-dir dest-dir))
        {:status :ok :adr-num next-adr-num :adr-tag adr-tag}))))

(defn -main
  "CLI Entry point. Promotes the given phase slug."
  [& args]
  (let [args (if (and (= 1 (count args)) (coll? (first args)))
               (first args)
               args)
        phase-slug (first args)]
    (if-not phase-slug
      (do (println "Usage: bb promote <phase-slug>")
          (System/exit 1))
      (let [opts {:plan-dir (fs/file "specs" "_plans" phase-slug)
                  :decision-log-path (fs/file "specs" "decision-log.md")
                  :specs-dir (fs/file "specs" "specs")
                  :archive-root-dir (fs/file "specs" "_plans" "_archive")}
            res (promote-plan! opts)]
        (if (= :ok (:status res))
          (do (println (str "Successfully promoted " phase-slug " as " (:adr-tag res)))
              (System/exit 0))
          (do (binding [*out* *err*]
                (println "Error:" (:message res)))
              (System/exit 1)))))))
