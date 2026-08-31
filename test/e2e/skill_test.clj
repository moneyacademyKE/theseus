(ns e2e.skill-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bb-agent.skill :as skill]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- with-temp-home [f]
  (let [home (fs/create-temp-dir {:prefix "bb-agent-skill-test-"})
        old-home (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" (str home))
      (f home)
      (finally
        (if old-home
          (System/setProperty "user.home" old-home)
          (System/clearProperty "user.home"))
        (fs/delete-tree home)))))

(defn- skill-fn [& names]
  (or (some #(ns-resolve 'bb-agent.skill %) names)
      (throw (ex-info "bb-agent.skill is missing the required function"
                      {:candidates names}))))

(defn- parse-skill [source]
  ((skill-fn 'parse-skill 'parse-skill-md 'read-skill) source))

(defn- discover [root]
  ((skill-fn 'discover-skills 'discover) root))

(defn- compose [parsed input]
  ((skill-fn 'compose-prompt) parsed input))

(defn- value-for [m k]
  (or (get m k)
      (get m (name k))
      (get m (keyword (str k)))))

(deftest valid-frontmatter-is-data-and-body-is-preserved
  (testing "frontmatter is metadata, while the body is not evaluated"
    (let [body "Use this literally: (+ 1 2)\n\n$HOME and {{not-a-template}}\n"
          source (str "---\n"
                      "name: explain\n"
                      "description: Explain things\n"
                      "---\n"
                      body)
          parsed (parse-skill source)]
      (is (= "explain" (value-for parsed :name)))
      (is (= "Explain things" (value-for parsed :description)))
      (is (or (= body (value-for parsed :body))
              (= body (value-for parsed :content)))))))

(deftest malformed-frontmatter-and-invalid-declarations-are-rejected
  (doseq [source ["name: missing-delimiters\n---\nbody"
                  "---\nname: only-name\n---\nbody"
                  "---\ndescription: only-description\n---\nbody"
                  "---\nname:\n description: blank-name\n---\nbody"
                  "---\nname: bad slug!\n description: bad\n---\nbody"
                  "---\nname: good\n description:\n---\nbody"]]
    (is (thrown? Exception (parse-skill source))
        (str "must reject malformed skill: " source)))
  (testing "declared duplicate names are rejected during discovery"
    (with-temp-home
      (fn [home]
        (let [root (fs/path home "skills")
              text (fn [description]
                     (str "---\nname: same\ndescription: " description "\n---\nbody\n"))]
          (fs/create-dirs (fs/path root "a"))
          (fs/create-dirs (fs/path root "b"))
          (spit (str (fs/path root "a" "SKILL.md")) (text "one"))
          (spit (str (fs/path root "b" "SKILL.md")) (text "two"))
          (is (thrown? Exception (discover root))))))))

(deftest discovery-is-sorted
  (with-temp-home
    (fn [home]
      (let [root (fs/path home "skills")]
        (doseq [name ["zulu" "alpha" "middle"]]
          (fs/create-dirs (fs/path root name))
          (spit (str (fs/path root name "SKILL.md"))
                (str "---\nname: " name "\ndescription: " name "\n---\n" name " body\n")))
        (let [found (discover root)
              names (map #(or (value-for % :name) %)
                         (if (map? found) (vals found) found))]
          (is (= ["alpha" "middle" "zulu"] (vec names))))))))

(deftest compose-prompt-appends-optional-input-without-evaluation
  (let [body "Answer literally: (+ 1 2)"
        parsed {:name "literal"
                :description "literal"
                :body body}
        prompt (compose parsed "Input: {{value}}")]
    (is (string? prompt))
    (is (.contains prompt body))
    (is (.contains prompt "Input: {{value}}"))
    (is (not (.contains prompt "3")))))

(deftest each-test-uses-and-cleans-a-temporary-home
  (with-temp-home
    (fn [home]
      (is (= (str home) (System/getProperty "user.home")))
      (spit (str (fs/path home "sentinel")) "temporary")))
  (is true))

(deftest cli-lists-runs-and-rejects-skills
  (with-temp-home
    (fn [home]
      (let [skill-dir (fs/path home "skills" "echo")
            session-file (fs/path home "state" "sessions" "skill-echo.edn")]
        (fs/create-dirs skill-dir)
        (spit (str (fs/path skill-dir "SKILL.md"))
              "---\nname: echo\ndescription: Echo the supplied input\n---\nSkill body\n")
        (spit (str (fs/path home "config.edn"))
              (pr-str {:provider :fake :model "fake-deterministic"}))
        (let [listed (shell! home "skill" "list")
              ran (shell! home "skill" "run" "echo" "hello" "there")
              unknown (shell! home "skill" "run" "missing")]
          (is (= 0 (:exit listed)) (:err listed))
          (is (= "echo - Echo the supplied input\n" (:out listed)))
          (is (= 0 (:exit ran)) (:err ran))
          (is (str/includes? (:out ran) "Skill body"))
          (is (fs/regular-file? session-file))
          (let [turn (first (edn/read-string (slurp (str session-file))))]
            (is (= "skill-echo" (:session/id turn)))
            (is (= "Skill body\n\n\nUser input:\nhello there" (:user/input turn))))
          (is (not= 0 (:exit unknown)))
          (is (str/includes? (:err unknown) "Unknown skill: missing")))))))
