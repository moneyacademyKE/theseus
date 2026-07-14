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

(defn deny-result [{:tool/keys [name] :as request}]
  {:tool/name name
   :status :denied
   :executed? false
   :approval/required? (:approval/required? request)
   :error/message (str "Tool " name " requires explicit approval")})

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
