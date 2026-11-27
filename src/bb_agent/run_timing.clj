(ns bb-agent.run-timing
  "True execution time vs wall clock via interval union (ported from LoopForge
   domain/run_timing.go). Retries count, parallel branches never double-count,
   and overnight pauses / approval waits land in :waiting-ms.")

(def ^:private node-closers
  #{:node/succeeded :node/failed :node/paused :node/cancelled :node/interrupted :gate/waiting})

(def ^:private run-terminals
  #{:run/succeeded :run/failed :run/cancelled})

(defn- merge-intervals [intervals]
  (if (empty? intervals)
    []
    (let [sorted (sort-by first intervals)]
      (reduce (fn [acc [s e]]
                (let [[ps pe] (peek acc)]
                  (if (<= s pe)
                    (conj (pop acc) [ps (max pe e)])
                    (conj acc [s e]))))
              [(first sorted)]
              (rest sorted)))))

(defn compute-timing
  "Compute {:execution-ms :wall-ms :waiting-ms :executing?} from ordered run events."
  [run events now-ms]
  (let [sorted (sort-by :at events)
        {:keys [intervals open terminal-at]}
        (reduce (fn [{:keys [intervals open terminal-at]} {:keys [type node at]}]
                  (cond
                    (= type :node/started)
                    {:intervals intervals :open (assoc open node at) :terminal-at terminal-at}

                    (contains? node-closers type)
                    (if-let [st (get open node)]
                      {:intervals (conj intervals [st (max st at)])
                       :open (dissoc open node)
                       :terminal-at terminal-at}
                      {:intervals intervals :open open :terminal-at terminal-at})

                    (contains? run-terminals type)
                    {:intervals (into intervals (map (fn [[_ st]] [st (max st at)]) open))
                     :open {}
                     :terminal-at at}

                    :else
                    {:intervals intervals :open open :terminal-at terminal-at}))
                {:intervals [] :open {} :terminal-at nil}
                sorted)
        end-ms (long (or terminal-at now-ms))
        final-intervals (into intervals (map (fn [[_ st]] [st (max st end-ms)]) open))
        merged (merge-intervals final-intervals)
        exec-ms (reduce + 0 (map (fn [[s e]] (max 0 (- (long e) (long s)))) merged))
        created (long (or (:created-at run) (some-> (first sorted) :at) end-ms))
        wall-ms (max 0 (- end-ms created))
        waiting-ms (max 0 (- wall-ms exec-ms))]
    {:execution-ms exec-ms
     :wall-ms wall-ms
     :waiting-ms waiting-ms
     :executing? (boolean (seq open))}))
