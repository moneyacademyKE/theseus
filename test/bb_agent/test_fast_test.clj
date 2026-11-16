(ns bb-agent.test-fast-test
  (:require [babashka.process :as process]
            [bb-agent.test-gate :as gate]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest fast-lane-runs-the-actual-local-gates
  (gate/with-isolated-home
   (fn [home]
     (let [{:keys [exit out err]}
           (process/sh {:continue true
                        :extra-env {"THESEUS_HOME" home "HOME" home}}
                       "bb" "test:fast")]
       (is (zero? exit) (str out "\n" err))
       (is (str/includes? (str out err) "linting took") "runs the lint gate")
       (is (str/includes? out "web module graph:") "checks the web module graph")
       (is (str/includes? out "TAP version") "runs the Node web tests")
       (is (str/includes? out "0 failures, 0 errors.") "runs focused Clojure tests")))))
