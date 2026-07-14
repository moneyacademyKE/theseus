(ns bb-agent.ui
  (:require [bb-agent.memory :as memory]
            [bb-agent.schedule :as schedule]))

(defn status []
  {:memory/backend (memory/backend)
   :memory/count (count (memory/load-memories))
   :schedule/count (count (schedule/load-schedules))})

(defn render-status []
  (let [{memory-backend :memory/backend
         memory-count :memory/count
         schedule-count :schedule/count} (status)]
    (str "Babashka agent status\n"
         "memory-backend=" memory-backend "\n"
         "memory-count=" memory-count "\n"
         "schedule-count=" schedule-count)))
