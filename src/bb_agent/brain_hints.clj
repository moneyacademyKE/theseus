(ns bb-agent.brain-hints
  "Harness-driven brain-file hints on tool miss (ported from OpenCrabs brain/hints.rs).
   When a tool call fails, searches <home>/brain/knowledge/*.md for relevant
   guidance and injects up to 2 compact snippets (<=900 chars total) directly
   into the error tool result — zero extra round-trip."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.knowledge-usage :as ku]
            [clojure.string :as str]))

(def ^:private max-snippets 2)
(def ^:private snippet-chars 500)
(def ^:private max-hint-chars 900)
(def ^:private min-score 2)

(def ^:private stopwords
  #{"the" "and" "for" "with" "that" "this" "from" "into" "your" "have"
    "not" "are" "was" "were" "will" "can" "but" "all" "any" "error"
    "failed" "tool" "file" "call" "run" "exit" "code"})

(defn- tokenize [s]
  (->> (str/split (str/lower-case (str (or s ""))) #"[^a-z0-9_./-]+")
       (filter #(and (>= (count %) 3) (not (contains? stopwords %))))
       distinct
       vec))

(defn- best-snippet
  "Extract the highest-scoring paragraph/window from `text` for `q-tokens`."
  [text q-tokens]
  (let [body (str/replace text #"(?s)^---\r?\n.*?\r?\n---\r?\n?" "")
        paras (->> (str/split body #"\r?\n\r?\n+")
                   (map str/trim)
                   (remove str/blank?))
        scored (keep (fn [p]
                       (let [lp (str/lower-case p)
                             hits (count (filter #(str/includes? lp %) q-tokens))]
                         (when (pos? hits)
                           {:hits hits :text p})))
                     paras)]
    (when-let [best (first (sort-by (comp - :hits) scored))]
      (let [t (:text best)]
        (if (> (count t) snippet-chars)
          (str (subs t 0 (dec snippet-chars)) "…")
          t)))))

(defn- knowledge-files []
  (let [dir (fs/path (config/home) "brain" "knowledge")]
    (when (fs/directory? dir)
      (->> (fs/glob dir "*.md")
           (remove #(str/includes? (str (fs/file-name %)) ".bak"))
           (sort-by fs/file-name)))))

(defn hints-for
  "Search <home>/brain/knowledge/*.md for guidance relevant to `query` and return
   a compact formatted hint block (<=900 chars), or nil when nothing clears threshold."
  [query]
  (let [q-tokens (tokenize query)]
    (when (seq q-tokens)
      (let [scored (keep (fn [path]
                           (try
                             (let [text (slurp (str path))
                                   lt (str/lower-case text)
                                   score (count (filter #(str/includes? lt %) q-tokens))]
                               (when (>= score min-score)
                                 (when-let [snip (best-snippet text q-tokens)]
                                   {:score score
                                    :name (str (fs/file-name path))
                                    :snippet snip})))
                             (catch Exception _ nil)))
                         (knowledge-files))
            top (->> scored
                     (sort-by (juxt (comp - :score) :name))
                     (take max-snippets))]
        (when (seq top)
          (doseq [{:keys [name]} top]
            (ku/record! {:page name :action :search-hit :surface :brain-hints}))
          (let [block (str "\n\n─── relevant brain notes ───\n"
                           (str/join "\n"
                                     (map (fn [{:keys [name snippet]}]
                                            (str "• **" name "**: " snippet))
                                          top)))]
            (if (> (count block) max-hint-chars)
              (str (subs block 0 (dec max-hint-chars)) "…")
              block)))))))
