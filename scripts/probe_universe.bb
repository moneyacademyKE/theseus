#!/usr/bin/env bb
;; Probe the sector-screened brief candidates against Binance and TradingView.
;; A matching ticker and sector do not establish the Binance token's identity.
;; Usage: bb scripts/probe_universe.bb [limit]
(require '[clojure.string :as str]
         '[bb-agent.brief :as brief]
         '[bb-agent.brief-universe :as universe]
         '[bb-agent.config :as config])

(def limit (or (some-> (first *command-line-args*) parse-long) 24))

(let [u (universe/fetch-universe)
      _ (when (nil? u)
          (println "Binance universe unavailable")
          (System/exit 2))
      covered (-> (slurp (str (config/home) "/brain/ai-stock-briefs/state.md"))
                  brief/parse-state
                  :covered
                  (->> (map first) set))
      v (brief/validated-universe u covered)
      names (mapv :underlying (:tokens v))]
  (println "bStocks candidates" (count (:tokens u)))
  (println "sector-screened" (count names))
  (println "sectors" (str/join ", " (distinct (keep :sector (:tokens v)))))
  (println "names" (str/join " " (take limit names)))
  (when (or (empty? names)
            (some #{"BN" "DG"} names)
            (not-every? universe/tech-sector? (:tokens v)))
    (println "FAIL: empty or non-tech result")
    (System/exit 1))
  (System/exit 0))
