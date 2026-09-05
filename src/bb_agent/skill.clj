(ns bb-agent.skill
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private slug-pattern #"^[a-z0-9][a-z0-9_-]*$")

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
          description (:description fields)]
      (when (or (str/blank? name) (str/blank? description))
        (throw (ex-info "Skill name and description are required" {})))
      (when-not (re-matches slug-pattern name)
        (throw (ex-info "Invalid skill name" {:name name})))
      {:name name :description description :body body})))

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
