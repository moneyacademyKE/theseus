#!/usr/bin/env bb
;; Report the existing state/usage.edn tool funnel. No arguments or payloads.
(require '[bb-agent.usage :as usage])

(println (usage/render-tool-summary (usage/load-events)))
