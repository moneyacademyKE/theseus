#!/usr/bin/env bb
(ns axiom.main
  "Axiom -- autonomous goal-fulfillment runner.

  Usage:
    bb axiom.clj <config.edn> [--max-iters N] [--once]
    bb axiom.clj status <config.edn> [--last N]
    bb axiom.clj pause|resume|stop <config.edn>

  Exits 0 if the goal was fulfilled, 1 on halt (stall/integrity/max-iters),
  2 on usage error. The `status`, `pause`, `resume`, and `stop` subcommands
  are operator commands and exit 0 when the request is recorded/read.")

(require '[axiom.config :as config]
         '[axiom.control :as control]
         '[axiom.core :as core]
         '[axiom.status :as status]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn- parse-run-args
  "Parse the flags for a normal run: --max-iters N / --max-iters=N / --once.
  The first positional arg is the config path."
  [args]
  (loop [args args acc {:max-iters 1000 :once false :config nil}]
    (cond
      (empty? args) acc
      (= (first args) "--max-iters")
      (recur (nnext args) (assoc acc :max-iters (Integer/parseInt (second args))))
      (str/starts-with? (first args) "--max-iters=")
      (recur (next args) (assoc acc :max-iters
                                (Integer/parseInt (subs (first args) 12))))
      (= (first args) "--once") (recur (next args) (assoc acc :once true))
      (= (first args) "--last")
      (recur (nnext args) (assoc acc :last (Integer/parseInt (second args))))
      (str/starts-with? (first args) "--last=")
      (recur (next args) (assoc acc :last (Integer/parseInt (subs (first args) 7))))
      :else (recur (next args) (assoc acc :config (first args))))))

(defn- usage! [message]
  (println message)
  (System/exit 2))

(defn- load-flat-config
  [path]
  (edn/read-string (slurp path)))

(defn- status! [args]
  (let [opts (parse-run-args args)]
    (when-not (:config opts)
      (usage! "Usage: bb axiom.clj status <config.edn> [--last N]"))
    ;; Read config as raw EDN (no validation) so a partially-broken or
    ;; stale config can still report its last-known run state.
    (status/summarize! (load-flat-config (:config opts)) {:last (:last opts 20)})
    (System/exit 0)))

(defn- control! [command args]
  (let [opts (parse-run-args args)]
    (when-not (:config opts)
      (usage! "Usage: bb axiom.clj pause|resume|stop <config.edn>"))
    (let [result (control/apply-command! (load-flat-config (:config opts)) command)]
      (println (str (name (:command result)) " -> " (name (:state result))))
      (when-let [path (:path result)]
        (println (str "path: " path)))
      (when (contains? result :cleared?)
        (println (str "cleared: " (:cleared? result))))
      (System/exit 0))))

(defn- run! [args]
  (let [opts (parse-run-args args)]
    (when-not (:config opts)
      (usage! "Usage: bb axiom.clj <config.edn> [--max-iters N] [--once]"))
    (let [cfg (-> (config/load-config (:config opts))
                  (merge (when (:once opts) {:max-iters-override 1})))
          run-opts {:max-iters (if (:once opts) 1 (:max-iters opts))
                    :config-path (:config opts)}
          res (core/run! cfg run-opts)]
      (System/exit (if (= :done (:status res)) 0 1)))))

(defn -main [& args]
  (let [command (control/parse-command (first args))]
    (cond
      (= :status command) (status! (rest args))
      (#{:pause :resume :stop} command) (control! command (rest args))
      :else (run! args))))

(apply -main *command-line-args*)
