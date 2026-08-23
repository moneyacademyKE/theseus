(ns bb-agent.subagent
  "Subagent ledger: hermes-beam's supervisor flattened into pure
  record transitions (:pending -> :claimed -> :completed/:failed)
  plus a file-backed shell. Process maps, not processes.

  Handles are id-or-record; pending/claimed return records; capacity
  is data (can-claim? predicate + claim's enforcing 4-arity)."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.edn :as edn]))

(defn- ->id [x]
  (if (map? x) (:subagent/id x) x))

(defn spawn
  "([ledger prompt] [ledger id prompt]) -> [ledger record].
  Explicit id replaces an existing record instead of duplicating."
  ([ledger prompt]
   (spawn ledger (str (java.util.UUID/randomUUID)) prompt))
  ([ledger id prompt]
   (let [record {:subagent/id     (->id id)
                 :subagent/prompt prompt
                 :subagent/status :pending}]
     [(conj (vec (remove #(= (:subagent/id record) (:subagent/id %)) ledger))
            record)
      record])))

(defn by-id [ledger id]
  (first (filter #(= (->id id) (:subagent/id %)) ledger)))

(defn- transition
  "Rewrite the record for `id` when its status is in `froms`;
  otherwise the ledger is returned unchanged."
  [ledger id froms f]
  (let [id (->id id)]
    (mapv (fn [r] (if (and (= id (:subagent/id r))
                           (contains? froms (:subagent/status r)))
                    (f r) r)) ledger)))

(defn claim
  "([ledger id assignee t] [ledger id assignee t capacity]).
  pending -> claimed. No-op on unknown id or non-pending record; the
  capacity arity also no-ops when `capacity` claims are outstanding."
  ([ledger id assignee t]
   (transition ledger id #{:pending}
               #(assoc % :subagent/status :claimed
                         :subagent/assignee assignee
                         :subagent/at t)))
  ([ledger id assignee t capacity]
   (if (< (count (filterv #(= :claimed (:subagent/status %)) ledger))
          (long capacity))
     (claim ledger id assignee t)
     ledger)))

(defn complete
  "claimed -> completed with :subagent/result. No-op otherwise."
  [ledger id result t]
  (transition ledger id #{:claimed}
              #(assoc % :subagent/status :completed
                        :subagent/result result
                        :subagent/at t)))

(defn fail
  "claimed -> failed with :subagent/reason. No-op otherwise."
  [ledger id reason t]
  (transition ledger id #{:claimed}
              #(assoc % :subagent/status :failed
                        :subagent/reason reason
                        :subagent/at t)))

(defn pending [ledger]
  (filterv #(= :pending (:subagent/status %)) ledger))

(defn claimed [ledger]
  (filterv #(= :claimed (:subagent/status %)) ledger))

(defn can-claim? [ledger capacity]
  (< (count (claimed ledger)) (long capacity)))

;; --- file shell: each bang is load -> pure transition -> save ---

(defn- ledger-file []
  (fs/path (config/home) "state" "subagents.edn"))

(defn- load-ledger []
  (if (fs/regular-file? (ledger-file))
    (edn/read-string (slurp (str (ledger-file))))
    []))

(defn- save-ledger! [ledger]
  (fs/create-dirs (fs/parent (ledger-file)))
  (spit (str (ledger-file)) (pr-str (vec ledger))))

(defn- apply! [f & args]
  (let [ledger' (apply f (load-ledger) args)]
    (save-ledger! ledger')
    ledger'))

(defn spawn! [id prompt]
  (let [[ledger _record] (spawn (load-ledger) (->id id) prompt)]
    (save-ledger! ledger)
    ledger))

(defn claim! [id assignee t]
  (apply! claim (->id id) assignee t))

(defn complete! [id result]
  (apply! complete (->id id) result (System/currentTimeMillis)))
