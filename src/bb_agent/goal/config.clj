(ns bb-agent.goal.config
  "EDN config loading and schema validation.
  Validates required keys, checks well-formedness of goals/integrity checks,
  handles bundle expansions, and facilitates config hot-reloading."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [bb-agent.goal.harness :as harness]
            [bb-agent.goal.predicates :as predicates]))

;; ---------- Config loading ----------

(def ^:private required-keys [:name :observers :goal :act])

(def ^:private path-keys
  "Config values naming filesystem paths. A relative value resolves against
  the directory containing the config file, so a config means the same thing
  from any invoking cwd (2026-09-06 W2)."
  [:workdir :lock :log-dir])

(defn anchor-paths
  "Resolve relative values of `path-keys` in `cfg` against the parent dir of
  `cfg-file`. Absolute values and absent keys pass through untouched. Pure."
  [cfg cfg-file]
  (let [base (-> cfg-file .getAbsoluteFile .getParentFile .getAbsolutePath)]
    (reduce (fn [cfg k]
              (let [v (get cfg k)
                    f (some-> v io/file)]
                (if (and f (not (.isAbsolute f)))
                  (assoc cfg k (str (io/file base v)))
                  cfg)))
            cfg
            path-keys)))

(defn- harness-model-errors
  [cfg]
  (let [acts (cond
               (map? (:act cfg)) [(:act cfg)]
               (vector? (:act cfg)) (:act cfg)
               :else [])]
    (->> acts
         (map-indexed (fn [i act]
                        (when (:harness act)
                          (try
                            (harness/build-invocation cfg {} {:attempt 0 :model-index 0} act)
                            nil
                            (catch clojure.lang.ExceptionInfo ex
                              (let [{:keys [validator]} (ex-data ex)]
                                (when validator
                                  {:key :models
                                   :problem (str "act[" i "] " (.getMessage ex))})))))))
         (remove nil?)
         vec)))

;; ---------- Integrity bundles (Phase 1) ----------
;; Pre-built integrity check sets, keyed by project type.

(def integrity-bundles
  "Pre-built integrity check sets, keyed by project type. Each bundle
  asserts the *baseline* that a build of that toolchain must preserve."
  {:gleam-build [{:op := :ref :build-ok :value "pass"}]
   :rust-build  [{:op := :ref :build-ok :value "pass"}
                 {:op := :ref :tests-ok  :value "pass"}]
   :clj-build   [{:op := :ref :build-ok :value "pass"}
                 {:op := :ref :tests-ok  :value "pass"}]
   :node-build  [{:op := :ref :build-ok :value "pass"}
                 {:op := :ref :tests-ok  :value "pass"}]
   :python-build [{:op := :ref :build-ok :value "pass"}
                  {:op := :ref :tests-ok  :value "pass"}]
   :go-build    [{:op := :ref :build-ok :value "pass"}
                 {:op := :ref :tests-ok  :value "pass"}]
   :clean-tree  [{:op := :ref :git-clean :value "true"}]})

(defn expand-bundles
  "Compose :integrity-bundle predicates with explicit :integrity checks."
  [cfg]
  (let [bundle-name (:integrity-bundle cfg)
        names       (cond
                      (nil? bundle-name)        nil
                      (keyword? bundle-name)   [bundle-name]
                      (coll? bundle-name)       (vec bundle-name)
                      :else                     (throw
                                                 (ex-info (str ":integrity-bundle must be a keyword or vector of keywords; got "
                                                               (pr-str bundle-name))
                                                          {:integrity-bundle bundle-name})))
        bundles     (when names
                      (mapv (fn [n]
                              (or (integrity-bundles n)
                                  (throw (ex-info (str "Unknown integrity bundle: " n)
                                                  {:bundle n
                                                   :known (keys integrity-bundles)}))))
                            names))
        explicit    (:integrity cfg [])]
    (cond-> []
      (seq bundles) (into (mapcat identity bundles))
      :always       (into explicit))))

(defn- host-repo-workdir?
  "True when `workdir` resolves to the repo this runner executes from (the
  directory containing the bb.edn that launched `bb goal`). Rollback is a
  hard `git reset --hard`, so a host-repo workspace would destroy
  uncommitted work on halt (2026-09-04 stress test)."
  [workdir]
  (let [bb-edn (io/file "bb.edn")]
    (and (.exists bb-edn)
         (= (.getCanonicalPath (io/file workdir))
            (.getCanonicalPath (.getParentFile (.getAbsoluteFile bb-edn)))))))

(defn validate-config
  "Pure: returns a vector of validation error maps for `cfg`, empty when valid."
  [cfg]
  (let [act (:act cfg)
        act-errors
        (cond
          (nil? act)              [{:key :act :problem "missing"}]
          (map? act)              (if (:harness act)
                                    (when-not (:prompt act)
                                      [{:key :act :problem "harness act missing :prompt"}])
                                    (when-not (:sh act)
                                      [{:key :act :problem "act map missing :sh"}]))
          (vector? act)           (vec (keep (fn [[i a]]
                                               (cond
                                                 (:harness a)
                                                 (when-not (:prompt a)
                                                   {:key :act
                                                    :problem (str "act[" i "] harness missing :prompt")})
                                                 (not (:sh a))
                                                 {:key :act
                                                  :problem (str "act[" i "] missing :sh")}))
                                             (map-indexed vector act)))
          :else                   [{:key :act
                                     :problem "act must be a map or vector of maps"}])
        integrity-errors
        (vec (keep (fn [[i c]]
                     (when-not (predicates/valid? c)
                       {:key :integrity
                        :problem (str "check[" i "] not a well-formed predicate")}))
                   (map-indexed vector (:integrity cfg []))))]
    (cond-> []
      (not (predicates/valid? (:goal cfg)))
      (conj {:key :goal :problem "not a well-formed predicate"})

      (not (contains? cfg :progress))
      (conj {:key :progress :problem "missing progress signature"})

      :always (into act-errors)
      :always (into integrity-errors)
      :always (into (harness-model-errors cfg)))))

(defn load-config
  "Read and parse an EDN config file. Validates required keys + structural validity."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Config not found: " path) {:path path})))
    (let [cfg (anchor-paths (edn/read-string (slurp f)) f)]
      (doseq [k required-keys]
        (when-not (contains? cfg k)
          (throw (ex-info (str "Config missing required key: " k)
                          {:key k :present (keys cfg)}))))
      (when (host-repo-workdir? (get cfg :workdir "."))
        (throw (ex-info "Refusing :workdir that resolves to the host repo — rollback hard-resets it; point :workdir at a dedicated clone or worktree"
                        {:workdir (get cfg :workdir ".")})))
      (let [cfg    (assoc cfg :integrity (expand-bundles cfg))
            errors (validate-config cfg)]
        (when (seq errors)
          (throw (ex-info "Config validation failed" {:errors errors})))
        cfg))))

;; ---------- Hot-reload (Phase 4b) ----------

(defn file-mtime
  "Last-modified millis of the file at `path`, or nil when it does not exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (.lastModified f))))

(defn maybe-reload
  "Hot-reload gate (Phase 4b)."
  [cfg path last-mtime]
  (let [now (file-mtime path)]
    (if (= now last-mtime)
      [cfg nil]
      (let [new-cfg (load-config path)]
        [new-cfg now]))))
