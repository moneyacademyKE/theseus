(ns axiom.harness
  "External harness invocation for agent CLIs such as opencode.

  Axiom supervises; harnesses act. Their output is advisory only -- the core
  loop still re-observes the world and decides from filesystem/build state."
  (:require [babashka.process :refer [sh]]
            [clojure.string :as str]))

(defn render-template
  "Replace {{key}} placeholders with context values. Missing keys render as
  the empty string so optional model/prompt fields do not leak template text."
  [tmpl ctx]
  (reduce (fn [s [k v]]
            (str/replace s (str "{{" (name k) "}}") (str (or v ""))))
          (str tmpl)
          ctx))

(defn- render-args
  [args ctx]
  (mapv #(render-template % ctx) (or args [])))

(def default-harnesses
  "Built-in harness profiles. They are plain argv templates, never shell
  strings. Config can override or extend them via :harnesses."
  {:opencode {:cmd "opencode"
              :args ["run" "--model" "{{model}}" "{{prompt}}"]}
   :shell-argv {:cmd "bash"
                :args ["-lc" "{{prompt}}"]}})

(defn build-invocation
  "Pure: turn cfg/world/state/act into a concrete process invocation.

  cfg may define :harnesses to override command/args per harness. Returns a
  map with :cmd, :args, :argv, :dir, :timeout, and metadata for logs/tests."
  [cfg world state act]
  (let [hname    (:harness act)
        profiles (merge default-harnesses (:harnesses cfg))
        profile  (get profiles hname)]
    (when-not profile
      (throw (ex-info (str "Unknown harness: " hname)
                      {:harness hname :known (keys profiles)})))
    (let [models (:models cfg)
          midx   (or (:model-index state 0) 0)
          model  (or (:model act)
                     (when (seq models)
                       (nth models (min midx (dec (count models))) "")))
          ctx    (merge world
                        {:attempt (:attempt state 0)
                         :model (or model "")
                         :prompt (render-template (:prompt act "")
                                                  (assoc world
                                                         :attempt (:attempt state 0)
                                                         :model (or model "")))})
          cmd    (render-template (:cmd profile) ctx)
          args   (render-args (or (:args act) (:args profile)) ctx)]
      {:harness hname
       :cmd cmd
       :args args
       :argv (into [cmd] args)
       :dir (:workdir cfg ".")
       :timeout (:timeout act (:timeout profile 120000))
       :prompt (:prompt ctx)
       :model model})))

(defn invoke!
  "Invoke a harness. If cfg supplies :harness-transport, call that function
  with the invocation map; tests use this to avoid requiring opencode. The
  default path uses process argv directly -- no shell interpolation."
  [cfg invocation]
  (if-let [transport (:harness-transport cfg)]
    (transport invocation)
    (apply sh {:out :string :err :string
               :dir (:dir invocation)
               :continue true
               :timeout (:timeout invocation)}
           (:argv invocation))))