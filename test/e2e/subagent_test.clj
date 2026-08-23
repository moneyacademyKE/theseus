(ns e2e.subagent-test
  "Subagent ledger tests: hermes-beam's supervisor flattened into pure
  record transitions (:pending -> :claimed -> :completed/:failed) plus
  the file-backed shell. Process maps, not processes. Handles are
  id-or-record; pending/claimed return records."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.subagent :as sa]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def t0 1000)
(def t1 2000)

(deftest spawn-creates-pending-record
  (let [[ledger record] (sa/spawn [] "summarize the repo")]
    (is (= 1 (count ledger)))
    (is (= :pending (:subagent/status record)))
    (is (= "summarize the repo" (:subagent/prompt record)))
    (is (string? (:subagent/id record)))
    (testing "re-spawning the same id replaces, not duplicates"
      (is (= 1 (count (first (sa/spawn ledger (:subagent/id record) "again"))))))))

(deftest lifecycle-claimed-then-completed
  (let [[l rec] (sa/spawn [] "work")
        id (:subagent/id rec)
        l' (sa/claim l id :worker-1 t0)
        claimed (sa/by-id l' id)
        l'' (sa/complete l' id "the answer" t1)]
    (is (= :claimed (:subagent/status claimed)))
    (is (= :worker-1 (:subagent/assignee claimed)))
    (is (= t0 (:subagent/at claimed)))
    (let [done (sa/by-id l'' id)]
      (is (= :completed (:subagent/status done)))
      (is (= "the answer" (:subagent/result done)))
      (is (= t1 (:subagent/at done))))))

(deftest failed-path-records-reason
  (let [[l rec] (sa/spawn [] "work")
        id (:subagent/id rec)
        l' (-> l (sa/claim id :worker-1 t0) (sa/fail id "provider exploded" t1))
        rec' (sa/by-id l' id)]
    (is (= :failed (:subagent/status rec')))
    (is (= "provider exploded" (:subagent/reason rec')))))

(deftest invalid-transitions-are-no-ops
  (testing "claiming a non-pending record changes nothing"
    (let [[l rec] (sa/spawn [] "work")
          id (:subagent/id rec)
          l' (sa/claim l id :w1 t0)
          l'' (sa/claim l' id :w2 t1)]
      (is (= :w1 (:subagent/assignee (sa/by-id l'' id))))))
  (testing "completing an unclaimed record changes nothing"
    (let [[l rec] (sa/spawn [] "work")
          l' (sa/complete l (:subagent/id rec) "early" t1)]
      (is (= :pending (:subagent/status (sa/by-id l' (:subagent/id rec)))))))
  (testing "claiming an unknown id changes nothing"
    (let [[l _] (sa/spawn [] "work")]
      (is (= l (sa/claim l "ghost" :w1 t0))))))

(deftest capacity-limits-concurrent-claims
  (let [[l ra] (sa/spawn [] "a")
        [l rb] (sa/spawn l "b")
        [l rc] (sa/spawn l "c")
        a (:subagent/id ra)
        b (:subagent/id rb)
        c (:subagent/id rc)
        l2 (sa/claim l a :w1 t0 2)
        l3 (sa/claim l2 b :w2 t0 2)]
    (is (sa/can-claim? l 2) "empty ledger has capacity")
    (is (sa/can-claim? l2 2) "one claim of two, still room")
    (testing "capacity-enforcing arity refuses the third claim"
      (let [l4 (sa/claim l3 c :w3 t0 2)]
        (is (= :pending (:subagent/status (sa/by-id l4 c))))))
    (testing "completing frees the slot"
      (let [l4 (sa/complete l3 a "done" t1)]
        (is (sa/can-claim? l4 2))
        (is (= :claimed (:subagent/status (sa/by-id (sa/claim l4 c :w3 t1 2) c))))))))

(deftest selectors
  (let [[l ra] (sa/spawn [] "a")
        l (first (sa/spawn l "b"))
        a (:subagent/id ra)
        l (sa/claim l a :w1 t0)]
    (is (= ["b"] (mapv :subagent/prompt (sa/pending l))))
    (is (= [a] (mapv :subagent/id (sa/claimed l))))))

(deftest file-shell-round-trips-through-state-edn
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-subagent-e2e-"})
        script (str/join "\n"
                         ["(require '[bb-agent.subagent :as sa])"
                          "(sa/spawn! \"t1\" \"do work\")"
                          "(sa/claim! \"t1\" :worker-1 4)"
                          "(sa/complete! \"t1\" \"all done\")"
                          "(println :shell-ok)"])]
    (try
      (let [r (p/shell {:out :string
                        :err :string
                        :continue true
                        :extra-env {"OPENCRABS_HOME" (str home)}}
                       "bb" "-e" script)]
        (is (= 0 (:exit r)) (:err r))
        (is (.contains ^String (:out r) "shell-ok"))
        (let [ledger (edn/read-string (slurp (str (fs/path home "state" "subagents.edn"))))]
          (is (= 1 (count ledger)))
          (let [{:subagent/keys [id status assignee result prompt]} (first ledger)]
            (is (= "t1" id))
            (is (= :completed status))
            (is (= :worker-1 assignee))
            (is (= "all done" result))
            (is (= "do work" prompt)))))
      (finally
        (fs/delete-tree home)))))
