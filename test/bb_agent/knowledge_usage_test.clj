(ns bb-agent.knowledge-usage-test
  (:require [babashka.fs :as fs]
            [bb-agent.brain-hints :as bh]
            [bb-agent.config :as config]
            [bb-agent.knowledge-usage :as ku]
            [clojure.test :refer [deftest is]]))

(deftest knowledge-usage-records-search-hits-and-reads-test
  (let [home (fs/create-temp-dir {:prefix "theseus-ku-"})
        kdir (fs/path home "brain" "knowledge")]
    (try
      (fs/create-dirs kdir)
      (spit (str (fs/path kdir "repl-driven-development.md"))
            "---\nOwns: babashka shell arity\n---\nAlways use varargs with babashka.process/shell.\n")
      (with-redefs [config/home (constantly (str home))]
        (ku/record! {:page "repl-driven-development.md" :action :read :surface :chat})
        (ku/record! {:page "repl-driven-development.md" :action :cited :surface :chat})
        ;; brain-hints automatically records :search-hit when returning snippets
        (is (string? (bh/hints-for "babashka.process shell arity varargs")))
        (let [agg (get (ku/aggregate) "repl-driven-development.md")]
          (is (= 1 (:read agg)))
          (is (= 1 (:cited agg)))
          (is (>= (:search-hit agg) 1))
          (is (>= (:total agg) 3))))
      (finally
        (fs/delete-tree home)))))
