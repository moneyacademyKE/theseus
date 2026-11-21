(ns e2e.kondo-test
  (:require [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [clojure.test :as t :refer [deftest is testing]]))

(def ^:private kondo
  (or (System/getenv "KONDO_BIN")
      (when (.exists (java.io.File. "../.tools/bin/clj-kondo"))
        "../.tools/bin/clj-kondo")
      (some-> (fs/which "clj-kondo") str)))

(defn- lint
  "Run bb lint against a path; returns exit code."
  [path]
  (-> (sh {:dir "." :continue true}
          "bb" "lint" (str path))
      :exit))

(deftest kondo-static-gate
  (if-not (and kondo (.exists (java.io.File. kondo)))
    (testing "clj-kondo binary missing; lint task must say so, not silently pass"
      (is (pos-int? (lint "/nonexistent-target-should-fail-for-missing-bin-or-path"))))
    (let [dir (str (fs/create-temp-dir))
          good (fs/path dir "good.clj")
          warn (fs/path dir "warn.clj")
          bad  (fs/path dir "bad.clj")]
      (spit (str good) "(ns good) (defn f [x] x)\n")
      (spit (str warn) "(ns warn) (defn f [unused] 1)\n")
      (spit (str bad)  "(ns bad) (defn broken (]\n")
      (testing "clean file lints clean"
        (is (zero? (lint good))))
      (testing "warning fails the gate (--fail-level warning)"
        (is (pos-int? (lint warn))))
      (testing "unparseable file fails the gate"
        (is (pos-int? (lint bad)))))))
