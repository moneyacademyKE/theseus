(ns bb-agent.path-lock-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.path-lock :as pl]
            [clojure.test :refer [deftest is testing]]))

(deftest advisory-path-lock-lives-under-home-and-serializes-test
  (let [home (fs/create-temp-dir {:prefix "theseus-lock-home-"})
        repo (fs/create-temp-dir {:prefix "theseus-lock-repo-"})
        target (str (fs/path repo "file.txt"))]
    (try
      (with-redefs [config/home (constantly (str home))]
        (testing "lockfile is placed under <home>/locks/paths/, never inside the repo tree"
          (let [lp (pl/lock-path target)]
            (is (string? lp))
            (is (.startsWith ^String lp (str (fs/path home "locks" "paths"))))
            (is (empty? (fs/list-dir repo)))))

        (testing "with-path-lock executes thunk cleanly and releases lock afterward"
          (let [ran (atom nil)]
            (is (= :done (pl/with-path-lock target
                           (fn [{:keys [held?]}]
                             (reset! ran held?)
                             :done))))
            (is (true? @ran)))
          ;; Re-acquiring immediately proves the prior lock was released
          (let [re-held (atom nil)]
            (pl/with-path-lock target (fn [{:keys [held?]}] (reset! re-held held?)))
            (is (true? @re-held)))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree repo)))))
