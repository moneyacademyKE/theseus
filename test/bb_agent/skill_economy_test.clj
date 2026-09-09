(ns bb-agent.skill-economy-test
  "V4 pins (bk-a1ae): the claims-vs-reads ledger is honest. Claims come
   from project.edn's :skills-used; reads from SKILL.md path receipts in
   authoring artifacts. The discrepancy — decoration — must be visible."
  (:require [babashka.fs :as fs]
            [bb-agent.skill-economy :as se]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn with-tmp-goals [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "skill-econ-test"}))]
    (binding [*tmp* tmp]
      (f))))

(use-fixtures :each with-tmp-goals)

(defn seed-ws!
  [name skills evidence]
  (let [ws (str *tmp* "/" name)]
    (fs/create-dirs ws)
    (spit (str ws "/project.edn")
          (pr-str (cond-> {:name name :observers {} :goal {} :act {}}
                    skills (assoc :skills-used skills))))
    (when evidence
      (spit (str ws "/authoring.edn") evidence))
    ws))

(deftest claims-and-reads-test
  (testing "claims come from project.edn; reads from SKILL.md path receipts"
    (let [ws (seed-ws! "ws-a" ["verification-witness" "adr"]
                       "read_file skills/verification-witness/SKILL.md ok")]
      (is (= #{"verification-witness" "adr"} (se/claimed-skills ws)))
      (is (= #{"verification-witness"} (se/read-receipts ws)))
      (is (= #{} (se/claimed-skills (str *tmp* "/never-was")))
          "a missing workspace claims nothing"))))

(deftest fleet-verdicts-test
  (testing "load-bearing vs decoration vs inventory; prune candidates named"
    (seed-ws! "ws-b" ["verification-witness" "adr"]
              "read_file skills/verification-witness/SKILL.md")
    (seed-ws! "ws-c" nil
              "read_file skills/frontend-design/SKILL.md")
    (let [{:keys [skills prune-candidates per-workspace]} (se/fleet-report *tmp*)]
      (is (= 2 (count per-workspace)) "two workspaces seeded")
      (is (= 1 (get-in skills ["verification-witness" :claims])))
      (is (= 1 (get-in skills ["verification-witness" :reads])))
      (is (= 0 (get-in skills ["frontend-design" :claims])) "read, never claimed")
      (is (= 1 (get-in skills ["frontend-design" :reads])))
      (is (= 1 (get-in skills ["adr" :claims])))
      (is (= 0 (get-in skills ["adr" :reads])))
      (is (contains? prune-candidates "adr") "claimed, never read → prune candidate")
      (is (not (contains? prune-candidates "verification-witness")))
      (is (not (contains? prune-candidates "frontend-design"))
          "inventory noise is not a prune candidate"))))

(deftest render-test
  (testing "the report is one human screen with the discrepancy visible"
    (seed-ws! "ws-d" ["adr"] nil)
    (let [out (se/render (se/fleet-report *tmp*))]
      (is (str/includes? out "skill economy over 1 workspaces"))
      (is (str/includes? out "adr"))
      (is (str/includes? out "prune candidates")))))
