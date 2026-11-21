(ns bb-agent.web-http
  "Shared HTTP plumbing for the web deck: response envelopes, auth
  extraction, bounded body/query parsing, and the guarded handler wrapper."
  (:require [bb-agent.web-auth :as auth]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-max-json-bytes (* 1024 1024))

(defn bearer
  "The raw token from Authorization: Bearer <token>, or nil. Rejects
  other schemes such as Basic."
  [req]
  (when-let [h (get-in req [:headers "authorization"])]
    (second (re-matches #"(?i)^Bearer\s+(\S+)$" (str/trim (str h))))))

(defn authorized?
  "A live session token in Authorization: Bearer <token> — any role
  (owner or read-only)."
  [req]
  (auth/session-live? (bearer req)))

(defn owner?
  "The request's session carries the owner role — the write surface's gate."
  [req]
  (= :owner (auth/session-role (bearer req))))

(defn respond
  "The base response envelope: status, content type, no-store."
  [code body type]
  {:status code
   :headers {"Content-Type" type "Cache-Control" "no-store"}
   :body body})

(defn json-res
  "A JSON response: the standard envelope for every API route."
  [code obj]
  (respond code (json/generate-string obj) "application/json; charset=utf-8"))

(defn- safe-url-decode [s]
  (try
    (java.net.URLDecoder/decode (str s) "UTF-8")
    (catch IllegalArgumentException _ nil)))

(defn query-params
  "Flat ?k=v&k2=v2 parse with safe URL decoding — malformed percent escapes
  drop the invalid pair rather than throwing 500."
  [req]
  (->> (str/split (str (or (:query-string req) "")) #"&")
       (keep (fn [kv]
               (when (seq kv)
                 (let [[k v] (str/split kv #"=" 2)
                       dk (safe-url-decode k)
                       dv (safe-url-decode (or v ""))]
                   (when (and dk (some? dv))
                     [(keyword dk) dv])))))
       (into {})))

(defn read-bounded-bytes
  "Read body into a byte array capped at max-bytes; throws ex-info {:status 413}
  before buffering past the ceiling."
  [body max-bytes]
  (cond
    (nil? body) (byte-array 0)
    (bytes? body) (if (> (alength ^bytes body) max-bytes)
                    (throw (ex-info "request body too large" {:status 413}))
                    ^bytes body)
    (string? body) (let [bs (.getBytes ^String body "UTF-8")]
                     (if (> (alength bs) max-bytes)
                       (throw (ex-info "request body too large" {:status 413}))
                       bs))
    (instance? java.io.InputStream body)
    (let [baos (java.io.ByteArrayOutputStream.)
          buf (byte-array 8192)
          ^java.io.InputStream is body]
      (loop [total 0]
        (let [n (.read is buf)]
          (if (neg? n)
            (.toByteArray baos)
            (let [total* (+ total n)]
              (if (> total* max-bytes)
                (throw (ex-info "request body too large" {:status 413}))
                (do (.write baos buf 0 n)
                    (recur total*))))))))
    (instance? java.io.Reader body)
    (let [sb (StringBuilder.)
          buf (char-array 4096)
          ^java.io.Reader rdr body]
      (loop []
        (let [n (.read rdr buf)]
          (if (neg? n)
            (let [bs (.getBytes (.toString sb) "UTF-8")]
              (if (> (alength bs) max-bytes)
                (throw (ex-info "request body too large" {:status 413}))
                bs))
            (do
              (.append sb buf 0 n)
              (when (> (.length sb) max-bytes)
                (throw (ex-info "request body too large" {:status 413})))
              (recur))))))
    :else (read-bounded-bytes (slurp body) max-bytes)))

(defn read-bounded-string
  "Read body as UTF-8 string capped at max-bytes; throws ex-info {:status 413}
  when exceeded."
  [body max-bytes]
  (String. (read-bounded-bytes body max-bytes) "UTF-8"))

(defn parse-json-body
  "Bounded body -> keyword-keyed map, or nil on unparsable non-map input.
  Throws ex-info {:status 413} if body exceeds max-bytes."
  ([req] (parse-json-body req default-max-json-bytes))
  ([req max-bytes]
   (let [raw (read-bounded-string (:body req) max-bytes)]
     (try
       (let [v (json/parse-string raw true)]
         (when (map? v) v))
       (catch Exception _ nil)))))

(defn guarded
  "The auth + error envelope every cockpit READ handler rides: 401
  without a live token, the handler's data as 200 JSON, ex-info :status
  for deliberate failures, 500 otherwise."
  [req handler]
  (if-not (authorized? req)
    (json-res 401 {:error "unauthorized"})
    (try
      (json-res 200 (handler))
      (catch Exception e
        (json-res (or (get (ex-data e) :status) 500)
                  {:error (str (.getMessage e))})))))

(defn guarded-owner
  "guarded's write-side sibling: 401 anonymous, 403 read-only sessions,
  the handler for owners."
  [req handler]
  (if-not (authorized? req)
    (json-res 401 {:error "unauthorized"})
    (if-not (owner? req)
      (json-res 403 {:error "read-only"})
      (try
        (json-res 200 (handler))
        (catch Exception e
          (json-res (or (get (ex-data e) :status) 500)
                    {:error (str (.getMessage e))}))))))
