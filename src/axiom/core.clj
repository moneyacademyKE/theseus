(ns axiom.core
  "The core loop: a PURE `decide` function + a side-effecting `run!`.

  Per decision-log D4: decide is pure (config, world, state) -> action,
  tested independently of any real agent. run! performs the I/O, threading
  decide through OBSERVE -> DECIDE -> [DONE | HALT | CHECKPOINT -> ACT ->
  REOBSERVE -> DECIDE].

  State machine (per SPEC):
    OBSERVE -> DECIDE -> { DONE | HALT | ACT -> REOBSERVE -> DECIDE }"
  (:require [axiom.config :as config]
            [axiom.predicates :as predicates]
            [axiom.observe :as observe]
            [axiom.lock :as lock]
            [axiom.log :as log]
            [axiom.git :as git]
            [axiom.notify :as notify]
            [axiom.harness :as harness]
            [axiom.control :as control]
            [axiom.budget :as budget]
            [axiom.decision :as decision]
            [babashka.process :refer [sh check]]
            [clojure.string :as str]))

(defn- render-template
  "Replace {{key}} placeholders with world values.
  e.g. \"./gen.sh {{attempt}}\" with {:attempt 2} -> \"./gen.sh 2\""
  [tmpl ctx]
  (reduce (fn [s [k v]]
            (str/replace s (str "{{" (name k) "}}") (str v)))
          tmpl ctx))

(defn- selected-act
  [cfg state]
  (let [act-specs (:act cfg)
        as-vec    (if (map? act-specs) [act-specs] act-specs)
        pidx      (or (:prompt-index state 0) 0)]
    (nth as-vec (min pidx (dec (count as-vec))))))

(defn- act-timeout-ms
  [cfg act]
  (long (or (:timeout act)
            (get-in cfg [:harness-success-poll :max-ms])
            120000)))

(defn- run-act!
  "Execute the configured act.

  Shell acts still run via bash -c and return a completed process result map.
  Harness acts may opt into :spawn mode, returning immediately with a live
  process handle so the core loop can poll the observed world and stop cleanly
  once success is proven by observers."
  [cfg world state]
  (let [act     (selected-act cfg state)
        pidx    (or (:prompt-index state 0) 0)
        attempt (:attempt state 0)
        models  (:models cfg)
        midx    (or (:model-index state 0) 0)
        model   (when (seq models)
                  (nth models (min midx (dec (count models))) ""))]
    (if (:harness act)
      (let [invocation (harness/build-invocation cfg world state act)
            mode       (or (:mode act) :spawn)]
        (log/event! (:log-dir cfg) :info "acting via harness"
                    (select-keys (assoc invocation :mode mode)
                                 [:harness :argv :prompt :model :mode]))
        (harness/invoke! cfg (assoc invocation :mode mode)))
      (let [tmpl    (:sh act)
            cmd     (render-template tmpl (assoc world :attempt attempt :model (or model "")))
            timeout (act-timeout-ms cfg act)]
        (log/event! (:log-dir cfg) :info "acting"
                    {:cmd cmd :attempt attempt :prompt-index pidx :model model})
        (sh {:out :string :err :string :dir (:workdir cfg ".")
             :continue true :timeout timeout} "bash" "-c" cmd)))))

(defn- process-alive?
  [proc]
  (try
    (nil? @(:exit proc))
    (catch Exception _ false)))

(defn- maybe-stop-process!
  [proc]
  (when (process-alive? proc)
    (try
      (.destroy ^Process (:proc proc))
      (catch Exception _ nil))))

(defn- await-process-result
  [proc]
  (try
    (check proc)
    (catch Exception ex
      (let [data (ex-data ex)]
        (if (map? data)
          data
          (throw ex))))))

