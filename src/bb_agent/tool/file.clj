(ns bb-agent.tool.file
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.conflict-blocks :as conflict-blocks]
            [bb-agent.tool.common :as common]
            [bb-agent.knowledge :as knowledge]
            [bb-agent.knowledge-usage :as ku]
            [bb-agent.path-lock :as path-lock]
            [bb-agent.repl :as repl]
            [bb-agent.session :as session]
            [bb-agent.tool.path :as path]
            [clojure.string :as str]))

(defn- parse-opt-long [v]
  (cond
    (nil? v) nil
    (integer? v) (long v)
    (string? v) (or (parse-long (str/trim v)) ::invalid)
    :else ::invalid))

(declare binary-file?)

;; ---------- Session-scoped fully-read path tracking (OpenCrabs read_state.rs) ----------

(def ^:private fully-read-paths (atom #{}))

(defn- canonical-key-path [p]
  (try
    (str (fs/canonicalize p))
    (catch Exception _
      (str (fs/absolutize p)))))

(defn mark-fully-read!
  "Record that `session-id` has been shown the full contents of `path`."
  [session-id path]
  (when (and session-id path)
    (swap! fully-read-paths conj [(str session-id) (canonical-key-path path)]))
  nil)

(defn was-fully-read?
  "True when `session-id` was shown the full contents of `path` this run."
  [session-id path]
  (boolean
   (and session-id path
        (contains? @fully-read-paths [(str session-id) (canonical-key-path path)]))))

(defn- read-file! [{:keys [path start_line end_line] :as args}]
  (let [sid (:session/id args)
        target (path/checked-read-path "read_file" path)
        s-line (parse-opt-long start_line)
        e-line (parse-opt-long end_line)]
    (cond
      (= :error (:status target)) target

      (or (= ::invalid s-line) (= ::invalid e-line))
      (common/error-result "read_file" "start_line and end_line must be positive integers" {:path path})

      (and s-line (< s-line 1))
      (common/error-result "read_file" "start_line must be >= 1" {:path path :start-line s-line})

      (and e-line (< e-line 1))
      (common/error-result "read_file" "end_line must be >= 1" {:path path :end-line e-line})

      (and s-line e-line (> s-line e-line))
      (common/error-result "read_file" "start_line cannot exceed end_line"
                           {:path path :start-line s-line :end-line e-line})

      (not (fs/exists? target))
      (common/error-result "read_file" (str "File not found: " path) {:path path})

      (fs/directory? target)
      (common/error-result "read_file" (str "Path is a directory: " path) {:path path})

      (path/protected-secret-path? target)
      (common/error-result "read_file"
                           (str "Refused: protected secret path: " path)
                           {:path path :executed? false})

      (path/file-too-large? target common/max-read-bytes)
      (common/error-result "read_file"
                           (str "File too large: " path)
                           {:path path :max/bytes common/max-read-bytes})

      (binary-file? target)
      (common/error-result "read_file"
                           (str "Refused: binary file (use document_read or shell): " path)
                           {:path path :executed? false})

      :else
      (let [full (slurp (str target))
            tstr (str target)
            _ (when (and (str/includes? tstr "/brain/knowledge/")
                         (str/ends-with? tstr ".md"))
                (ku/record! {:page (str (fs/file-name target))
                             :action :read
                             :surface :chat
                             :session-id sid}))
            conflicts (when (str/includes? full "<<<<<<<")
                        (not-empty (conflict-blocks/extract-blocks tstr full)))]
        (if (or s-line e-line)
          (let [lines (str/split-lines full)
                total (count lines)
                from-i (max 1 (or s-line 1))
                to-i (min total (or e-line total))
                truncated? (or (> from-i 1) (< to-i total))
                sliced (if (or (zero? total) (> from-i total) (> from-i to-i))
                         ""
                         (str/join "\n" (subvec (vec lines) (dec from-i) to-i)))]
            (when (and sid (not truncated?))
              (mark-fully-read! sid target))
            (common/ok-result "read_file"
                              (cond-> {:path tstr
                                       :content sliced
                                       :bytes (count (.getBytes sliced "UTF-8"))
                                       :start-line from-i
                                       :end-line (max (dec from-i) to-i)
                                       :total-lines total
                                       :truncated? truncated?}
                                conflicts (assoc :conflict/blocks conflicts))))
          (do
            (when sid (mark-fully-read! sid target))
            (common/ok-result "read_file"
                              (cond-> {:path tstr
                                       :content full
                                       :bytes (count (.getBytes full "UTF-8"))}
                                conflicts (assoc :conflict/blocks conflicts)))))))))

(defn- write-file-unguarded
  [target content bytes append? create-dirs? snapshot]
  (path-lock/with-path-lock
    (str target)
    (fn [_]
      (when-let [parent (fs/parent target)]
        (when (or create-dirs? append?)
          (fs/create-dirs parent)))
      (if append?
        (spit (str target) content :append true)
        (aio/write-atomic-file! target content))))
  (merge (common/ok-result "write_file"
                           {:path (str target)
                            :bytes bytes
                            :append? (boolean append?)})
         (when snapshot {:snapshot snapshot})))

(defn- write-file! [{:keys [path content append? create-dirs?] :as args}]
  (let [sid (:session/id args)
        target (path/checked-write-path "write_file" path)
        content (or content "")
        bytes (count (.getBytes content "UTF-8"))]
    (cond
      (= :error (:status target)) target

      (path/protected-write-path? target)
      (common/error-result "write_file"
                           (str "Refused: protected secret path: " path)
                           {:path path :executed? false})

      (and sid
           (not append?)
           (fs/exists? target)
           (not (was-fully-read? sid target)))
      (common/error-result "write_file"
                           (str "Refused: existing file was not read in full this session (" path
                                "). Call read_file for the full file first, or use edit_file / append?=true.")
                           {:path path :executed? false})

      (> bytes common/max-write-bytes)
      (common/error-result "write_file"
                           (str "Content too large for write_file: " path)
                           {:path path :max/bytes common/max-write-bytes :bytes bytes})

      (and (not append?) (repl/clojure-file? path)
           (not (:ok? (repl/parse-check-clojure content))))
      (common/error-result "write_file"
                           (str "Refused: invalid Clojure syntax in " path ": "
                                (:error (repl/parse-check-clojure content)))
                           {:path path :executed? false})

      :else
      (let [guard (knowledge/guard-write! path)]
        (if (= :refused (:verdict guard))
          (common/error-result "write_file"
                               (str "write refused by protection tier ("
                                    (name (:tier guard)) "): " path)
                               {:path path :tier (:tier guard)})
          (write-file-unguarded target content bytes append? create-dirs?
                                (:snapshot guard)))))))

;; ---------- surgical edit_file ----------

(defn- count-occurrences [haystack needle]
  (if (empty? needle)
    0
    (loop [idx 0 cnt 0]
      (let [found (.indexOf ^String haystack ^String needle (int idx))]
        (if (neg? found)
          cnt
          (recur (+ found (count needle)) (inc cnt)))))))

(defn- fuzzy-line-matches
  "Find 0-based start line indices where trimmed lines match needle-lines."
  [file-lines needle-lines]
  (let [n (count needle-lines)
        f-len (count file-lines)
        norm-needle (mapv str/trim needle-lines)]
    (if (or (zero? n) (> n f-len))
      []
      (filterv (fn [i]
                 (= norm-needle
                    (mapv #(str/trim (nth file-lines (+ i %))) (range n))))
               (range (inc (- f-len n)))))))

(defn- apply-edit-transform
  "Pure transform returning {:ok true :content ... :mode ... :replacements ...}
   or {:error \"...\"}."
  [original {:keys [old_text new_text replace_all start_line end_line]}]
  (let [new-s (str (or new_text ""))
        s-line (parse-opt-long start_line)
        e-line (parse-opt-long end_line)]
    (cond
      (or (= ::invalid s-line) (= ::invalid e-line))
      {:error "start_line and end_line must be positive integers"}

      (and s-line e-line)
      (let [trailing-nl? (str/ends-with? original "\n")
            lines (vec (str/split-lines original))
            total (count lines)]
        (cond
          (or (< s-line 1) (< e-line s-line) (> e-line total))
          {:error (str "Invalid line range " s-line ".." e-line " for file with " total " lines")}

          :else
          (let [before (subvec lines 0 (dec s-line))
                after (subvec lines e-line)
                replacement-lines (if (empty? new-s) [] (str/split-lines new-s))
                joined (str/join "\n" (concat before replacement-lines after))
                final-s (if (and trailing-nl? (not (str/ends-with? joined "\n")))
                          (str joined "\n")
                          joined)]
            {:ok true :content final-s :mode :line-range :replacements 1})))

      (and (string? old_text) (not (empty? old_text)))
      (let [cnt (count-occurrences original old_text)]
        (cond
          (and (> cnt 1) (not replace_all))
          {:error (str "Ambiguous match: old_text matched " cnt
                       " times. Provide more surrounding context or set replace_all=true.")}

          (pos? cnt)
          (let [updated (if replace_all
                          (str/replace original old_text new-s)
                          (str/replace-first original old_text new-s))]
            {:ok true :content updated :mode :exact :replacements (if replace_all cnt 1)})

          :else
          ;; Whitespace-trimmed line-block fallback (unique match only).
          (let [trailing-nl? (str/ends-with? original "\n")
                file-lines (vec (str/split-lines original))
                needle-lines (vec (str/split-lines old_text))
                hits (fuzzy-line-matches file-lines needle-lines)]
            (cond
              (zero? (count hits))
              {:error "old_text not found in file (exact or trimmed-line match)"}

              (> (count hits) 1)
              {:error (str "Ambiguous fuzzy match: old_text matched " (count hits)
                           " line blocks. Provide more surrounding context.")}

              :else
              (let [start-idx (first hits)
                    before (subvec file-lines 0 start-idx)
                    after (subvec file-lines (+ start-idx (count needle-lines)))
                    rep-lines (if (empty? new-s) [] (str/split-lines new-s))
                    joined (str/join "\n" (concat before rep-lines after))
                    final-s (if (and trailing-nl? (not (str/ends-with? joined "\n")))
                              (str joined "\n")
                              joined)]
                {:ok true :content final-s :mode :fuzzy-lines :replacements 1})))))

      :else
      {:error "edit_file requires either (old_text + new_text) or (start_line + end_line + new_text)"})))

(defn- edit-file! [{:keys [path] :as args}]
  (let [target (path/checked-write-path "edit_file" path)]
    (cond
      (= :error (:status target)) target

      (path/protected-write-path? target)
      (common/error-result "edit_file"
                           (str "Refused: protected secret path: " path)
                           {:path path :executed? false})

      (not (fs/exists? target))
      (common/error-result "edit_file" (str "File not found: " path) {:path path})

      (fs/directory? target)
      (common/error-result "edit_file" (str "Path is a directory: " path) {:path path})

      (path/file-too-large? target common/max-read-bytes)
      (common/error-result "edit_file" (str "File too large: " path)
                           {:path path :max/bytes common/max-read-bytes})

      :else
      (let [original (slurp (str target))
            tx (apply-edit-transform original args)]
        (if-let [err (:error tx)]
          (common/error-result "edit_file" err {:path path})
          (let [updated (:content tx)
                bytes (count (.getBytes ^String updated "UTF-8"))]
            (cond
              (> bytes common/max-write-bytes)
              (common/error-result "edit_file"
                                   (str "Result too large for edit_file: " path)
                                   {:path path :max/bytes common/max-write-bytes :bytes bytes})

              (and (repl/clojure-file? path)
                   (not (:ok? (repl/parse-check-clojure updated))))
              (common/error-result "edit_file"
                                   (str "Refused: invalid Clojure syntax after edit in " path ": "
                                        (:error (repl/parse-check-clojure updated)))
                                   {:path path :executed? false})

              :else
              (let [guard (knowledge/guard-write! path)]
                (if (= :refused (:verdict guard))
                  (common/error-result "edit_file"
                                       (str "edit refused by protection tier ("
                                            (name (:tier guard)) "): " path)
                                       {:path path :tier (:tier guard)})
                  (do
                    (path-lock/with-path-lock
                      (str target)
                      (fn [_] (aio/write-atomic-file! target updated)))
                    (merge (common/ok-result "edit_file"
                                             {:path (str target)
                                              :bytes bytes
                                              :mode (:mode tx)
                                              :replacements (:replacements tx)
                                              :changed? (not= original updated)})
                           (when-let [snap (:snapshot guard)]
                             {:snapshot snap}))))))))))))

;; ---------- search (literal + regex + glob) ----------

(defn- grep-line [query line-number line]
  {:line/number line-number
   :line/text line
   :match query})

(defn- binary-file?
  "Check first 512 bytes for a NUL byte so binary blobs are skipped before slurp."
  [file]
  (try
    (with-open [is (java.io.FileInputStream. (fs/file file))]
      (let [buf (byte-array 512)
            n (.read is buf)]
        (boolean
         (and (pos? n)
              (some #(zero? (aget buf %)) (range n))))))
    (catch Exception _ true)))

(defn- git-internal-path? [file]
  (some #(= ".git" (str %)) (fs/components file)))

(defn- searchable-file? [file]
  (and (fs/regular-file? file)
       (not (git-internal-path? file))
       (not (path/protected-secret-path? file))
       (not (path/file-too-large? file common/max-read-bytes))
       (not (binary-file? file))))

(defn- search-file [file query matcher]
  (let [checked (path/checked-read-path "search" (str file))]
    (if (= :error (:status checked))
      []
      (->> (str/split-lines (slurp (str checked)))
           (map-indexed (fn [idx line]
                          (when (matcher line)
                            (grep-line query (inc idx) line))))
           (remove nil?)))))

(defn- glob-files [base glob-pat]
  (let [pat (when (and (string? glob-pat) (not (str/blank? glob-pat)))
              (str/trim glob-pat))]
    (if pat
      (let [patterns (if (str/includes? pat "/")
                       [pat]
                       [pat (str "**/" pat)])]
        (->> patterns
             (mapcat #(fs/glob base %))
             distinct
             (filter searchable-file?)))
      (->> (fs/glob base "**")
           (filter searchable-file?)))))

(defn- search! [{:keys [path query regex glob]}]
  (let [base (path/checked-read-path "search" path)
        query (or query "")
        compiled (when (and regex (not (str/blank? query)))
                   (try {:re (re-pattern query)}
                        (catch Exception e {:err (str "Invalid regex: " (.getMessage e))})))]
    (cond
      (str/blank? query)
      (common/error-result "search" "search requires non-empty :query" {})

      (:err compiled)
      (common/error-result "search" (:err compiled) {:query query})

      (= :error (:status base)) base

      (not (fs/exists? base))
      (common/error-result "search" (str "Search path not found: " path) {:path path})

      (and (not (fs/directory? base)) (path/protected-secret-path? base))
      (common/error-result "search"
                           (str "Refused: protected secret path: " path)
                           {:path path :executed? false})

      :else
      (let [matcher (if-let [re (:re compiled)]
                      #(boolean (re-find re %))
                      #(str/includes? % query))
            files (if (fs/directory? base)
                    (glob-files base glob)
                    (if (searchable-file? base) [base] []))
            matches (->> files
                         (mapcat (fn [file]
                                   (map #(assoc % :path (str file))
                                        (search-file file query matcher))))
                         (take (common/bounded-count nil common/max-search-results))
                         vec)]
        (common/ok-result "search" (cond-> {:path (str base)
                                            :query query
                                            :matches matches}
                                     regex (assoc :regex true)
                                     glob (assoc :glob glob)))))))

(def ^:private max-session-turns-slice 10)
(def ^:private max-session-turns-chars 20000)

(defn- read-session-turns! [{:keys [from to] :session/keys [id]}]
  (let [from-n (cond (integer? from) (long from)
                     (string? from) (parse-long (str/trim from))
                     :else nil)
        to-n (cond (integer? to) (long to)
                   (string? to) (parse-long (str/trim to))
                   :else nil)]
    (cond
      (str/blank? (str (or id "")))
      (common/error-result "read_session_turns"
                           "read_session_turns requires an active session"
                           {:executed? false})

      (or (nil? from-n) (nil? to-n) (neg? from-n) (< to-n from-n))
      (common/error-result "read_session_turns"
                           "from and to must be non-negative integers with from <= to"
                           {:executed? false})

      :else
      (let [capped-to (min to-n (+ from-n (dec max-session-turns-slice)))
            turns (session/load-turn-range id from-n capped-to)
            rendered (str/join
                      "\n\n"
                      (map (fn [{:keys [turn/index user/input assistant/final]}]
                             (str "[Turn " index "]\n"
                                  "User: " (or input "") "\n"
                                  "Assistant: " (or final "")))
                           turns))
            output (if (> (count rendered) max-session-turns-chars)
                     (str (subs rendered 0 (- max-session-turns-chars 14))
                          "\n[truncated]")
                     rendered)]
        (common/ok-result "read_session_turns"
                          {:from from-n
                           :to capped-to
                           :count (count turns)
                           :output output})))))

(def handlers
  {"read_file" read-file!
   "write_file" write-file!
   "edit_file" edit-file!
   "search" search!
   "read_session_turns" read-session-turns!})
