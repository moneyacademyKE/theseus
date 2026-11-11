#!/usr/bin/env bb
;; Live BrowserOS MCP probe. Prints protocol facts and counts only.
(require '[bb-agent.mcp-client :as mcp]
         '[clojure.string :as str])

(def url (or (System/getenv "BROWSEROS_MCP_URL")
             "http://127.0.0.1:9210/mcp"))
(def client (mcp/connect {:url url :client-name "theseus" :client-version "0.1.0"}))

(defn- die [stage result]
  (println stage "FAILED:" (or (:error result) "unknown error"))
  (System/exit 1))

(when-not (:ok? client) (die "initialize" client))
(when (str/blank? (:session-id client))
  (die "initialize" {:error "BrowserOS did not return mcp-session-id"}))
(println "initialize: ok")
(println "session header: present (value withheld)")
(println "protocol:" (:protocol-version client))
(println "server:" (select-keys (:server-info client) [:name :version]))

(def listed (mcp/list-tools client))
(when-not (:ok? listed) (die "tools/list" listed))
(def tools (get-in listed [:result :tools]))
(def names (set (map :name tools)))
(println "tools/list: ok;" (count tools) "tools")
(println "tool names:" (str/join ", " (sort names)))

(if (contains? names "tabs")
  (let [result (mcp/call-tool client "tabs" {:action "list"})]
    (when-not (:ok? result) (die "tools/call tabs" result))
    (when (true? (get-in result [:result :isError]))
      (die "tools/call tabs" {:error "BrowserOS returned a tool-level error"}))
    (println "tools/call tabs list: ok; tool error: false; result fields:"
             (str/join ", " (map name (keys (:result result)))))
    (println "tab payload withheld"))
  (println "tools/call: skipped (tabs tool not advertised)"))
(System/exit 0)
