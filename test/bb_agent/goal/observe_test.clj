(ns bb-agent.goal.observe-test
  "Regression pins for observer failure semantics.

  2026-09-06 incident: a shell-broken observer (:int parse of empty
  output) silently parsed to 0 -- which HAPPENS TO BE the goal value of
  a convergence project (:defects <= 0). The runner declared spurious
  victory over an untouched world. Contract per build-world docstring:
  a failed observer yields nil; predicates treat nil as unsatisfied."
  (:require [clojure.test :refer [deftest is testing]]
            [bb-agent.goal.observe :as observe]
            [bb-agent.goal.predicates :as predicates]))

(defn- probe-world
  "World for a single observer whose :sh may be broken."
  [sh parse]
  (get (observe/build-world {:probe {:sh sh :parse parse}} {:dir "/tmp"}) :probe))

(deftest failed-int-observer-yields-nil-not-zero
  (testing "empty output (command failure) parses to nil, never 0"
    (is (nil? (probe-world "exit 1" :int))))
  (testing "non-numeric output parses to nil, never 0"
    (is (nil? (probe-world "echo not-a-number" :int))))
  (testing "valid numeric output still parses"
    (is (= 7 (probe-world "echo 7" :int)))))

(deftest nil-world-never-satisfies-numeric-goal
  (testing "the fail-safe chain: nil observer -> nil world -> unsatisfied goal"
    (is (false? (predicates/goal-met? {:defects nil}
                                      {:op :<= :ref :defects :value 0})))
    (is (false? (predicates/goal-met? {:defects nil}
                                      {:op :>= :ref :defects :value 3})))
    (is (true? (predicates/goal-met? {:defects 0}
                                     {:op :<= :ref :defects :value 0})))))
