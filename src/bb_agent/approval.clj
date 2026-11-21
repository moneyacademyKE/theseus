(ns bb-agent.approval
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute PosixFilePermissions)))

(def ^:private io-lock (Object.))
(def ^:private empty-state {:pending {} :decisions {} :approve-rest #{}})

(defn approvals-file []
  (fs/path (config/home) "state" "approvals.edn"))

(defn- lock-file [path]
  (fs/path (fs/parent path) "approvals.edn.lock"))

(defn- read-state-unlocked [path]
  (if (fs/regular-file? path)
    (try (or (edn/read-string (slurp (str path))) empty-state)
         (catch Exception _ empty-state))
    empty-state))

(defn- write-private-file!
  "Atomically write content with owner-only (0600) permissions from birth."
  [path content]
  (let [target (.toPath (java.io.File. (str path)))
        parent (.getParent target)]
    (when parent
      (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))
    (let [tmp (Paths/get (str parent)
                         (into-array String [(str "." (.getFileName target)
                                                  ".tmp." (System/nanoTime))]))
          bytes (.getBytes (str content) StandardCharsets/UTF_8)]
      (try
        (try
          (let [attr (PosixFilePermissions/asFileAttribute
                      (PosixFilePermissions/fromString "rw-------"))]
            (Files/createFile tmp (into-array java.nio.file.attribute.FileAttribute [attr])))
          (catch UnsupportedOperationException _
            (Files/createFile tmp (into-array java.nio.file.attribute.FileAttribute []))))
        (Files/write tmp bytes (into-array java.nio.file.OpenOption []))
        (try
          (Files/move tmp target
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING
                                   StandardCopyOption/ATOMIC_MOVE]))
          (catch Exception _
            (Files/move tmp target
                        (into-array java.nio.file.CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (try (Files/deleteIfExists tmp)
               (catch Exception _ nil)))))))

(defn- with-approvals-lock [f]
  (locking io-lock
    (let [path (approvals-file)
          parent (fs/parent path)]
      (fs/create-dirs parent)
      (with-open [ch (FileChannel/open
                      (.toPath (java.io.File. (str (lock-file path))))
                      (into-array java.nio.file.OpenOption
                                  [StandardOpenOption/CREATE
                                   StandardOpenOption/READ
                                   StandardOpenOption/WRITE]))
                  _lock (.lock ch)]
        (f path)))))

(defn- atomic-mutate-state!
  "Apply (f state) -> {:state state' :result r} under both the JVM lock and
   the companion FileChannel lock, writing atomically only when :state changed."
  [f]
  (with-approvals-lock
    (fn [path]
      (let [state (read-state-unlocked path)
            {:keys [state' result]} (f state)]
        (when (some? state')
          (write-private-file! path (pr-str state')))
        result))))

(defn load-state []
  (with-approvals-lock read-state-unlocked))

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
            sent (when notify (notify pending))]
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
                  (when on-expire (on-expire pending sent))
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
