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
      :required []}}}])
