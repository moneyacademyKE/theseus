(ns bb-agent.secret-mask
  "Secret-masking round-trip for Web Deck / MCP / provider configs
   (ported from LoopForge domain/mcp_masking.go).
   Replaces sensitive env vars and literal headers with `\"******\"` on read,
   and preserves stored secrets when `\"******\"` is submitted back unchanged."
  (:require [clojure.string :as str]))

(def masked-sentinel "******")

(def ^:private sensitive-key-parts
  ["TOKEN" "SECRET" "PASSWORD" "PASSWD" "API_KEY" "APIKEY"
   "ACCESS_KEY" "PRIVATE_KEY" "AUTH" "COOKIE" "CREDENTIAL" "SESSION"])

(defn sensitive-key?
  "True when env/config key `k` looks like a credential or secret."
  [k]
  (let [u (str/upper-case (if (keyword? k) (name k) (str k)))]
    (boolean (some #(str/includes? u %) sensitive-key-parts))))

(defn- mask-map [m all-keys?]
  (when (map? m)
    (reduce-kv
     (fn [acc k v]
       (assoc acc k (if (and (string? v) (not (str/blank? v))
                             (or all-keys? (sensitive-key? k)))
                      masked-sentinel
                      v)))
     {}
     m)))

(defn mask-config
  "Mask literal `:headers` values and sensitive `:env` / top-level secret keys."
  [cfg]
  (if-not (map? cfg)
    cfg
    (reduce-kv
     (fn [acc k v]
       (cond
         (= k :headers) (assoc acc k (mask-map v true))
         (= k :env) (assoc acc k (mask-map v false))
         (and (string? v) (not (str/blank? v)) (sensitive-key? k))
         (assoc acc k masked-sentinel)
         :else (assoc acc k v)))
     {}
     cfg)))

(defn- merge-masked-map [stored incoming]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k (if (and (= v masked-sentinel) (contains? stored k))
                    (get stored k)
                    v)))
   {}
   (or incoming {})))

(defn merge-masked-update
  "Apply `incoming` config update over `stored`, preserving any stored secret
   whose incoming value is the unchanged `masked-sentinel` (`\"******\"`)."
  [stored incoming]
  (reduce-kv
   (fn [acc k v]
     (cond
       (and (map? v) (map? (get stored k)))
       (assoc acc k (merge-masked-map (get stored k) v))

       (and (= v masked-sentinel) (contains? stored k))
       (assoc acc k (get stored k))

       :else (assoc acc k v)))
   {}
   (or incoming {})))
