(ns bb-agent.test-gate
  "Run integration gates with an isolated Theseus data home."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]))

(defn with-isolated-home
  "Call f with a fresh temporary home and remove it even if f fails.
  The caller must pass the home to child processes as THESEUS_HOME."
  [f]
  (let [home (fs/create-temp-dir {:prefix "theseus-test-home-"})]
    (try
      (f (str home))
      (finally (fs/delete-tree home)))))

(defn run-isolated!
  "Run a command with a disposable Theseus home and return its exit status."
  [command]
  (with-isolated-home
    (fn [home]
      (apply process/shell
             {:extra-env {"THESEUS_HOME" home "HOME" home}
              :continue true}
             command))))
