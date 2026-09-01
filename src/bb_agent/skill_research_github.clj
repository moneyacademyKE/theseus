(ns bb-agent.skill-research-github
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bb-agent.skill-research :as research]))

(def ^:private max-body-bytes (* 1024 1024))
(def ^:private user-agent "opencrabs-skill-research/1.0")

(defn- data-error [message data]
  (throw (ex-info message (assoc data :type :github-data-error))))

(defn- body-string [{:keys [status body]} op]
  (when-not (and (integer? status) (<= 200 status 299))
    (data-error "GitHub returned a non-success status" {:op op :status status}))
  (let [bytes (cond
                (string? body) (.getBytes ^String body "UTF-8")
                (bytes? body) body
                :else (data-error "GitHub returned a malformed body"
                                  {:op op :status status}))]
    (when (> (alength ^bytes bytes) max-body-bytes)
      (data-error "GitHub response body exceeds 1MB" {:op op :status status}))
    (String. ^bytes bytes "UTF-8")))

(defn- encode-segment [x]
  (-> (java.net.URLEncoder/encode (str x) "UTF-8")
      (str/replace "+" "%20")))

(defn- repository-path [full-name]
  (let [parts (str/split (or full-name "") #"/" -1)]
    (when-not (and (= 2 (count parts)) (every? (complement str/blank?) parts))
      (data-error "Malformed GitHub repository name" {:full-name full-name}))
    (str/join "/" (map encode-segment parts))))

(defn github-transport [{:keys [op query limit full-name]}]
  (case op
    :search
    (let [n (-> (or limit 5) long (max 1) (min 5))
          response (http/get "https://api.github.com/search/repositories"
                             {:query-params {:q query
                                             :sort "stars"
                                             :order "desc"
                                             :per_page n}
                              :headers {"accept" "application/vnd.github+json"
                                        "user-agent" user-agent}
                              :throw false
                              :timeout 15000})
          text (body-string response :search)
          parsed (try (json/parse-string text true)
                      (catch Exception e
                        (data-error "Malformed GitHub search JSON"
                                    {:op :search :cause (.getMessage e)})))]
      (when-not (and (map? parsed) (sequential? (:items parsed)))
        (data-error "Malformed GitHub search result" {:op :search}))
      {:items (:items parsed)})

    :readme
    (let [url (str "https://api.github.com/repos/"
                   (repository-path full-name) "/readme")
          response (http/get url
                             {:headers {"accept" "application/vnd.github.raw+json"
                                        "user-agent" user-agent}
                              :throw false
                              :timeout 15000})]
      (body-string response :readme))

    (data-error "Unsupported GitHub transport operation" {:op op})))

(defn- github-metadata [item]
  (when (map? item)
    {:full-name (or (:full-name item) (:full_name item))
     :name (:name item)
     :description (:description item)
     :stars (or (:stars item) (:stargazers_count item))
     :updated-at (or (:updated-at item) (:updated_at item))
     :url (or (:html-url item) (:html_url item) (:url item))}))

(defn- reasons-of [x fallback]
  (let [reasons (:reasons x)]
    (if (seq reasons) (vec reasons) [fallback])))

(defn- normalized [item]
  (let [original (or (:full-name item) (:full_name item))]
    (try
      (let [result (research/normalize-candidate (github-metadata item))
            candidate (if (map? (:candidate result)) (:candidate result) result)
            rejected? (or (not (map? result))
                          (#{:rejected :quarantined :invalid} (:status result))
                          (false? (:accepted? result))
                          (false? (:valid? result)))]
        (if rejected?
          {:rejection {:status :rejected
                       :full-name original
                       :reasons (reasons-of result "malformed candidate")}}
          {:candidate candidate}))
      (catch Exception e
        {:rejection {:status :rejected
                     :full-name original
                     :reasons [(or (.getMessage e) "malformed candidate")]}}))))

(defn- safe-inspection? [inspection]
  (and (map? inspection)
       (not (false? (:safe? inspection)))
       (not (#{:rejected :quarantined :unsafe :invalid} (:status inspection)))
       (or (true? (:safe? inspection))
           (#{:safe :accepted :ok} (:status inspection))
           (empty? (:reasons inspection)))))

(defn- invalid-query-result [reason]
  {:status :rejected
   :reasons [reason]
   :proposals []
   :rejections []
   :counts {:proposals 0 :rejections 0}})

(defn research! [root query {:keys [limit transport]}]
  (cond
    (not (string? query)) (invalid-query-result "query must be a string")
    (str/blank? query) (invalid-query-result "query must be nonblank")
    (> (count query) 256) (invalid-query-result "query must be at most 256 characters")
    :else
    (let [n (if (integer? limit) (-> limit (max 1) (min 5)) 5)
          send! (or transport github-transport)
          search-result (send! {:op :search :query query :limit n})
          items (if (sequential? (:items search-result)) (:items search-result) [])
          outcomes (mapv normalized items)
          initial-rejections (mapv :rejection (filter :rejection outcomes))
          candidates (mapv :candidate (filter :candidate outcomes))
          ranked (research/rank-candidates candidates n)
          fetched
          (mapv (fn [candidate]
                  (let [readme (send! {:op :readme
                                       :full-name (:full-name candidate)
                                       :url (:url candidate)})
                        inspection (research/inspect-text readme)]
                    (if (safe-inspection? inspection)
                      {:proposal (research/store-proposal!
                                  root (assoc candidate :readme readme))}
                      {:rejection {:status :rejected
                                   :full-name (:full-name candidate)
                                   :reasons (reasons-of inspection
                                                        "README failed safety inspection")}})))
                ranked)
          proposals (mapv :proposal (filter :proposal fetched))
          rejections (into initial-rejections
                           (map :rejection (filter :rejection fetched)))]
      {:status :ok
       :proposals proposals
       :rejections rejections
       :counts {:proposals (count proposals)
                :rejections (count rejections)}})))

(comment
  ;; Fetched content is always treated as inert data and is never evaluated.
  )
