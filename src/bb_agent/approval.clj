(ns bb-agent.approval
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn approvals-file []
  (fs/path (config/home) "state" "approvals.edn"))

(defn load-state []
  (let [path (approvals-file)]
    (if (fs/regular-file? path)
      (edn/read-string (slurp (str path)))
      {:pending {} :decisions {} :approve-rest #{}})))

(defn save-state! [state]
  (let [path (approvals-file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str state))
    state))

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
    (save-state! (assoc-in (load-state) [:pending id] pending))
    pending))

(defn resolve! [session-id decision]
  (let [state (load-state)
        pending (->> (:pending state)
                     vals
                     (filter #(= session-id (:session/id %)))
                     (sort-by :created/at)
                     first)]
    (if pending
      (let [id (:approval/id pending)
            state* (-> state
                       (assoc-in [:decisions id] decision)
                       (update :pending dissoc id)
                       (cond-> (= decision :approve-rest)
                         (update :approve-rest conj session-id)))]
        (save-state! state*)
        (assoc pending :approval/decision decision))
      nil)))

(defn- consume-decision! [id]
  (let [state (load-state)
        decision (get-in state [:decisions id])]
    (when decision
      (save-state! (update state :decisions dissoc id))
      decision)))

(defn- approve-rest? [session-id]
  (contains? (:approve-rest (load-state)) session-id))

(defn waiting-approver [{:keys [session-id channel timeout-ms notify]}]
  (fn [request]
    (if (approve-rest? session-id)
      :approved
      (let [pending (create-pending! session-id channel request)
            deadline (+ (System/currentTimeMillis) (or timeout-ms 30000))]
        (when notify (notify pending))
        (loop []
          (if-let [decision (consume-decision! (:approval/id pending))]
            (case decision
              :approve :approved
              :approve-rest :approved
              :deny :denied
              :denied)
            (if (< (System/currentTimeMillis) deadline)
              (do (Thread/sleep 200) (recur))
              :denied)))))))

(defn interactive-approver []
  (let [approve-rest? (atom false)]
    (fn [{:tool/keys [name args]}]
      (if @approve-rest?
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
              ("a" "all" "rest") (do (reset! approve-rest? true) :approved)
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
