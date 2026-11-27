(ns bb-agent.repl-test
  "Red/Green tests for REPL-driven development enforcement (ADR 0013)."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.doctor :as doctor]
            [bb-agent.repl :as repl]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]
            [bencode.core :as bencode]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest clojure-file-detection-and-parse-check
  (testing "clojure-file? recognizes Clojure source extensions only"
    (is (true? (repl/clojure-file? "src/bb_agent/core.clj")))
    (is (true? (repl/clojure-file? "src/app.cljc")))
    (is (true? (repl/clojure-file? "web/app.cljs")))
    (is (true? (repl/clojure-file? "scripts/check.bb")))
    (is (false? (repl/clojure-file? "README.md")))
    (is (false? (repl/clojure-file? "config.edn")))
    (is (false? (repl/clojure-file? "web/js/main.js"))))
  (testing "parse-check-clojure validates balanced syntax including reader macros"
    (is (true? (:ok? (repl/parse-check-clojure "(ns a)\n(defn f [x] #_skip ::kw `(~x))"))))
    (let [bad (repl/parse-check-clojure "(defn broken [x] (+ x 1)")]
      (is (false? (:ok? bad)))
      (is (string? (:error bad))))))

(deftest repl-eval-bb-fallback-and-error-handling
  (let [res (repl/eval-code! {:code "(println \"hi\") (+ 1 2 3)"})]
    (is (true? (:ok? res)))
    (is (= :bb (:mode res)))
    (is (= "6" (:value res)))
    (is (str/includes? (:stdout res) "hi")))
  (let [err (repl/eval-code! {:code "(/ 1 0)"})]
    (is (false? (:ok? err)))
    (is (string? (:error err)))))

(deftest repl-eval-prefers-live-nrepl-port-over-bencode
  (let [dir (fs/create-temp-dir {:prefix "theseus-nrepl-"})
        ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort ss)
        server-fut (future
                     (try
                       (with-open [sock (.accept ss)
                                   in (java.io.PushbackInputStream. (.getInputStream sock))
                                   out (.getOutputStream sock)]
                         (let [_req (bencode/read-bencode in)]
                           (bencode/write-bencode out {"out" "from-nrepl\n"})
                           (bencode/write-bencode out {"value" "42" "ns" "user"})
                           (bencode/write-bencode out {"status" ["done"]})
                           (.flush out)))
                       (catch Exception _ nil)))]
    (try
      (spit (str (fs/path dir ".nrepl-port")) (str port))
      (let [res (repl/eval-code! {:code "(+ 40 2)" :cwd (str dir)})]
        (is (true? (:ok? res)))
        (is (= :nrepl (:mode res)))
        (is (= "42" (:value res)))
        (is (str/includes? (str (:stdout res)) "from-nrepl")))
      (finally
        (try (.close ss) (catch Exception _ nil))
        @server-fut
        (fs/delete-tree dir)))))

(deftest repl-gate-enforces-eval-before-clojure-edit-and-parse-check-before-write
  (let [dir (fs/create-temp-dir {:prefix "theseus-repl-gate-"})
        clj-file (str (fs/path dir "demo.clj"))
        md-file (str (fs/path dir "notes.md"))
        evals (atom 0)
        cfg {:approval/mode :auto-all
             :cwd (str dir)
             :repl/enforce true
             :repl/evals evals}]
    (try
      (testing "repl_eval is advertised in tools/definitions and approval-gated (not safe-auto)"
        (is (some #(= "repl_eval" (get-in % [:function :name])) tools/definitions))
        (is (some #(= "repl_eval" (get-in % [:function :name])) (tools/active-definitions nil)))
        (is (not (contains? common/safe-auto-tools "repl_eval"))))

      (testing "non-Clojure edits are not blocked by :repl-gate"
        (let [r (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path md-file :content "# hello"}}
                 cfg)]
          (is (= :ok (:status r)))))

      (testing "Clojure edit BEFORE any repl_eval is denied by :repl-gate"
        (let [r (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path clj-file :content "(ns demo)\n(def x 1)\n"}}
                 cfg)]
          (is (= :denied (:status r)))
          (is (= :repl-gate (:denial/source r)))
          (is (not (fs/exists? clj-file)))))

      (testing "after one successful repl_eval, valid Clojure write succeeds"
        (let [ev (tool/handle-tool-request
                  {:tool/name "repl_eval"
                   :tool/args {:code "(+ 1 1)"}}
                  cfg)]
          (is (= :ok (:status ev)))
          (is (= 1 @evals)))
        (let [w (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path clj-file :content "(ns demo)\n(def x 1)\n"}}
                 cfg)]
          (is (= :ok (:status w)))
          (is (fs/exists? clj-file))))

      (testing "parse-before-land refuses unbalanced Clojure on both write_file and edit_file"
        (let [bad-w (tool/handle-tool-request
                     {:tool/name "write_file"
                      :tool/args {:path clj-file :content "(ns demo)\n(defn broken [x]"}}
                     cfg)
              bad-e (tool/handle-tool-request
                     {:tool/name "edit_file"
                      :tool/args {:path clj-file :old_text "(def x 1)" :new_text "(def x 1"}}
                     cfg)]
          (is (= :error (:status bad-w)))
          (is (= :error (:status bad-e)))
          (is (= "(ns demo)\n(def x 1)\n" (slurp clj-file))
              "broken Clojure must never overwrite the valid file on disk")))

      (testing "opt-out :repl/enforce false disables :repl-gate"
        (let [r (tool/handle-tool-request
                 {:tool/name "write_file"
                  :tool/args {:path (str (fs/path dir "optout.clj"))
                              :content "(ns optout)\n"}}
                 {:approval/mode :auto-all :repl/enforce false :repl/evals (atom 0)})]
          (is (= :ok (:status r)))))
      (finally
        (fs/delete-tree dir)))))

(deftest doctor-recognizes-repl-enforce-config-key
  (let [home (fs/create-temp-dir {:prefix "theseus-repl-doc-"})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake :model "fake-deterministic" :repl/enforce true}))
      (with-redefs [config/home (constantly (str home))]
        (let [chk (first (filter #(= :config-keys (:check %)) (doctor/run-checks)))]
          (is (= :ok (:status chk)))))
      (finally
        (fs/delete-tree home)))))
