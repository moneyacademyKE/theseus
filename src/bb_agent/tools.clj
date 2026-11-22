(ns bb-agent.tools
  "Tool definitions advertised to LLM providers (OpenAI function-calling
  format). One source of truth: the provider sends these verbatim, the
  handlers in bb-agent.tool.* execute them, and policy predicates see the
  same names."
  (:require [clojure.string]))

(def definitions
  [{:type "function"
    :function
    {:name "read_file"
     :description "Read a text file from disk (full file or optional 1-based inclusive line range)."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "File path to read"}
                   "start_line" {:type "integer" :description "Optional 1-based starting line (inclusive)"}
                   "end_line" {:type "integer" :description "Optional 1-based ending line (inclusive)"}}
      :required ["path"]}}}
   {:type "function"
    :function
    {:name "write_file"
     :description "Write content to a file on disk (subject to approval policy)."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "File path to write"}
                   "content" {:type "string" :description "Full file content"}
                   "append?" {:type "boolean" :description "Append instead of overwrite"}
                   "create-dirs?" {:type "boolean" :description "Create parent directories"}}
      :required ["path" "content"]}}}
   {:type "function"
    :function
    {:name "edit_file"
     :description "Surgically edit an existing file via exact/fuzzy search-and-replace (old_text/new_text) or 1-based inclusive line range (start_line/end_line/new_text). Prefer this over write_file when modifying existing files."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "Existing file path to edit"}
                   "old_text" {:type "string" :description "Exact text to replace (for search-replace mode)"}
                   "new_text" {:type "string" :description "Replacement text"}
                   "replace_all" {:type "boolean" :description "Replace all exact occurrences (default false)"}
                   "start_line" {:type "integer" :description "1-based start line (inclusive, for line-range mode)"}
                   "end_line" {:type "integer" :description "1-based end line (inclusive, for line-range mode)"}}
      :required ["path" "new_text"]}}}
   {:type "function"
    :function
    {:name "shell"
     :description "Run a shell command and return stdout/stderr (subject to approval policy)."
     :parameters
     {:type "object"
      :properties {"cmd" {:type "string" :description "The shell command to run"}
                   "cwd" {:type "string" :description "Working directory (optional)"}
                   "timeout-ms" {:type "integer" :description "Timeout in milliseconds (optional)"}}
      :required ["cmd"]}}}
   {:type "function"
    :function
    {:name "search"
     :description "Search a file or directory for a query string or regex; returns matching lines with line numbers."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "File or directory path to search"}
                   "query" {:type "string" :description "Text or regex pattern to search for"}
                   "regex" {:type "boolean" :description "Treat query as a regular expression (default false)"}
                   "glob" {:type "string" :description "Optional filename glob filter (e.g. \"*.clj\")"}}
      :required ["path" "query"]}}}
   {:type "function"
    :function
    {:name "git_status"
     :description "Return git status (porcelain) for a directory."
     :parameters
     {:type "object"
      :properties {"cwd" {:type "string" :description "Repository directory (optional)"}}
      :required []}}}
   {:type "function"
    :function
    {:name "telegram_send_file"
     :description "Send a local file to the current Telegram chat as a document or photo (Telegram sessions only)."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "Local file path to send"}
                   "caption" {:type "string" :description "Optional caption (max 1024 characters)"}
                   "photo?" {:type "boolean" :description "Send as photo instead of document (optional)"}}
      :required ["path"]}}}
   {:type "function"
    :function
    {:name "web_send_file"
     :description "Send a local file to the current web deck conversation as a downloadable artifact (web cockpit sessions only). Use it whenever the user is on the web deck and asks for a file, a report, or any generated output."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "Local file path to send"}
                   "caption" {:type "string" :description "Optional caption (max 200 characters)"}}
      :required ["path"]}}}
   {:type "function"
    :function
    {:name "web_search"
     :description (str "Search the web and return results (title, URL, snippet). "
                       "Keyless; read-only. Use it for anything you need to know "
                       "about the outside world: documentation, prices, news, APIs. "
                       "The query itself is visible to the search engine.")
     :parameters
     {:type "object"
      :properties {"query" {:type "string" :description "What to search for"}
                   "limit" {:type "integer" :description "Max results, 1-8 (default 5)"}}
      :required ["query"]}}}
   {:type "function"
    :function
    {:name "browser_read"
     :description "Read a public HTTPS web page through the local BrowserOS reader. Opens and closes one owned tab; cannot click, type, run scripts, or access credentials."
     :parameters
     {:type "object"
      :properties {"url" {:type "string" :description "Public HTTPS URL to read"}}
      :required ["url"]}}}
   {:type "function"
    :function
    {:name "a2a_task"
     :description (str "Send a task to a remote A2A agent and wait for its reply "
                       "(JSON-RPC message/send + tasks/get). Use it to borrow "
                       "capabilities this agent lacks: polished document "
                       "generation (xlsx with formulas, styled pdf/docx), image "
                       "analysis, or any skill the remote agent advertises. "
                       "The local OpenCrabs bridge is at http://127.0.0.1:18790. "
                       "Network side effect; subject to approval. The stored "
                       "bridge credential is attached only to localhost targets.")
     :parameters
     {:type "object"
      :properties {"url" {:type "string" :description "Base URL of the remote A2A agent (e.g. http://127.0.0.1:18790)"}
                   "text" {:type "string" :description "The task to send"}
                   "timeout-ms" {:type "integer" :description "Optional wait cap in milliseconds (default 180000, max 600000)"}}
      :required ["url" "text"]}}}
   {:type "function"
    :function
    {:name "read_session_turns"
     :description "Read a verbatim inclusive range of earlier turns [from..to] from the current session on disk (use the [Turns A-B] provenance tags in compacted summaries to drill down). Max 10 turns per call."
     :parameters
     {:type "object"
      :properties {"from" {:type "integer" :description "0-based starting turn index (inclusive)"}
                   "to" {:type "integer" :description "0-based ending turn index (inclusive)"}}
      :required ["from" "to"]}}}
   {:type "function"
    :function
    {:name "launch_goal"
     :description "Launch a supervised goal runner for a complex build. It loops — acts, measures observers, rolls back on integrity violations — until the goal is met or halts, and posts progress plus the outcome to this chat. Use it when a build needs more than a few tool rounds (multi-file projects, tests that must pass); do not use it for simple one-shot answers."
      :parameters
      {:type "object"
      :properties {"spec" {:type "string" :description "What to build, stated as an inspectable end state: the files that must exist and the checks that must pass"}}
      :required ["spec"]}}}])

