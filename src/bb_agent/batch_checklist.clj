(ns bb-agent.batch-checklist
  "Monotonic crash-resumable batch checklist for bulk N-item work.
   Persists Markdown under state/checklists/<session-id>.md. Ticked (:done)
   and skipped (:skipped) items are monotonic: restarting a checklist after a
   crash or compaction merges and preserves every previously completed item."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(defn item-key [text]
  (-> (str (or text ""))
      str/lower-case
      (str/replace #"[^a-z0-9._/-]+" " ")
      str/trim))

(defn- safe-sid [sid]
  (let [s (str/replace (str (or sid "default")) #"[^A-Za-z0-9_.-]" "_")]
    (if (str/blank? s) "default" s)))

(defn checklist-file [session-id]
  (fs/path (config/home) "state" "checklists" (str (safe-sid session-id) ".md")))

(defn parse-checklist [raw]
  (let [lines (str/split-lines (str (or raw "")))
        title (or (some (fn [l]
                          (when (str/starts-with? l "# ")
                            (str/trim (subs l 2))))
                        lines)
                  "Batch Checklist")
        items (->> lines
                   (keep (fn [l]
                           (when-let [[_ mark text] (re-matches #"^\s*-\s+\[([ x~])\]\s+(.+)$" l)]
                             {:text (str/trim text)
                              :key (item-key text)
                              :status (case mark
                                        "x" :done
                                        "~" :skipped
                                        :open)})))
                   vec)]
    {:title title :items items}))

(defn format-checklist [{:keys [title items]}]
  (str "# " (or title "Batch Checklist") "\n\n"
       (str/join "\n"
                 (map (fn [{:keys [status text]}]
                        (str "- [" (case status :done "x" :skipped "~" " ") "] " text))
                      items))
       "\n"))

(defn progress-line [{:keys [items]}]
  (let [done (count (filter #(= :done (:status %)) items))
        skipped (count (filter #(= :skipped (:status %)) items))
        open (count (filter #(= :open (:status %)) items))]
    (str done " done, " skipped " skipped, " open " open (" (count items) " total)")))

(defn load-checklist [session-id]
  (let [p (checklist-file session-id)]
    (when (fs/regular-file? p)
      (try
        (parse-checklist (slurp (str p)))
        (catch Exception _ nil)))))

(defn start-checklist!
  "Initialize or monotonically restart-merge a batch checklist. Any item
   previously marked :done or :skipped stays finalized on restart."
  [session-id title item-texts]
  (let [existing (load-checklist session-id)
        prev-status (into {} (map (juxt :key :status) (:items existing)))
        merged-items (->> item-texts
                          (map #(str/trim (str %)))
                          (remove str/blank?)
                          (reduce (fn [acc t]
                                    (let [k (item-key t)]
                                      (if (some #(= k (:key %)) acc)
                                        acc
                                        (let [st (get prev-status k :open)]
                                          (conj acc {:text t
                                                     :key k
                                                     :status (if (#{:done :skipped} st) st :open)})))))
                                  []))
        record {:title (or title (:title existing) "Batch Checklist")
                :items merged-items}]
    (aio/write-private-file! (checklist-file session-id) (format-checklist record))
    record))

(defn tick!
  "Transition an item matching `text-or-key` to :done or :skipped (monotonic)."
  [session-id text-or-key status]
  (when-let [cur (load-checklist session-id)]
    (let [k (item-key text-or-key)
          next-st (if (#{:done :skipped} status) status :done)
          updated (update cur :items
                          (fn [items]
                            (mapv (fn [it]
                                    (if (and (= k (:key it)) (= :open (:status it)))
                                      (assoc it :status next-st)
                                      it))
                                  items)))]
      (aio/write-private-file! (checklist-file session-id) (format-checklist updated))
      updated)))
