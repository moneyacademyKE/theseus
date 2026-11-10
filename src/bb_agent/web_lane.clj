(ns bb-agent.web-lane
  "Durable state for the web cockpit lane: jobs and upload descriptors
  persisted to state/web-lane.edn.

  The durable truth of a conversation is the session file (turns under
  the shared lock); this store is the lane's bookkeeping — queue state,
  inputs, upload descriptors — the minimum a restart needs to answer
  'what happened to my message' honestly instead of vanishing. Flow
  events are deliberately ephemeral: liveness, not truth.

  Interrupted recovery is conservative: a job found queued or running at
  boot is marked :interrupted, never silently re-executed — side effects
  either fired or never fired, and only the runner knows which. Retry is
  an explicit operator action."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [bb-agent.config :as config]))

(defn state-file
  "Where the lane's registries live: <home>/state/web-lane.edn."
  []
  (fs/path (config/home) "state" "web-lane.edn"))

(defn- persistent-job
  "Strip the ephemeral fields: flow events and the pending-approval
  snapshot are process-local liveness."
  [job]
  (dissoc job :events :approval))

(defn persist!
  "Write the registries (minus ephemera). Called at the few state
  transitions that matter — submit, terminal, cancel, retry, approval
  changes — never per flow event."
  [jobs uploads]
  (let [path (state-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path)
          (pr-str {:jobs (into {} (map (fn [[id j]] [id (persistent-job j)]) jobs))
                   :uploads uploads
                   :saved/at (str (java.time.Instant/now))}))))

(defn- job-number [id]
  (some->> (re-matches #"job-(\d+)" (str id)) second (Long/parseLong)))

(defn recover!
  "Boot recovery: read the persisted registries. Non-terminal jobs are
  marked :interrupted (error names the restart); finished jobs return
  as-is; upload descriptors rehydrate so a retry can find its bytes.
  Returns {:jobs .. :uploads .. :next-seq .. :interrupted [ids]}."
  []
  (let [data (try
               (when (fs/regular-file? (state-file))
                 (edn/read-string (slurp (str (state-file)))))
               (catch Exception _ nil))
        jobs (or (:jobs data) {})
        uploads (or (:uploads data) {})
        interrupted (keep (fn [[id j]]
                            (when (contains? #{:queued :running} (:status j)) (str id)))
                          jobs)
        recovered (into {}
                        (map (fn [[id j]]
                               [(str id)
                                (if (contains? #{:queued :running} (:status j))
                                  (assoc j :status :interrupted
                                         :error "theseus restarted while this turn was running"
                                         :finished-at (System/currentTimeMillis))
                                  j)]))
                        jobs)]
    {:jobs recovered
     :uploads uploads
     :next-seq (or (some->> (vals jobs) (keep job-number) seq (apply max)) 0)
     :interrupted interrupted}))
