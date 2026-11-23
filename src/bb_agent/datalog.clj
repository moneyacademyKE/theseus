(ns bb-agent.datalog
  "Zero-dependency, Babashka-native in-memory EAV Datalog engine.
   Hydrates immutable DB values from Theseus's EDN truth on disk (cached by
   mtime) — zero daemon, zero native pod, zero IPC overhead, and pure Clojure
   predicates inside :where clauses.

   (Empirical note: datascript/datascript 1.8.1 fails under Babashka SCI due to
   `deftype` in datascript/lru.cljc; this pure-map engine runs natively in bb.)"
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------- Immutable EAV / AEV / AVE store ----------

(defn empty-db
  ([] (empty-db {}))
  ([schema]
   {:schema (or schema {})
    :eav {}
    :aev {}
    :ave {}
    :next-eid 1}))

(defn- unique-attr? [schema a]
  (= :db.unique/identity (get-in schema [a :db/unique])))

(defn- ref-attr? [schema a]
  (= :db.type/ref (get-in schema [a :db/valueType])))

(defn- many-attr? [schema a]
  (= :db.cardinality/many (get-in schema [a :db/cardinality])))

(defn- lookup-ref? [x]
  (and (vector? x) (= 2 (count x)) (keyword? (first x))))

(defn resolve-eid [db ref-or-eid]
  (cond
    (integer? ref-or-eid) ref-or-eid
    (lookup-ref? ref-or-eid)
    (let [[a v] ref-or-eid]
      (first (get-in db [:ave a v])))
    :else nil))

(defn- retract-datom [db e a v]
  (-> db
      (update-in [:eav e a] disj v)
      (update-in [:aev a e] disj v)
      (update-in [:ave a v] disj e)))

