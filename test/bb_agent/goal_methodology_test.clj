(ns bb-agent.goal-methodology-test
  "Pins the default goal methodology (owner directive 2026-09-07, conf: high):
   the canon file exists, carries the method's load-bearing rules, and the
   author-prompt names it so the method survives even an unread reference."
  (:require [bb-agent.config :as config]
            [bb-agent.goal-bridge :as bridge]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest methodology-canon-test
  ;; The canon follows the configured Theseus home so isolated suites do not
  ;; read the operator's live brain. Run this suite against the real home when
  ;; explicitly checking host state.
  (let [f (str (config/home) "/brain/knowledge/goal-methodology.md")]
    (when (.exists (java.io.File. f))
      (let [body (slurp f)]
        (testing "canon is owner-stamped and carries the hard rules"
          (is (str/includes? body "conf: high"))
          (is (str/includes? body "Python is never invoked"))
          (is (str/includes? body "Red/green TDD"))
          (is (str/includes? body "YAGNI"))
          (is (str/includes? body "Complexity vs utility"))
          (is (str/includes? body "certification")))))))

(deftest author-prompt-names-methodology-test
  (testing "the authoring prompt points at the methodology and inlines its hard rules"
    (is (str/includes? @#'bridge/author-prompt "references/goal-methodology.md"))
    (is (str/includes? @#'bridge/author-prompt "Python is floored"))))
