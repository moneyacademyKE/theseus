;; scripts/probe_net_search.bb — live probe of the web_search tool.
;; Requires the repo on the classpath. Exit 0 only when the engine
;; returns results that actually mention the query's significant terms;
;; a page of unrelated posts (the Bing failure mode) is a FAILED probe,
;; not a green one. Never touches the live ledger (direct net-search
;; call, not a tool.clj dispatch).
(require '[bb-agent.net-search :as net-search]
         '[clojure.string :as str])

(def stop-words
  #{"the" "and" "for" "with" "from" "how" "what" "why" "when" "where"
    "who" "which" "docs" "documentation" "about" "into" "that" "this"})

(defn terms [query]
  (->> (str/split (str/lower-case query) #"[^a-z0-9]+")
       (remove stop-words)
       (filter #(>= (count %) 4))
       distinct
       vec))

(defn mentions-term? [result terms]
  (let [haystack (str/lower-case
                  (str (:title result) " " (:snippet result) " "
                       (:url result) " " (:domain result)))]
    (some #(str/includes? haystack %) terms)))

(let [query (or (first *command-line-args*) "Babashka scripting documentation")
      {:keys [status results output error/message]} (net-search/search
                                                     {:query query :limit 5})
      query-terms (terms query)
      relevant (if (seq query-terms)
                 (filter #(mentions-term? % query-terms) results)
                 results)]
  (println "probe query:" query)
  (println "tool status:" status)
  (println "results parsed:" (count results))
  (println "relevant results:" (count relevant))
  (when (seq relevant)
    (println "---")
    (println output))
  (if (and (= :ok status) (>= (count relevant) 2))
    (System/exit 0)
    (do (println "PROBE FAILED:" (or message "fewer than 2 relevant results"))
        (System/exit 1))))