(defn- harness-success-poll-result
  [cfg result act]
  (let [poll-ms     (long (or (get-in cfg [:harness-success-poll :interval-ms]) 200))
        deadline-ms (+ (System/currentTimeMillis) (act-timeout-ms cfg act))
        proc        (:process result)]
    (loop []
      (let [after-w  (observe/build-world (:observers cfg) {:dir (:workdir cfg ".")})
            success? (and (empty? (predicates/integrity-violations after-w (:integrity cfg)))
                          (predicates/goal-met? after-w (:goal cfg)))]
        (cond
          success?
          (do
            (maybe-stop-process! proc)
            {:result (assoc (await-process-result proc) :terminated-early? true)
             :after-world after-w
             :early-success? true})

          (not (process-alive? proc))
          {:result (await-process-result proc)
           :after-world after-w
           :early-success? false}

          (>= (System/currentTimeMillis) deadline-ms)
          (do
            (maybe-stop-process! proc)
            (let [done          (await-process-result proc)
                  after-timeout (observe/build-world (:observers cfg) {:dir (:workdir cfg ".")})]
              {:result (assoc done :timed-out? true)
               :after-world after-timeout
               :early-success? (and (empty? (predicates/integrity-violations after-timeout (:integrity cfg)))
                                    (predicates/goal-met? after-timeout (:goal cfg)))}))

          :else
          (do
            (Thread/sleep poll-ms)
            (recur)))))))

(defn- halt-result!
  [cfg iteration action]
  (let [workdir    (:workdir cfg ".")
        log-dir    (:log-dir cfg)
        tag-prefix (get-in cfg [:checkpoint :tag-prefix] "axiom")
        reason     (:reason action)]
    (log/event! log-dir :halt (str "HALT: " (name reason))
                (dissoc action :type))
    (log/iteration! log-dir iteration (assoc action :event :halt))
    (let [tag      (str tag-prefix "-last-good")
          restored (git/rollback! workdir tag)]
      (when restored
        (log/event! log-dir :info "rollback" {:to tag}))
      (let [bundle (log/halt-bundle! log-dir action)]
        (notify/notify! cfg action bundle))
      {:status :halt :reason reason :iterations iteration
       :detail action :rolled-back? (boolean restored)})))

(defn- swap-cfg
  [old-cfg new-cfg]
  (merge new-cfg (select-keys old-cfg [:workdir :lock :log-dir :checkpoint
                                        :config-path :hot-reload])))

