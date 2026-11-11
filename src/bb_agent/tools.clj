(ns bb-agent.tools
  "Tool definitions advertised to LLM providers (OpenAI function-calling
  format). One source of truth: the provider sends these verbatim, the
  handlers in bb-agent.tool.* execute them, and policy predicates see the
  same names.")

(def definitions
  [{:type "function"
    :function
    {:name "read_file"
     :description "Read a text file from disk and return its contents."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "File path to read"}}
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
     :description "Search a file for a query string; returns matching lines with line numbers."
     :parameters
     {:type "object"
      :properties {"path" {:type "string" :description "File path to search"}
                   "query" {:type "string" :description "Text to search for"}}
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
    {:name "launch_goal"
     :description "Launch a supervised goal runner for a complex build. It loops — acts, measures observers, rolls back on integrity violations — until the goal is met or halts, and posts progress plus the outcome to this chat. Use it when a build needs more than a few tool rounds (multi-file projects, tests that must pass); do not use it for simple one-shot answers."
     :parameters
     {:type "object"
      :properties {"spec" {:type "string" :description "What to build, stated as an inspectable end state: the files that must exist and the checks that must pass"}}
      :required ["spec"]}}}])
