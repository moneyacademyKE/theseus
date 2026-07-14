(ns e2e.memory-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- shell! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- run-agent [home prompt]
  (shell! home "agent" prompt))

(deftest memory-add-search-and-context-attachment
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-memory-e2e-"})
        memory-file (fs/path home "state" "memory.edn")
        session-file (fs/path home "state" "sessions" "default.edn")]
    (try
      (let [add-1 (shell! home "memory" "add" "Project codename is theseus")
            add-2 (shell! home "memory" "add" "Moe prefers babashka for scripting")
            search (shell! home "memory" "search" "theseus")
            agent (run-agent home "use memory theseus")]
        (is (= 0 (:exit add-1)) (:err add-1))
        (is (= 0 (:exit add-2)) (:err add-2))
        (is (= 0 (:exit search)) (:err search))
        (is (= 0 (:exit agent)) (:err agent))
        (is (fs/regular-file? memory-file))
        (let [stored (edn/read-string (slurp (str memory-file)))]
          (is (= 2 (count stored)))
          (is (= "Project codename is theseus" (:memory/text (first stored)))))
        (is (str/includes? (:out search) "Project codename is theseus"))
        (is (str/includes? (:out agent) "Project codename is theseus"))
        (let [turns (edn/read-string (slurp (str session-file)))
              turn (first turns)]
          (is (= "use memory theseus" (:user/input turn)))
          (is (= ["Project codename is theseus"]
                 (mapv :memory/text (:memory/matches turn))))))
      (finally
        (fs/delete-tree home)))))
