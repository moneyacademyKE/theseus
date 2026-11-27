(ns bb-agent.brain-hints-test
  (:require [babashka.fs :as fs]
            [bb-agent.brain-hints :as bh]
            [bb-agent.config :as config]
            [bb-agent.tool-hints :as hints]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest brain-hints-searches-knowledge-and-caps-output-test
  (let [home (fs/create-temp-dir {:prefix "theseus-bh-"})
        kdir (fs/path home "brain" "knowledge")]
    (try
      (fs/create-dirs kdir)
      (spit (str (fs/path kdir "repl-driven-development.md"))
            (str "---\nOwns: babashka process shell arity gotcha\n---\n"
                 "# REPL Gotchas\n\n"
                 "babashka.process/shell arity bug: passing a vector crashes with Cannot run program. "
                 "Always use varargs (apply p/shell {:opts} prog args).\n"))
      (spit (str (fs/path kdir "unrelated.md"))
            "---\nOwns: cooking recipes\n---\nBoil water and add salt.\n")

      (testing "finds relevant snippet from knowledge dir and formats compactly"
        (with-redefs [config/home (constantly (str home))]
          (let [block (bh/hints-for "shell babashka.process arity Cannot run program")]
            (is (string? block))
            (is (str/includes? block "repl-driven-development.md"))
            (is (str/includes? block "varargs"))
            (is (<= (count block) 910)))))

      (testing "returns nil when nothing matches above threshold"
        (with-redefs [config/home (constantly (str home))]
          (is (nil? (bh/hints-for "xyzzynonexistenttoken123")))))

      (testing "tool-hints/attach-next-step enriches error results with :brain/hints"
        (with-redefs [config/home (constantly (str home))]
          (let [res (hints/attach-next-step
                     {:tool/name "shell" :tool/args {:cmd "bb -e ..."}}
                     {:status :error :error/message "Cannot run program babashka.process/shell arity"})]
            (is (string? (:brain/hints res)))
            (is (str/includes? (:brain/hints res) "repl-driven-development.md")))))

      (finally
        (fs/delete-tree home)))))