(defn- add-datom [db e a v]
  (let [db' (if (many-attr? (:schema db) a)
              db
              (reduce (fn [d old-v]
                        (if (= old-v v) d (retract-datom d e a old-v)))
                      db
                      (get-in db [:eav e a])))]
    (-> db'
        (update-in [:eav e a] (fnil conj #{}) v)
        (update-in [:aev a e] (fnil conj #{}) v)
        (update-in [:ave a v] (fnil conj #{}) e))))

(defn- resolve-or-alloc-eid [db m]
  (let [schema (:schema db)
        existing (or (when-let [id (:db/id m)] (resolve-eid db id))
                     (some (fn [[a v]]
                             (when (unique-attr? schema a)
                               (first (get-in db [:ave a v]))))
                           m))]
    (if existing
      [db existing]
      (let [eid (:next-eid db)]
        [(assoc db :next-eid (inc eid)) eid]))))

(defn- transact-map [db m]
  (let [[db1 eid] (resolve-or-alloc-eid db m)
        schema (:schema db1)]
    (reduce-kv
     (fn [acc a v]
       (if (or (= a :db/id) (nil? v))
         acc
         (let [vals (if (and (or (set? v) (sequential? v))
                             (not (lookup-ref? v)))
                      v
                      [v])]
           (reduce
            (fn [d raw-v]
              (if (ref-attr? schema a)
                (if-let [target (resolve-eid d raw-v)]
                  (add-datom d eid a target)
                  (if (lookup-ref? raw-v)
                    (let [[la lv] raw-v
                          [d2 target-eid] (resolve-or-alloc-eid d {la lv})]
                      (-> d2
                          (add-datom target-eid la lv)
                          (add-datom eid a target-eid)))
                    (add-datom d eid a raw-v)))
                (add-datom d eid a raw-v)))
            acc
            vals))))
     db1
     m)))

(defn db-with
  "Return a new immutable DB value with `tx-data` (maps or [:db/add|:db/retract e a v]) applied."
  [db tx-data]
  (reduce
   (fn [acc item]
     (cond
       (map? item) (transact-map acc item)
       (and (vector? item) (= :db/add (first item)))
       (let [[_ e a v] item]
         (if-let [eid (resolve-eid acc e)]
           (add-datom acc eid a v)
           acc))
       (and (vector? item) (= :db/retract (first item)))
       (let [[_ e a v] item]
         (if-let [eid (resolve-eid acc e)]
           (retract-datom acc eid a v)
           acc))
       :else acc))
   db
   tx-data))

;; ---------- Datalog query engine ----------

(defn- qvar? [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- wildcard? [x]
  (= '_ x))

(defn- parse-query [query-vec]
  (loop [xs (seq query-vec) section nil acc {:find [] :in [] :where []}]
    (if-let [x (first xs)]
      (if (#{:find :in :where} x)
        (recur (next xs) x acc)
        (recur (next xs) section (update acc section conj x)))
      acc)))

(defn- match-pattern [db env [ep ap vp]]
  (let [e-val (if (qvar? ep) (get env ep ::unbound) (if (wildcard? ep) ::any ep))
        a-val (if (qvar? ap) (get env ap ::unbound) (if (wildcard? ap) ::any ap))
        v-val (if (qvar? vp) (get env vp ::unbound) (if (wildcard? vp) ::any vp))
        candidates
        (cond
          (and (not (#{::unbound ::any} e-val))
               (not (#{::unbound ::any} a-val)))
          (for [v (get-in db [:eav e-val a-val])] [e-val a-val v])

          (and (not (#{::unbound ::any} a-val))
               (not (#{::unbound ::any} v-val)))
          (for [e (get-in db [:ave a-val v-val])] [e a-val v-val])

          (not (#{::unbound ::any} a-val))
          (for [[e vs] (get-in db [:aev a-val])
                v vs]
            [e a-val v])

          (not (#{::unbound ::any} e-val))
          (for [[a vs] (get-in db [:eav e-val])
                v vs]
            [e-val a v])

          :else
          (for [[e am] (:eav db)
                [a vs] am
                v vs]
            [e a v]))]
    (keep (fn [[e a v]]
            (when (and (or (#{::unbound ::any} e-val) (= e e-val))
                       (or (#{::unbound ::any} a-val) (= a a-val))
                       (or (#{::unbound ::any} v-val) (= v v-val)))
              (cond-> env
                (qvar? ep) (assoc ep e)
                (qvar? ap) (assoc ap a)
                (qvar? vp) (assoc vp v))))
          candidates)))

(def ^:private pred-fns
  "Strict pure-function allowlist for :where predicates. Arbitrary symbol
   resolution is intentionally disallowed so untrusted queries cannot execute I/O."
  {'= = 'not= not= '< < '<= <= '> > '>= >=
   'str/includes? str/includes?
   'str/starts-with? str/starts-with?
   'str/ends-with? str/ends-with?
   'str/lower-case str/lower-case
   'str/upper-case str/upper-case
   're-find (fn [pat s] (when (and (string? pat) (string? s)) (re-find (re-pattern pat) s)))
   'count count 'empty? empty? 'seq seq
   'contains? contains?
   'some? some? 'nil? nil? 'pos? pos? 'zero? zero? 'neg? neg?
   'even? even? 'odd? odd? 'inc inc 'dec dec '+ + '- - '* *})

(defn- eval-pred [expr env]
  (let [[op & args] expr
        f (get pred-fns op)
        resolved (mapv #(if (qvar? %) (get env %) %) args)]
    (when (fn? f)
      (try
        (apply f resolved)
        (catch Exception _ nil)))))

(defn- eval-clause [db envs clause]
  (cond
    ;; Negation: (not [?e :a :v] ...)
    (and (list? clause) (= 'not (first clause)))
    (let [sub-clauses (rest clause)]
      (filterv (fn [env]
                 (empty? (reduce (fn [acc c] (eval-clause db acc c)) [env] sub-clauses)))
               envs))

    ;; Predicate / binding: [(>= ?ev ?min)] or [(f ?x) ?out]
    (and (vector? clause) (list? (first clause)))
    (let [[expr out-var] clause]
      (keep (fn [env]
              (let [res (eval-pred expr env)]
                (if out-var
                  (when (some? res) (assoc env out-var res))
                  (when res env))))
            envs))

    ;; Data pattern: [?e :a ?v]
    (vector? clause)
    (vec (mapcat #(match-pattern db % clause) envs))

    :else envs))

(defn- project-find-term [term envs]
  (if (list? term)
    (let [[agg var-sym] term
          vals (map #(get % var-sym) envs)]
      (case agg
        count (count (distinct vals))
        sum (reduce + 0 vals)
        min (when (seq vals) (apply min vals))
        max (when (seq vals) (apply max vals))
        nil))
    (map #(get % term) envs)))

(defn q
  "Run a Datalog query against `db` (plus optional :in inputs)."
  [query-vec db & inputs]
  (let [{:keys [find in where]} (parse-query query-vec)
        in-vars (if (seq in) in ['$])
        all-vals (cons db inputs)
        init-env (reduce (fn [m [sym val]]
                           (if (qvar? sym) (assoc m sym val) m))
                         {}
                         (map vector in-vars all-vals))
        envs (reduce (fn [acc c] (eval-clause db acc c)) [init-env] where)]
    (cond
      ;; Scalar: [:find ?x .] or [:find (count ?e) .]
      (and (= 2 (count find)) (= '. (second find)))
      (let [term (first find)]
        (if (list? term)
          (project-find-term term envs)
          (first (project-find-term term envs))))

      ;; Collection: [:find [?x ...]]
      (and (= 1 (count find)) (vector? (first find)) (= '... (second (first find))))
      (vec (distinct (map #(get % (ffirst find)) envs)))

      ;; Relation: [:find ?a ?b ...]
      :else
      (if (some list? find)
        #{(mapv #(if (list? %) (project-find-term % envs) (first (project-find-term % envs))) find)}
        (set (map (fn [env] (mapv #(get env %) find)) envs))))))

;; ---------- Pull API ----------

(defn- pull-attr-val [schema a vs]
  (if (or (many-attr? schema a) (> (count vs) 1))
    (vec vs)
    (first vs)))

(defn pull
  "Pull attributes and nested ref maps for `eid-or-ref` from `db`."
  [db pattern eid-or-ref]
  (when-let [eid (resolve-eid db eid-or-ref)]
    (let [entity-map (get-in db [:eav eid] {})
          schema (:schema db)]
      (reduce
       (fn [acc spec]
         (cond
           (= :* spec)
           (reduce-kv (fn [m a vs]
                        (if (seq vs)
                          (assoc m a (pull-attr-val schema a vs))
                          m))
                      acc
                      entity-map)

           (keyword? spec)
           (if-let [vs (seq (get entity-map spec))]
             (assoc acc spec (pull-attr-val schema spec vs))
             acc)

           (map? spec)
           (reduce-kv
            (fn [m ref-attr sub-pat]
              (if-let [vs (seq (get entity-map ref-attr))]
                (let [pulled (mapv #(pull db sub-pat %) vs)]
                  (assoc m ref-attr (if (and (= 1 (count pulled)) (not (many-attr? schema ref-attr)))
                                      (first pulled)
                                      pulled)))
                m))
            acc
            spec)

           :else acc))
       {}
       pattern))))

;; ---------- Zero-daemon mtime-cached Theseus DB hydrator ----------

(def theseus-schema
  {:memory/id {:db/unique :db.unique/identity}
   :memory/supersedes {:db/valueType :db.type/ref}
   :session/id {:db/unique :db.unique/identity}
   :goal/slug {:db/unique :db.unique/identity}})

(def theseus-attributes
  "Advertised attributes hydrated into `(theseus-db)` for agent introspection."
  {:memory [:memory/id :memory/text :memory/kind :memory/status :memory/evidence-count :memory/supersedes]
   :session [:session/id :session/summary :session/updated]
   :goal [:goal/slug :goal/name :goal/status]})

(def ^:private cache (atom {:sig nil :db nil}))

(defn- file-mtime [p]
  (if (fs/regular-file? p)
    (.toMillis (fs/last-modified-time p))
    0))

(defn- goals-signature [goals-dir]
  (if (fs/directory? goals-dir)
    (->> (fs/list-dir goals-dir)
         (filter fs/directory?)
         (map (fn [d] [(fs/file-name d) (file-mtime (fs/path d "project.edn"))]))
         (sort-by first)
         vec)
    []))

(defn hydrate-home!
  "Build a fresh immutable Datalog DB from Theseus's EDN files on disk."
  []
  (let [home (config/home)
        mem-path (fs/path home "state" "memory.edn")
        sum-path (fs/path home "state" "session-summaries.edn")
        goals-dir (fs/path home "goals")
        memories (let [raw (aio/read-edn-safe mem-path [])]
                   (if (sequential? raw) raw []))
        mem-tx (mapv (fn [m]
                       (cond-> (select-keys m [:memory/id :memory/text :memory/kind
                                               :memory/status :memory/evidence-count])
                         (:memory/supersedes m)
                         (assoc :memory/supersedes [:memory/id (:memory/supersedes m)])))
                     (filter :memory/id memories))
        summaries (let [raw (aio/read-edn-safe sum-path {})]
                    (if (map? raw) raw {}))
        sess-tx (mapv (fn [[sid s]]
                        {:session/id (str (or (:session/id s) sid))
                         :session/summary (str (or (:summary s) ""))
                         :session/updated (or (:updated/at s) 0)})
                      summaries)
        goal-tx (if (fs/directory? goals-dir)
                  (->> (fs/list-dir goals-dir)
                       (filter fs/directory?)
                       (keep (fn [d]
                               (let [proj (fs/path d "project.edn")]
                                 (when (fs/regular-file? proj)
                                   (let [p (aio/read-edn-safe proj {})]
                                     {:goal/slug (fs/file-name d)
                                      :goal/name (str (or (:name p) (fs/file-name d)))
                                      :goal/status (or (:status p) :unknown)})))))
                       vec)
                  [])]
    (-> (empty-db theseus-schema)
        (db-with mem-tx)
        (db-with sess-tx)
        (db-with goal-tx))))

(defn theseus-db
  "Return the mtime-cached immutable Datalog DB for `(config/home)`.
   Zero daemon, zero pod, automatically refreshes when any EDN file changes."
  []
  (let [home (str (config/home))
        sig [home
             (file-mtime (fs/path home "state" "memory.edn"))
             (file-mtime (fs/path home "state" "session-summaries.edn"))
             (goals-signature (fs/path home "goals"))]
        cur @cache]
    (if (= sig (:sig cur))
      (:db cur)
      (let [db (hydrate-home!)]
        (reset! cache {:sig sig :db db})
        db))))

;; ---------- Agent tool handler (`query_graph`) ----------

(def ^:private default-limit 50)
(def ^:private max-limit 200)

(defn- bound-result [res limit]
  (cond
    (set? res) (set (take limit res))
    (vector? res) (vec (take limit res))
    :else res))

(defn run-query-tool!
  "Read-only tool handler for `query_graph`. Accepts either:
   - {:query \"schema\"} to inspect hydrated schema and attributes
   - {:query \"[:find ... :where ...]\"} for Datalog queries
   - {:pull \"[...]\" :ref \"[:attr val]\"} for entity/ref pulls"
  [args]
  (let [q-str (:query args)
        pull-str (:pull args)
        ref-str (:ref args)
        limit (let [n (or (:limit args) default-limit)]
                (min max-limit (max 1 (if (integer? n) n default-limit))))]
    (try
      (cond
        (= "schema" (some-> q-str str/trim str/lower-case))
        (common/ok-result "query_graph"
                          {:schema theseus-schema
                           :attributes theseus-attributes
                           :predicates (vec (sort (keys pred-fns)))})

        (and (string? pull-str) (not (str/blank? pull-str)))
        (if (or (nil? ref-str) (str/blank? (str ref-str)))
          (common/error-result "query_graph" "pull requires :ref (e.g. \"[:memory/id \\\"m1\\\"]\")" {})
          (let [pat (edn/read-string pull-str)
                ref (edn/read-string (str ref-str))
                res (pull (theseus-db) pat ref)]
            (common/ok-result "query_graph" {:result res})))

        (and (string? q-str) (not (str/blank? q-str)))
        (let [q-vec (edn/read-string q-str)]
          (if-not (vector? q-vec)
            (common/error-result "query_graph" "query must be an EDN vector starting with :find" {})
            (let [raw (q q-vec (theseus-db))
                  bounded (bound-result raw limit)]
              (common/ok-result "query_graph"
                                {:result bounded
                                 :count (if (coll? bounded) (count bounded) 1)
                                 :truncated? (and (coll? raw) (> (count raw) limit))}))))

        :else
        (common/error-result "query_graph"
                             "provide :query (EDN Datalog vector or \"schema\") or :pull + :ref"
                             {}))
      (catch Exception e
        (common/error-result "query_graph"
                             (str "Invalid Datalog query/pull EDN: " (ex-message e))
                             {})))))

(def handlers
  {"query_graph" run-query-tool!})
