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

;; ---------------------------------------------------------------------------
;; Context cost (added 2026-11-11)
;;
;; `load-brain-context` promises "per-turn cost stays flat as knowledge grows",
;; and it is flat for knowledge/ — one `name — scope` line per page. It is NOT
;; flat for brain/*.md, which is concatenated WHOLE every turn and holds the two
;; append-only ledgers. Measured live before this existed: 754430 chars injected
;; per turn (~188k tokens at chars/4), of which improvements.md alone is 635358
;; and wins.md 119318 — so two files that only ever grow are 99.7% of the
;; always-on context, and nothing in the repo counted them.
;;
;; Sources, both level 2 in code/docs they ship:
;; - ruslanudintsev-ai/agent-self-learning-core, core/commands/base.py:196-292
;;   (`_apply_output_budgets`): every result carries the real total beside what
;;   was shown, so a cut can never read as a whole.
;; - vibeeval/vibecosystem, README: "Catalog size does not describe how much
;;   prompt content is loaded into each request."
;;
;; Report-only by construction: nothing here trims, rewrites or refuses. A
;; measurement no boundary calls measures nothing, so this is a `bb` task and a
;; printout a human reads.
;; ---------------------------------------------------------------------------

(defn context-cost
  "What the always-on brain injection costs, per file, biggest first.
   {:status :ok|:missing :files [{:name :chars :share}] :identity-chars
    :index-lines :index-chars :injected-chars :approx-tokens :basis}

   A missing directory returns {:status :missing :files []} and NO counts —
   not-found is not a measured zero (the rule `ledger-mentions` learned
   2026-10-14), so an absent corpus can never read as a cheap one.

   :injected-chars is the count of the string `load-brain-context` actually
   returns, not a sum of guesses. :approx-tokens divides by 4 and says so in
   :basis — an estimate names where its number came from (munitkala/selfsemester,
   `basis = one sentence on where the numbers come from`)."
  ([]
   (context-cost (str (brain-dir))))
  ([dir]
   (if-not (fs/exists? dir)
     {:status :missing :files []}
     (let [files (sort-by fs/file-name (fs/glob dir "*.md"))
           rows (->> files
                     (map (fn [f] {:name (fs/file-name f)
                                   :chars (count (slurp (str f)))}))
                     (sort-by :chars >)
                     vec)
           total (reduce + 0 (map :chars rows))
           rows (mapv (fn [r]
                        (assoc r :share (if (pos? total)
                                          (* 100.0 (/ (:chars r) total))
                                          0.0)))
                      rows)
           idx (knowledge-index dir)
           idx-chars (count (str/join "\n" idx))
           injected (count (load-brain-context dir))]
       {:status :ok
        :files rows
        :identity-chars total
        :index-lines (count idx)
        :index-chars idx-chars
        :injected-chars injected
        :approx-tokens (quot injected 4)
        :basis "approx-tokens = injected-chars / 4 (chars-per-token rule of thumb, not a provider tokenizer)"
        :negative-scope (str "counts the brain injection this process would build, not what the provider "
                             "accepted: history, memories, semantic context, skills and tool payloads ride "
                             "alongside it and are not measured here. chars are Clojure chars, not bytes "
                             "or tokens; no file was trimmed or rewritten.")}))))

(defn context-cost-report
  "The printout for `context-cost`: one line per file with its share, the
   dominant file named, the index counted separately, and the estimate's basis
   beside the estimate. Exits are the caller's business."
  ([dir]
   (let [c (context-cost dir)]
     (if (= :missing (:status c))
       (str "context cost: MISSING — no brain dir at " dir
            "\nnot-checked: nothing was measured, so this is not a zero-cost corpus.")
       (let [dom (first (:files c))]
         (str/join
          "\n"
          (concat
           [(str "context cost: " (:injected-chars c) " chars always injected per turn"
                 " (~" (:approx-tokens c) " tokens) — " (:basis c))]
           (map (fn [r]
                  (format "  %7d chars  %5.1f%%  %s" (:chars r) (:share r) (:name r)))
                (:files c))
           [(str "dominant: " (:name dom) " is " (format "%.1f" (:share dom))
                 "% of the always-on brain injection (" (:identity-chars c)
                 " chars of brain/*.md, loaded whole — append-only ledgers only ever grow)")
            (str "knowledge index: " (:index-lines c) " line(s), " (:index-chars c)
                 " chars — one `name — scope` line per page, which IS flat as the corpus grows")
            (str "negative scope: " (:negative-scope c))]))))))
  ([]
   (context-cost-report (str (brain-dir)))))

(defn context-cost-print!
  ([dir] (print (context-cost-report dir)) (flush))
  ([] (context-cost-print! (str (brain-dir)))))

(defn -main
  "CLI for `bb context-cost [brain-dir]`. A missing path exits 2 and names it:
   not-found is not an empty corpus, the shape `bb coords externals` already uses."
  [args]
  (let [dir (or (first args) (str (brain-dir)))]
    (context-cost-print! dir)
    (println)
    (if (fs/exists? dir) 0 2)))
