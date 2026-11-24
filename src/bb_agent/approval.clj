(ns bb-agent.approval
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private empty-state {:pending {} :decisions {} :approve-rest #{}})

(defn approvals-file []
  (fs/path (config/home) "state" "approvals.edn"))

(defn- lock-file [path]
  (fs/path (fs/parent path) "approvals.edn.lock"))

(defn- atomic-mutate-state!
  "Apply (f state) -> {:state' state' :result r} under both the JVM lock and
   the companion FileChannel lock, writing atomically only when :state' changed."
  [f]
  (let [path (approvals-file)]
    (aio/atomic-mutate-edn! path (lock-file path) empty-state f)))

(defn load-state []
  (let [path (approvals-file)]
    (aio/with-file-lock (lock-file path)
      #(aio/read-edn-safe path empty-state))))

(defn save-state! [state]
  (atomic-mutate-state! (fn [_] {:state' state :result state})))

(defn- approval-id []
  (str (java.util.UUID/randomUUID)))

(defn create-pending! [session-id channel request]
  (let [id (approval-id)
        pending {:approval/id id
                 :session/id session-id
                 :channel channel
                 :tool/name (:tool/name request)
                 :tool/args (:tool/args request)
                 :created/at (str (java.time.Instant/now))}]
    (atomic-mutate-state!
     (fn [state]
       {:state' (assoc-in state [:pending id] pending)
        :result pending}))))

(defn resolve! [session-id decision]
  (atomic-mutate-state!
   (fn [state]
     (when-let [pending (->> (:pending state)
                             vals
                             (filter #(= session-id (:session/id %)))
                             (sort-by :created/at)
                             first)]
       (let [id (:approval/id pending)
             state* (-> state
                        (assoc-in [:decisions id] decision)
                        (update :pending dissoc id)
                        (cond-> (= decision :approve-rest)
                          (update :approve-rest conj session-id)))]
         {:state' state*
          :result (assoc pending :approval/decision decision)})))))

(defn resolve-by-id!
  "Resolve one specific pending approval by id, but only when it belongs
   to the expected session. Returns the pending with its decision, or nil.
   Button callbacks bind to a single approval instead of FIFO order."
  [target-id session-id decision]
  (atomic-mutate-state!
   (fn [state]
     (let [pending (get-in state [:pending target-id])]
       (when (and pending (= session-id (:session/id pending)))
         (let [state* (-> state
                          (assoc-in [:decisions target-id] decision)
                          (update :pending dissoc target-id)
                          (cond-> (= decision :approve-rest)
                            (update :approve-rest conj session-id)))]
           {:state' state*
            :result (assoc pending :approval/decision decision)}))))))

(defn- consume-decision! [id]
  (atomic-mutate-state!
   (fn [state]
     (when-let [decision (get-in state [:decisions id])]
       {:state' (update state :decisions dissoc id)
        :result decision}))))

(defn- approve-rest? [session-id]
  (contains? (:approve-rest (load-state)) session-id))

(defn waiting-approver [{:keys [session-id channel timeout-ms notify on-expire]}]
  (fn [request]
    (if (approve-rest? session-id)
      :approved
      (let [pending (create-pending! session-id channel request)
            deadline (+ (System/currentTimeMillis) (or timeout-ms 30000))
            sent (when notify
                   (try (notify pending)
                        (catch Exception _ nil)))]
        (loop []
          (if-let [decision (consume-decision! (:approval/id pending))]
            (case decision
              :approve :approved
              :approve-rest :approved
              :deny :denied
              :denied)
            (if (< (System/currentTimeMillis) deadline)
              (do (Thread/sleep 200) (recur))
              (do (resolve-by-id! (:approval/id pending) session-id :expired)
                  (when on-expire
                    (try (on-expire pending sent)
                         (catch Exception _ nil)))
                  :denied))))))))

(defn interactive-approver []
  (let [approve-rest-flag (atom false)]
    (fn [{:tool/keys [name args]}]
      (if @approve-rest-flag
        :approved
        (do
          (binding [*out* *err*]
            (println)
            (println (str "Tool request: " name))
            (println (pr-str args))
            (println "Approve? [y]es / [n]o / [a]pprove rest of turn:"))
          (let [answer (-> (read-line) (or "") str/lower-case str/trim)]
            (case answer
              ("y" "yes") :approved
              ("a" "all" "rest") (do (reset! approve-rest-flag true) :approved)
              :denied)))))))

(defn telegram-approval-command? [text]
  (contains? #{"/approve" "/deny" "/approve-rest" "approve" "deny" "approve rest"}
             (-> (or text "") str/lower-case str/trim)))

(defn telegram-approval-reply [text]
  (case (-> (or text "") str/lower-case str/trim)
    ("/approve" "approve") :approve
    ("/approve-rest" "approve rest") :approve-rest
    ("/deny" "deny") :deny
    nil))

(defn approval-reply-text [resolved decision]
  (if resolved
    (case decision
      :approve "Approved pending tool."
      :approve-rest "Approved pending tool and the rest of this session."
      :deny "Denied pending tool."
      "Approval reply recorded.")
    "No pending tool approval for this chat."))
