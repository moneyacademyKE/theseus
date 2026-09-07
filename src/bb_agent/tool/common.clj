(ns bb-agent.tool.common)

(def default-shell-timeout-ms 5000)
(def max-read-bytes 1000000)
(def max-write-bytes 1000000)
(def max-search-results 50)
(def max-doc-chars 20000)
(def safe-auto-tools #{"read_file" "search" "document_read" "git_status"})

(defn normalize-request [request]
  (-> request
      (update :tool/name str)
      (update :approval/policy #(or % :ask))
      (assoc :approval/required? true)))

(defn approval-decision [{:approval/keys [policy] :tool/keys [name]}]
  (case policy
    :auto-all :approved
    :auto-safe (if (contains? safe-auto-tools name) :approved :denied)
    :never :denied
    :ask :ask
    :denied))

(defn deny-result
  "A denied tool call. The message names the denial SOURCE and the recourse
   (B4, 2026-09-07: a floor-denied `rm` produced a reply that explained
   nothing) — the model relays this, so the human learns which rule fired
   and what would grant it, instead of a bare 🚫."
  ([request] (deny-result request :approval))
  ([{:tool/keys [name] :as request} source]
   {:tool/name name
    :status :denied
    :executed? false
    :approval/required? (:approval/required? request)
    :error/message
    (case source
      :policy (str "Tool " name " denied by the tool constitution (brain/rules.clj). "
                   "Recourse: the owner can grant it by editing brain/rules.clj. "
                   "Tell the user which rule fired instead of retrying.")
      :approval (str "Tool " name " requires explicit approval and no approver is "
                     "present in this turn. Recourse: ask the user to approve, or "
                     "report this step as skipped — do not silently retry.")
      (str "Tool " name " requires explicit approval"))}))

(defn ok-result [name body]
  (merge {:tool/name name
          :status :ok
          :executed? true}
         body))

(defn error-result [name message body]
  (merge {:tool/name name
          :status :error
          :executed? true
          :error/message message}
         body))

(defn stringify-path [path]
  (when path
    (str path)))

(defn safe-timeout-ms [value]
  (let [n (cond
            (integer? value) value
            (number? value) (long value)
            :else default-shell-timeout-ms)]
    (-> n (max 1) (min 60000))))

(defn bounded-count [value default]
  (let [n (cond
            (integer? value) value
            (number? value) (long value)
            :else default)]
    (-> n (max 1) (min max-search-results))))
