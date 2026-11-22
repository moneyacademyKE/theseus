(ns bb-agent.skill
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private slug-pattern #"^[a-z0-9][a-z0-9_-]*$")

(defn- parse-globs [raw]
  (when-not (str/blank? (str (or raw "")))
    (->> (str/split (str raw) #",")
         (map str/trim)
         (remove str/blank?)
         (mapcat (fn [g]
                   (if (str/includes? g "/")
                     [g]
                     [g (str "**/" g)])))
         distinct
         vec)))

(defn parse-skill [raw]
  (let [match (re-find #"(?sm)\A---\r?\n(.*?)^---\r?(?:\n|\z)(.*)\z" raw)]
    (when-not match
      (throw (ex-info "SKILL.md must have opening and closing --- fences" {})))
    (let [frontmatter (second match)
          body (nth match 2)
          fields (reduce
                  (fn [result line]
                    ;; Tolerate YAML list items, nested keys, and continuations:
                    ;; only flat `key: value` lines at column 0 become fields.
                    (if-let [[_ k v] (re-matches #"([A-Za-z0-9_-]+)\s*:\s*(.*)" line)]
                      (assoc result (keyword (str/trim k)) (str/trim v))
                      result))
                  {}
                  (str/split-lines frontmatter))
          name (:name fields)
          description (:description fields)
          globs (parse-globs (:globs fields))]
      (when (or (str/blank? name) (str/blank? description))
        (throw (ex-info "Skill name and description are required" {})))
      (when-not (re-matches slug-pattern name)
        (throw (ex-info "Invalid skill name" {:name name})))
      (cond-> {:name name :description description :body body}
        (seq globs) (assoc :globs globs)))))

(defn- glob->regex [glob]
  (let [escaped (-> (str glob)
                    (str/replace "\\" "/")
                    (str/replace #"([.+^$(){}|\[\]])" "\\\\$1")
                    (str/replace "**/" "\u0001")
                    (str/replace "**" "\u0002")
                    (str/replace "*" "[^/]*")
                    (str/replace "?" "[^/]")
                    (str/replace "\u0001" "(?:.*/)?")
                    (str/replace "\u0002" ".*"))]
    (re-pattern (str "^" escaped "$"))))

(defn path-matches-glob?
  "True when `path` matches `glob` (normalized with forward slashes)."
  [path glob]
  (let [norm (-> (str (or path ""))
                 (str/replace "\\" "/")
                 (str/replace #"^\./" ""))]
    (boolean (re-matches (glob->regex glob) norm))))

(defn matching-skills-for-path
  "Return skills whose :globs match `path`."
  [skills path]
  (if (str/blank? (str (or path "")))
    []
    (filterv (fn [sk]
               (and (seq (:globs sk))
                    (some #(path-matches-glob? path %) (:globs sk))))
             skills)))

(defn check-skill-gate
  "Glob-triggered just-in-time skill gate: if `tool-name` mutates a file path
   matching a skill not yet in `@seen-atom`, marks the first matching skill seen
   and returns `{:skill/name ... :skill/body ...}` so the caller blocks once,
   injects the skill body, and allows the immediate retry."
  [skills seen-atom tool-name path]
  (when (and (contains? #{"write_file" "edit_file"} tool-name)
             (seq skills)
             (some? seen-atom)
             (not (str/blank? (str (or path "")))))
    (let [seen @seen-atom
          unseen (->> (matching-skills-for-path skills path)
                      (remove #(contains? seen (:name %))))]
      (when-let [sk (first unseen)]
        (swap! seen-atom conj (:name sk))
        {:skill/name (:name sk)
         :skill/body (:body sk)}))))

(defn discover-skills [skills-dir]
  (if-not (fs/directory? skills-dir)
    []
    (let [skills (->> (fs/list-dir skills-dir)
                      (filter #(and (fs/directory? %)
                                    (fs/regular-file? (fs/path % "SKILL.md"))))
                      (map #(parse-skill (slurp (str (fs/path % "SKILL.md")))))
                      vec)
          names (map :name skills)]
      (when-not (= (count names) (count (set names)))
        (throw (ex-info "Duplicate skill name" {:names names})))
      (vec (sort-by :name skills)))))

(defn find-skills-dirs []
  (let [h (config/home)]
    (filterv fs/directory?
             [(fs/path h "skills")
              (fs/path h "theseus" "skills")])))

(defn discover-all-skills
  ([] (discover-all-skills (find-skills-dirs)))
  ([dirs]
   (let [dirs (if (coll? dirs) dirs [dirs])
         all (mapcat (fn [d] (try (discover-skills d) (catch Exception _ []))) dirs)]
     (->> (group-by :name all)
          (map (fn [[_ sks]] (first sks)))
          (sort-by :name)
          vec))))

(defn skills-summary
  ([] (skills-summary (find-skills-dirs)))
  ([dirs]
   (let [skills (discover-all-skills dirs)]
     (when (seq skills)
       (str "\n--- Available Skills ---\n"
            "Skills — saved workflows triggered by /<name>:\n"
            (str/join "\n" (map #(str "- /" (:name %) ": " (:description %)) skills)))))))

(defn compose-prompt [skill-or-body input]
  (let [body (if (map? skill-or-body) (:body skill-or-body) skill-or-body)]
    (if (str/blank? input)
      body
      (str body "\n\nUser input:\n" input))))

(def parse parse-skill)
(def discover discover-skills)
(def compose compose-prompt)