(defn run!
  "Drive the loop to completion. Returns:
    {:status :done  :world w :iterations n}
    {:status :halt  :reason :integrity|:stall|:max-iters ...}

  Acquires the lock (refuses if held live); releases on exit. Logs every
  iteration as structured EDN. opts: {:max-iters N :config-path path}."
  ([cfg] (run! cfg {}))
  ([cfg opts]
   (let [workdir     (:workdir cfg ".")
         lock-path   (:lock cfg (str workdir "/.axiom.lock"))
         log-dir     (:log-dir cfg)
         max-iters   (:max-iters opts 1000)
         config-path (:config-path opts)
         init-mtime  (when config-path (config/file-mtime config-path))]
     (lock/acquire! lock-path)
     (try
       (loop [iteration  0
              cfg         cfg
              last-mtime  init-mtime
              state       {:stall 0 :thrash 0 :attempt 0 :seen-tuples #{}
                           :started-at-ms (System/currentTimeMillis)}]
         (let [[cfg* maybe-mtime] (if (and (:hot-reload cfg false) config-path)
                                    (config/maybe-reload cfg config-path last-mtime)
                                    [cfg nil])
               swapped?   (some? maybe-mtime)
               cfg        (if swapped?
                            (do (log/event! log-dir :info "hot-reload"
                                            {:config-path config-path})
                                (swap-cfg cfg cfg*))
                            cfg)
               last-mtime (if swapped? maybe-mtime last-mtime)
               world      (observe/build-world (:observers cfg) {:dir workdir})
               state      (assoc state :now-ms (System/currentTimeMillis))
               ctl        (control/state cfg)
               action     (cond
                            (= :stop-requested (:state ctl)) {:type :halt :reason :operator-stop :world world}
                            (= :paused (:state ctl)) {:type :halt :reason :operator-paused :world world}
                            (>= iteration max-iters) {:type :halt :reason :max-iters :world {}}
                            :else (decision/decide cfg world state))]
           (case (:type action)
             :done
             (do (log/event! log-dir :info "GOAL FULFILLED" {:world world})
                 (log/iteration! log-dir iteration {:event :done :world world})
                 {:status :done :world world :iterations iteration})

             :halt
             (halt-result! cfg iteration action)

             :rollback
             (let [tag      (str (get-in cfg [:checkpoint :tag-prefix] "axiom") "-last-good")
                   restored (git/rollback! workdir tag)
                   state'   (-> state
                                (update :rollbacks (fnil inc 0))
                                (assoc :stall 0))]
               (log/event! log-dir :warn "rollback recovery"
                           {:to tag :restored? (boolean restored)
                            :rollbacks (:rollbacks state')})
               (log/iteration! log-dir iteration
                               (assoc action :event :rollback
                                      :rolled-back? (boolean restored)))
               (recur (inc iteration) cfg last-mtime state'))

             :escalate
             (let [rung       (:rung action)
                   base-state (assoc state
                                     :stall 0 :thrash 0
                                     :escalation-index (:next-escalation-index action))
                   state'     (case rung
                                :reseed
                                (do (when-let [rs (:reseed cfg)]
                                      (let [cmd (render-template (:sh rs) world)]
                                        (log/event! log-dir :info "reseed" {:cmd cmd})
                                        (sh {:out :string :err :string :dir workdir
                                             :continue true} "bash" "-c" cmd)))
                                    base-state)
                                :reframe
                                (let [idx (inc (or (:prompt-index state 0) 0))]
                                  (log/event! log-dir :info "reframe" {:prompt-index idx})
                                  (assoc base-state :prompt-index idx))
                                :escalate-model
                                (let [idx (inc (or (:model-index state 0) 0))]
                                  (log/event! log-dir :info "escalate-model" {:model-index idx})
                                  (assoc base-state :model-index idx))
                                base-state)]
               (log/iteration! log-dir iteration
                               (assoc action :event :escalate :rung rung))
               (recur (inc iteration) cfg last-mtime state'))

             :act
             (let [attempt    (:attempt state 0)
                   pidx       (or (:prompt-index state 0) 0)
                   midx       (or (:model-index state 0) 0)
                   before     (predicates/progress-sig world (:progress cfg))
                   seen       (:seen-tuples state #{})
                   tuple      [pidx midx before]
                   identical? (and (:identical-retry-guard cfg false)
                                   (contains? seen tuple))
                   act        (selected-act cfg state)]
               (if identical?
                 (do
                   (log/event! log-dir :info "identical-skip"
                               {:tuple tuple :stall (inc (:stall state 0))})
                   (log/iteration! log-dir iteration
                                   {:event :identical-skip :tuple tuple
                                    :prompt-index pidx :model-index midx
                                    :attempt attempt})
                   (recur (inc iteration) cfg last-mtime
                          (-> state
                              (assoc :stall (inc (:stall state 0)))
                              (update :thrash (fnil inc 0)))))
                 (let [_        (git/tag! workdir (str (get-in cfg [:checkpoint :tag-prefix] "axiom") "-last-good"))
                       raw      (run-act! cfg world state)
                       wrapped  (if (= :spawn (:mode raw))
                                  (harness-success-poll-result cfg raw act)
                                  {:result raw
                                   :after-world (observe/build-world (:observers cfg) {:dir workdir})
                                   :early-success? false})
                       result   (:result wrapped)
                       after-w  (:after-world wrapped)
                       after    (predicates/progress-sig after-w (:progress cfg))
                       moved?   (or (:early-success? wrapped) (not= before after))
                       state'   (-> state
                                    (budget/add-usage result)
                                    (update :attempt inc)
                                    (assoc :stall (if moved? 0 (inc (:stall state 0))))
                                    (update :thrash (fnil inc 0))
                                    (assoc :seen-tuples (conj seen tuple)))]
                   (log/iteration! log-dir iteration
                                   (merge {:event :act :attempt attempt :exit (:exit result)
                                           :duration-ms (:duration result)
                                           :progress-before before :progress-after after
                                           :progressed? moved?
                                           :early-success? (:early-success? wrapped false)
                                           :terminated-early? (:terminated-early? result false)
                                           :timed-out? (:timed-out? result false)}
                                          (budget/totals state')))
                   (recur (inc iteration) cfg last-mtime state')))))))
       (finally
         (lock/release! lock-path))))))