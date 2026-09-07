(ns e2e.telegram-bot-commands-test
  "Command-menu (setMyCommands) builder tests — pure payload logic, no network."
  (:require [bb-agent.bot-commands :as bot-commands]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest telegram-command-name-converts-to-menu-valid
  (is (= "deep_research" (bot-commands/telegram-command-name "deep-research")))
  (is (= "code_review_swarm" (bot-commands/telegram-command-name "code-review-swarm")))
  (is (= "skills" (bot-commands/telegram-command-name "skills")))
  (is (= 32 (count (bot-commands/telegram-command-name
                    (apply str (repeat 60 "x")))))
      "names truncate at the Telegram 32-char cap"))

(deftest build-commands-native-first-then-skills
  (let [cmds (bot-commands/build-commands [{:name "deep-research" :description "Multi-source research"}
                                           {:name "zz-last" :description "Alphabetical tail"}])]
    (is (= "new" (:command (first cmds))) "native commands lead the menu")
    (is (= {:command "deep_research" :description "Multi-source research"}
           (nth cmds (count bot-commands/native-commands)))
        "skills follow, with converted names")))

(deftest build-commands-dedupes-and-protects-native
  (let [cmds (bot-commands/build-commands [{:name "goal" :description "A skill trying to shadow native /goal"}
                                           {:name "foo-bar" :description "hyphen"}
                                           {:name "foo_bar" :description "underscore twin"}])
        by-name (group-by :command cmds)]
    (is (= 1 (count (get by-name "goal")))
        "a skill cannot shadow or duplicate a native command")
    (is (= "Supervised goal build: /goal <what to build>"
           (:description (first (get by-name "goal"))))
        "the native entry keeps its own description")
    (is (= 1 (count (get by-name "foo_bar")))
        "hyphenated and underscored twins collapse to one menu entry")
    (is (= "hyphen" (:description (first (get by-name "foo_bar"))))
        "first occurrence wins the dedupe")))

(deftest build-commands-fallbacks-truncation-and-cap
  (let [blank-desc (bot-commands/build-commands [{:name "nodesc" :description ""}])
        long-desc (bot-commands/build-commands [{:name "wordy" :description (apply str (repeat 500 "d"))}])
        many (bot-commands/build-commands (mapv (fn [i] {:name (str "skill" i) :description "d"})
                                                (range 200)))]
    (is (= "Skill: nodesc"
           (:description (nth blank-desc (count bot-commands/native-commands))))
        "blank description falls back to a named placeholder")
    (is (= 256 (count (:description (nth long-desc (count bot-commands/native-commands)))))
        "descriptions truncate at the Telegram 256-char cap")
    (is (= 100 (count many)) "the menu caps at Telegram's 100-command limit")))

(deftest resolve-skill-exact-then-underscore-fallback
  (let [skills [{:name "deep-research" :description "d" :body "B"}
                {:name "plain" :description "d" :body "P"}]]
    (is (= "plain" (:name (bot-commands/resolve-skill skills "plain")))
        "exact match wins")
    (is (= "deep-research" (:name (bot-commands/resolve-skill skills "deep_research")))
        "menu-spelled underscores resolve to the hyphenated skill")
    (is (nil? (bot-commands/resolve-skill skills "nope"))
        "unknown commands resolve to nil")))

(deftest resolve-skill-exact-wins-over-fallback
  (let [skills [{:name "foo_bar" :description "literal" :body "L"}
                {:name "foo-bar" :description "hyphen" :body "H"}]]
    (is (= "foo_bar" (:name (bot-commands/resolve-skill skills "foo_bar")))
        "an exact underscore-named skill beats the hyphen fallback")))

(deftest build-commands-fits-total-description-budget
  (let [skills (mapv (fn [i] {:name (str "wordy" i) :description (apply str (repeat 200 "d"))})
                     (range 60))
        cmds (bot-commands/build-commands skills)
        total (reduce + 0 (map (comp count :description) cmds))
        native-total (reduce + 0 (map (comp count second) bot-commands/native-commands))]
    (is (<= total 5000) "combined descriptions fit the empirical 5000-char budget")
    (is (= native-total (reduce + 0 (map (comp count :description)
                                         (take (count bot-commands/native-commands) cmds))))
        "native descriptions are never truncated")
    (is (some #(clojure.string/ends-with? (:description %) "…") cmds)
        "over-budget skill descriptions truncate with an ellipsis marker")))
