(ns bb-agent.retention
  "Two-horizon state retention policy (ported from LoopForge
   botinteraction/retention.go). Separates heavy body retention (default 90d)
   from lightweight audit-record retention (default 180d), enforcing
   record-days >= body-days automatically.")

(def default-body-days 90)
(def default-record-days 180)
(def ^:private day-ms (* 24 3600 1000))

(defn normalize-policy
  "Normalize {:body-days :record-days}, raising record-days to body-days if shorter."
  [{:keys [body-days record-days]}]
  (let [b (max 0 (long (if (some? body-days) body-days default-body-days)))
        r (max 0 (long (if (some? record-days) record-days default-record-days)))
        r (if (and (pos? b) (pos? r) (< r b)) b r)]
    {:body-days b :record-days r}))

(defn apply-retention
  "Apply two-horizon retention to a sequence of maps carrying `:ts-ms` and optional `:body`.
   Returns {:kept [...] :bodies-stripped N :records-dropped N}."
  [rows now-ms policy]
  (let [{:keys [body-days record-days]} (normalize-policy policy)
        body-cutoff (when (pos? body-days) (- (long now-ms) (* body-days day-ms)))
        rec-cutoff (when (pos? record-days) (- (long now-ms) (* record-days day-ms)))]
    (reduce (fn [{:keys [kept bodies-stripped records-dropped]} row]
              (let [ts (long (or (:ts-ms row) now-ms))]
                (cond
                  (and rec-cutoff (< ts rec-cutoff))
                  {:kept kept :bodies-stripped bodies-stripped :records-dropped (inc records-dropped)}

                  (and body-cutoff (< ts body-cutoff) (contains? row :body))
                  {:kept (conj kept (dissoc row :body))
                   :bodies-stripped (inc bodies-stripped)
                   :records-dropped records-dropped}

                  :else
                  {:kept (conj kept row)
                   :bodies-stripped bodies-stripped
                   :records-dropped records-dropped})))
            {:kept [] :bodies-stripped 0 :records-dropped 0}
            rows)))
