(ns bb-agent.brain
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ledger-files
  "Append-only history ledgers in <home>/brain/ that grow without bound and must
  never be concatenated whole into every turn's system prompt."
  #{"improvements.md" "wins.md"})

(defn brain-dir []
  (fs/path (config/home) "brain"))

(defn load-brain
  "Concatenate identity .md files in <home>/brain, sorted by filename,
  each under a `## filename` header. Excludes append-only ledgers
  (`improvements.md`, `wins.md`), which enter `load-brain-context` as an
  on-demand index. Empty string when missing or empty."
  ([]
   (load-brain (str (brain-dir))))
  ([dir]
   (if-not (fs/exists? dir)
     ""
     (let [files (->> (fs/glob dir "*.md")
                      (remove #(contains? ledger-files (fs/file-name %)))
                      (sort-by fs/file-name))]
       (if (seq files)
         (str/join "\n\n"
                   (map (fn [f]
                          (str "## " (fs/file-name f) "\n" (slurp (str f))))
                        files))
         "")))))

(defn ledger-index
  "One summary line per present append-only ledger (`improvements.md`,
  `wins.md`): `name — append-only ledger (N chars, M lines)`. Keeps full
  ledger history out of per-turn injection while keeping it discoverable."
  ([]
   (ledger-index (str (brain-dir))))
  ([dir]
   (if-not (fs/exists? dir)
     []
     (->> (fs/glob dir "*.md")
          (filter #(contains? ledger-files (fs/file-name %)))
          (sort-by fs/file-name)
          (mapv (fn [f]
                  (let [text (slurp (str f))
                        lines (if (empty? text) 0 (count (str/split-lines text)))]
                    (format "%s — append-only ledger (%d chars, %d lines)"
                            (fs/file-name f) (count text) lines))))))))

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
  "Identity (brain/*.md excluding append-only ledgers) always in-context;
  append-only ledgers (improvements.md, wins.md) and knowledge pages
  (brain/knowledge/*.md) enter as compact indexes with on-demand read
  instructions so per-turn cost stays bounded as history grows."
  ([]
   (load-brain-context (str (brain-dir))))
  ([dir]
   (let [body (load-brain dir)
         ledgers (ledger-index dir)
         idx (knowledge-index dir)
         sections (cond-> []
                    (seq body) (conj body)
                    (seq ledgers)
                    (conj (str "## Ledgers (index only — read/tail brain/<name> when history is needed)\n"
                               (str/join "\n" ledgers)))
                    (seq idx)
                    (conj (str "## Knowledge files (index only — cat brain/knowledge/<name> when its scope matches the task)\n"
                               (str/join "\n" idx))))]
     (str/join "\n\n" sections))))

(defn- sized-rows
  [paths]
  (let [raw (->> paths
                 (map (fn [f] {:name (fs/file-name f)
                               :chars (count (slurp (str f)))}))
                 (sort-by :chars >)
                 vec)
        total (reduce + 0 (map :chars raw))
        rows (mapv (fn [r]
                     (assoc r :share (if (pos? total)
                                       (* 100.0 (/ (:chars r) total))
                                       0.0)))
                   raw)]
    [rows total]))

(defn context-cost
  "What the always-on brain injection costs, separating always-injected
   identity files from indexed append-only ledgers (`improvements.md`,
   `wins.md`) and knowledge pages.

   A missing directory returns {:status :missing :files []} and NO counts."
  ([]
   (context-cost (str (brain-dir))))
  ([dir]
   (if-not (fs/exists? dir)
     {:status :missing :files []}
     (let [all-md (sort-by fs/file-name (fs/glob dir "*.md"))
           id-files (remove #(contains? ledger-files (fs/file-name %)) all-md)
           ledger-paths (filter #(contains? ledger-files (fs/file-name %)) all-md)
           [rows total] (sized-rows id-files)
           [ledger-rows ledger-total] (sized-rows ledger-paths)
           ledgers (ledger-index dir)
           ledgers-chars (count (str/join "\n" ledgers))
           idx (knowledge-index dir)
           idx-chars (count (str/join "\n" idx))
           injected (count (load-brain-context dir))]
       {:status :ok
        :files rows
        :identity-chars total
        :ledgers ledger-rows
        :ledger-lines (count ledgers)
        :ledger-chars ledger-total
        :ledger-index-chars ledgers-chars
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
  "The printout for `context-cost`: identity files by share, indexed
   append-only ledgers kept on disk, knowledge index, and the estimate's
   basis beside the estimate. Exits are the caller's business."
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
           [(if dom
              (str "dominant: " (:name dom) " is " (format "%.1f" (:share dom))
                   "% of the always-on brain identity (" (:identity-chars c)
                   " chars of identity brain/*.md)")
              "dominant: none (0 chars of identity brain/*.md)")]
           (when (seq (:ledgers c))
             (concat
              [(str "ledgers index (on disk, not injected): " (:ledger-lines c) " file(s), "
                    (:ledger-chars c) " chars on disk (" (:ledger-index-chars c)
                    " chars in index summary)")]
              (map (fn [r]
                     (format "  %7d chars  (indexed) %s" (:chars r) (:name r)))
                   (:ledgers c))))
           [(str "knowledge index: " (:index-lines c) " line(s), " (:index-chars c)
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
