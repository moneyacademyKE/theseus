;; scripts/probe_net_search.bb — live probe of the web_search tool.
;; Requires the repo on the classpath; prints top results for a real
;; query. Exit 0 on a parsed result set, 1 on failure. Never touches
;; the live ledger (the handler is called with the usage funnel off —
;; this is a direct net-search/search call, not a tool.clj dispatch).
(require '[bb-agent.net-search :as net-search])

(let [query (or (first *command-line-args*) "Babashka scripting documentation")
      {:keys [status results output error/message]} (net-search/search {:query query :limit 5})]
  (println "probe query:" query)
  (println "tool status:" status)
  (println "results parsed:" (count results))
  (when (seq results)
    (println "---")
    (println output))
  (if (and (= :ok status) (seq results))
    (System/exit 0)
    (do (println "PROBE FAILED:" (or message "no results parsed"))
        (System/exit 1))))
