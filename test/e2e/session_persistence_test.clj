(ns e2e.session-persistence-test
  "Session persistence under hostile conditions: cross-process writers must
   not lose each other's turns (the lost-update that froze Eileen's memory
   mid-September — amnesia F1, 2026-09-08), and a truncated history file
   must be archived, never allowed to poison every future append."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-home
  [f]
  (let [home (str (fs/create-temp-dir {:prefix "theseus-sess-"}))]
    (try
      (with-redefs [config/home (fn [] home)]
        (f home))
      (finally
        (fs/delete-tree home)))))

(deftest sequential-appends-preserve-every-turn
  (with-home
    (fn [_]
      (dotimes [i 5]
        (session/append-turn! "t1" {:user/input (str "turn " i)}))
      (let [turns (session/load-turns "t1")]
        (is (= 5 (count turns)))
        (is (= ["turn 0" "turn 1" "turn 2" "turn 3" "turn 4"]
               (mapv :user/input turns))
            "append order preserved, nothing dropped")))))

(deftest cross-process-appends-lose-nothing
  "The real F1 regression: three live bb processes race the test process
   on ONE session file. Without the OS file lock, the last writer's spit
   silently ate every turn written since its own read."
  (with-home
    (fn [home]
      (dotimes [i 5]
        (session/append-turn! "x-test" {:user/input (str "parent-pre-" i)}))
      (let [procs (doall
                   (for [s (range 3)]
                     (p/process {:dir (str (fs/cwd))
                                 ;; into{} first: System/getenv is a bare Java map SCI can't merge.
                                 :env (assoc (into {} (System/getenv)) "OPENCRABS_HOME" home)
                                 :out :string :err :string}
                                "bb" "-cp" "src" "-e"
                                (str "(require '[bb-agent.session :as session])"
                                     " (println \"HOME-CHECK\" (str (bb-agent.config/home)))"
                                     " (dotimes [i 5]"
                                     "   (session/append-turn! \"x-test\""
                                     "     {:user/input (str \"sub-" s "\" \"-\" i)}))"
                                     " (println \"SUB-OK\")"))))]
        ;; Parent writes WHILE the subprocesses run — that overlap is the race.
        (dotimes [i 5]
          (session/append-turn! "x-test" {:user/input (str "parent-during-" i)}))
        (doseq [prc procs]
          (let [{:keys [exit out err]} (deref prc 30000 {:exit -1 :out "" :err ""})]
            (is (zero? exit) (str "subprocess failed: " err out))
            (is (str/includes? out "SUB-OK") (str "no SUB-OK in: " out))
            (is (str/includes? out (str "HOME-CHECK " home))
                (str "sub resolved the WRONG home: " out))))
        (let [turns (session/load-turns "x-test")
              inputs (set (map :user/input turns))
              expected (set (concat (map #(str "parent-pre-" %) (range 5))
                                    (map #(str "parent-during-" %) (range 5))
                                    (for [s (range 3) i (range 5)]
                                      (str "sub-" s "-" i))))]
          (is (= 25 (count turns)) (str "saw: " (count turns) " turns"))
          (is (empty? (clojure.set/difference expected inputs))
              "every writer's every turn survived — zero lost updates"))))))

(deftest corrupt-history-archived-not-poisonous
  "A truncated file used to make every later append throw on read, so the
   session froze at the last good write. Now: archive the corpse with a
   .corrupt- suffix, return [], and keep appending."
  (with-home
    (fn [home]
      (session/append-turn! "t3" {:user/input "good turn before the crash"})
      (let [f (str (session/session-file "t3"))]
        ;; Simulate a mid-spit crash: keep the header, cut the body.
        (spit f "[{:user/input \"good turn befo\"")
        (is (= [] (session/load-turns "t3")) "corrupt file reads as empty")
        (let [archived (fs/glob (str home "/state/sessions") "*.corrupt-*")]
          (is (= 1 (count archived)) "history archived for inspection, not eaten")
          (is (str/includes? (slurp (str (first archived))) "good turn befo")))
        (session/append-turn! "t3" {:user/input "first turn after recovery"})
        (is (= ["first turn after recovery"]
               (mapv :user/input (session/load-turns "t3")))
            "session keeps working after archival")))))

(deftest appends-still-redact-secrets
  "The lock must not regress the redaction seam: persistence stays the
   place where secrets go to die."
  (with-home
    (fn [home]
      (session/append-turn! "t4" {:user/input "token 12345678:AAAAAAAAAAabcdefghjklmno"})
      (let [raw (slurp (str (session/session-file "t4")))]
        (is (str/includes? raw "<REDACTED-SECRET>"))
        (is (not (str/includes? raw "AAAAAAAAAAabcdefghjklmno")))))))
