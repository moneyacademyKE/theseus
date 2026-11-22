(ns bb-agent.plan
  "Slim, file-backed interactive session plan and mechanical plan gate.
   Persists per-session plans under state/plans/<session-id>.edn via
   bb-agent.atomic-io and supports optional shell verification commands
   on step completion (Ralph-style ground-truth checks)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private gated-tools
  #{"write_file" "edit_file" "shell" "launch_goal" "spawn_subagent"})

(defn- safe-session-id [sid]
  (let [s (str/replace (str (or sid "default")) #"[^A-Za-z0-9_.-]" "_")]
    (if (str/blank? s) "default" s)))

(defn plan-file [session-id]
  (fs/path (config/home) "state" "plans" (str (safe-session-id session-id) ".edn")))

(defn load-plan [session-id]
  (when-not (str/blank? (str (or session-id "")))
    (aio/read-edn-safe (plan-file session-id) nil)))

(defn has-active-plan? [session-id]
  (let [p (load-plan session-id)]
    (boolean (and (map? p) (= :active (:status p)) (seq (:steps p))))))

(defn plan-gate-blocked?
  "True when cfg sets :plan/gate-after-tool-calls N, the turn has already
   executed >= N tool calls, tool-name is a mutating/control tool, and the
   session has no active plan."
  [cfg tool-name]
  (let [threshold (:plan/gate-after-tool-calls cfg)
        used (long (or (:turn/tool-calls cfg) 0))
        sid (:session/id cfg)]
    (and (integer? threshold)
         (pos? threshold)
         (>= used threshold)
         (contains? gated-tools tool-name)
         (not (has-active-plan? sid)))))

(defn init-plan! [session-id title steps]
  (let [clean-steps (->> steps
                         (map #(str/trim (str %)))
                         (remove str/blank?)
                         (map-indexed (fn [idx text]
                                        {:index (inc idx)
                                         :title text
                                         :status :pending}))
                         vec)
        record {:session/id (safe-session-id session-id)
                :title (str/trim (str (or title "Session Plan")))
                :status :active
                :created-at (System/currentTimeMillis)
                :steps clean-steps}]
    (aio/write-private-file! (plan-file session-id) (pr-str record))
    record))

(defn- update-step! [session-id step-idx f]
  (let [f-path (plan-file session-id)]
    (aio/update-edn!
     f-path nil
     (fn [p]
       (when (map? p)
         (update p :steps
                 (fn [steps]
                   (mapv (fn [st]
                           (if (= (long step-idx) (long (:index st)))
                             (f st)
                             st))
                         steps))))))))

(defn- run-verify-cmd [cmd cwd]
  (let [dir (if (and (string? cwd) (fs/directory? cwd)) cwd (str (config/home)))
        proc (p/process ["bash" "-c" cmd] {:dir dir :out :string :err :string})
        res (deref proc 30000 ::timeout)]
    (if (= ::timeout res)
      (do (p/destroy-tree proc)
          {:ok? false :exit -1 :error "verification command timed out (30s)"})
      {:ok? (zero? (:exit res))
       :exit (:exit res)
       :stdout (:out res)
       :stderr (:err res)})))

(defn start-step! [session-id step-idx]
  (update-step! session-id step-idx #(assoc % :status :in-progress :started-at (System/currentTimeMillis))))

(defn complete-step! [session-id step-idx {:keys [verify-cmd cwd note]}]
  (if-let [p (load-plan session-id)]
    (if-not (some #(= (long step-idx) (long (:index %))) (:steps p))
      {:error (str "Unknown plan step: " step-idx)}
      (if (and (string? verify-cmd) (not (str/blank? verify-cmd)))
        (let [v (run-verify-cmd verify-cmd cwd)]
          (if (:ok? v)
            {:ok true
             :plan (update-step! session-id step-idx
                                 #(cond-> (assoc % :status :done
                                                 :completed-at (System/currentTimeMillis)
                                                 :verified-by verify-cmd)
                                    note (assoc :note note)))
             :verify v}
            {:error (str "Verification failed (exit " (:exit v) "): "
                         (str/trim (str (:stderr v) " " (:stdout v))))
             :verify v}))
        {:ok true
         :plan (update-step! session-id step-idx
                             #(cond-> (assoc % :status :done
                                             :completed-at (System/currentTimeMillis))
                                note (assoc :note note)))}))
    {:error "No active plan for this session"}))

(defn clear-plan! [session-id]
  (let [p (plan-file session-id)]
    (when (fs/exists? p)
      (fs/delete p))
    {:cleared? true}))

(defn run-plan-tool
  "Execute a `plan` tool request and return a standard tool result map."
  [{:keys [action title steps step verify_cmd note]} cfg]
  (let [sid (or (:session/id cfg) "default")
        act (str/lower-case (str (or action "status")))]
    (case act
      "init"
      (if (and (sequential? steps) (seq steps))
        (let [p (init-plan! sid title steps)]
          {:tool/name "plan" :status :ok :executed? true :plan p})
        {:tool/name "plan" :status :error :executed? false
         :error/message "plan init requires a non-empty :steps vector"})

      "start"
      (if (integer? step)
        (if-let [p (start-step! sid step)]
          {:tool/name "plan" :status :ok :executed? true :plan p}
          {:tool/name "plan" :status :error :executed? false
           :error/message "No active plan for this session"})
        {:tool/name "plan" :status :error :executed? false
         :error/message "plan start requires integer :step"})

      "complete"
      (if (integer? step)
        (let [res (complete-step! sid step {:verify-cmd verify_cmd :cwd (:cwd cfg) :note note})]
          (if (:ok res)
            {:tool/name "plan" :status :ok :executed? true
             :plan (:plan res) :verify (:verify res)}
            {:tool/name "plan" :status :error :executed? false
             :error/message (:error res) :verify (:verify res)}))
        {:tool/name "plan" :status :error :executed? false
         :error/message "plan complete requires integer :step"})

      "status"
      {:tool/name "plan" :status :ok :executed? true :plan (load-plan sid)}

      "clear"
      (assoc (clear-plan! sid) :tool/name "plan" :status :ok :executed? true)

      {:tool/name "plan" :status :error :executed? false
       :error/message (str "Unknown plan action: " action)})))
