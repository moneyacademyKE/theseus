(ns e2e.brain-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [bb-agent.brain :as brain]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]))

(deftest load-brain-missing-dir-returns-empty
  (is (= "" (brain/load-brain "/tmp/definitely-not-a-brain-dir-8391"))))

(deftest load-brain-sorts-headers-and-filters
  (let [dir (fs/create-temp-dir {:prefix "opencrabs-bb-brain-"})]
    (try
      (spit (str (fs/path dir "z-identity.md")) "I am terse.")
      (spit (str (fs/path dir "a-rules.md")) "Never guess.")
      (spit (str (fs/path dir "notes.txt")) "not markdown")
      (let [out (brain/load-brain (str dir))]
        (is (str/includes? out "## a-rules.md"))
        (is (str/includes? out "## z-identity.md"))
        (is (str/includes? out "Never guess."))
        (is (str/includes? out "I am terse."))
        (is (not (str/includes? out "not markdown")))
        (is (neg? (compare (str/index-of out "## a-rules.md")
                           (str/index-of out "## z-identity.md")))))
      (finally
        (fs/delete-tree dir)))))

(deftest brain-context-injected-into-provider-request
  (let [home (fs/create-temp-dir {:prefix "opencrabs-bb-brain-home-"})
        captured (atom nil)]
    (try
      (fs/create-dirs (fs/path home "brain"))
      (spit (str (fs/path home "brain" "IDENTITY.md"))
            "BRAIN-MARKER-742: speak in haiku")
      (with-redefs [config/home (fn [] (str home))
                    provider/complete (fn [_ request]
                                        (reset! captured request)
                                        {:content "fake: ok" :usage nil})]
        (let [turn (core/run-turn! {:provider :fake
                                    :model "fake-deterministic"
                                    :session/id "brain-e2e"}
                                   "say ok")]
          (is (= "fake: ok" (:assistant/final turn))))
        (let [messages (:messages @captured)
              system (filter #(= :system (:role %)) messages)]
          (is (= 1 (count system)) (pr-str messages))
          (is (str/includes? (:content (first system)) "BRAIN-MARKER-742"))
          (is (= :user (:role (last messages))))))
      (finally
        (fs/delete-tree home)))))
