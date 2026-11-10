(ns bb-agent.web-http
  "Shared HTTP plumbing for the web deck: response envelopes, auth
  extraction, query/body parsing, and the guarded handler wrapper.

  Split out of web.clj (2026-11-10) so the route modules and the app
  compose over the same transport vocabulary instead of reaching into
  each other. Pure functions only — no server state lives here."
  (:require [bb-agent.web-auth :as auth]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn bearer
  "The raw token from Authorization: Bearer <token>, or nil."
  [req]
  (some-> (get-in req [:headers "authorization"])
          (str/split #"\s+" 2)
          (second)))

(defn authorized?
  "A live session token in Authorization: Bearer <token> — any role
  (owner or read-only). Reads accept both; writes want owner?."
  [req]
  (auth/session-live? (bearer req)))

(defn owner?
  "The request's session carries the owner role — the write surface's
  gate. Read-only sessions fail this and get 403, not 401: they are
  logged in, just not allowed to write (parity row 19)."
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

(defn query-params
  "Flat ?k=v&k2=v2 parse with URL decoding — enough for name/mime/id."
  [req]
  (->> (str/split (str (or (:query-string req) "")) #"&")
       (keep (fn [kv]
               (when (seq kv)
                 (let [[k v] (str/split kv #"=" 2)]
                   [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                    (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
       (into {})))

(defn parse-json-body
  "Body → keyword-keyed map, or nil on anything unparsable."
  [req]
  (try
    (let [v (json/parse-string (slurp (:body req)) true)]
      (when (map? v) v))
    (catch Exception _ nil)))

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
  the handler for owners. The write surface is one gate, not six."
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