(def optional-tools
  "Capabilities that ship dormant: low-priority extras the owner opts into per
   deployment (owner directive 2026-11-12 — \"these new capabilities are low
   priority. they should be optional\"). They are absent from every provider
   prompt and refused at dispatch until config.edn opts in:

     :tools/enable-optional [\"web_search\" \"browser_read\" \"a2a_task\"]"
  #{"web_search" "browser_read" "a2a_task"})

(defn- enabled-set
  "Names cfg opted into. Strings and keywords both work (:web_search ==
   \"web_search\"); nils and unknown names fall away — enabling is additive,
   never a source of new tools."
  [cfg]
  (->> (get cfg :tools/enable-optional)
       (filter identity)
       (map (fn [x] (if (keyword? x) (name x) (str x))))
       set))

(def discover-tools-definition
  {:type "function"
   :function
   {:name "discover_tools"
    :description "Search deferred optional tools by keyword and unlock their full schemas for subsequent rounds in this turn."
    :parameters
    {:type "object"
     :properties {"query" {:type "string" :description "Keyword(s) describing the capability you need"}}
     :required ["query"]}}})

(defn- discovered-set [cfg]
  (->> (get cfg :tools/discovered)
       (filter identity)
       (map (fn [x] (if (keyword? x) (name x) (str x))))
       set))

(defn deferred-definitions
  "Optional tools that cfg opted into, deferred behind discover_tools."
  [cfg]
  (let [enabled (enabled-set cfg)]
    (filterv (fn [d]
               (let [n (get-in d [:function :name])]
                 (and (optional-tools n) (contains? enabled n))))
             definitions)))

(defn search-deferred-tools
  "Match deferred tool definitions by keyword tokens in name/description."
  [cfg query]
  (let [terms (->> (clojure.string/split (clojure.string/lower-case (str (or query "")))
                                         #"[^a-z0-9_]+")
                   (remove clojure.string/blank?)
                   vec)
        candidates (deferred-definitions cfg)]
    (if (empty? terms)
      candidates
      (filterv (fn [d]
                 (let [hay (clojure.string/lower-case
                            (str (get-in d [:function :name]) " "
                                 (get-in d [:function :description])))]
                   (some #(clojure.string/includes? hay %) terms)))
               candidates))))

(defn active-definitions
  "The tool list a turn advertises: `definitions` with dormant optional tools
   removed. When :tools/defer-optional is true, opted-in optional tools stay
   hidden behind discover_tools until unlocked in :tools/discovered."
  [cfg]
  (let [enabled (enabled-set cfg)
        defer? (boolean (:tools/defer-optional cfg))
        discovered (discovered-set cfg)
        base (into []
                   (remove (fn [d]
                             (let [n (get-in d [:function :name])]
                               (and (optional-tools n)
                                    (or (not (contains? enabled n))
                                        (and defer? (not (contains? discovered n))))))))
                   definitions)]
    (if (and defer? (seq (deferred-definitions cfg)))
      (conj base discover-tools-definition)
      base)))

(defn dormant?
  "True when `name` is an optional tool cfg has NOT opted into (or is still
   deferred and not yet discovered). Refused at dispatch before execution."
  [cfg name]
  (boolean
   (and (optional-tools name)
        (or (not (contains? (enabled-set cfg) name))
            (and (:tools/defer-optional cfg)
                 (not (contains? (discovered-set cfg) name)))))))
