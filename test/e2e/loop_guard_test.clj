(ns e2e.loop-guard-test
  "Repetition-aware loop guard: the same tool+args must not burn all 8
   rounds. Identical repeats stop the turn gracefully with a clear
   message; varying args never trip the guard."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-temp-home [f]
  (let [home (str (fs/create-temp-dir {:prefix "loop-guard-"}))]
    (try
      (with-redefs [config/home (fn [] home)]
        (f))
      (finally
        (fs/delete-tree home)))))

(deftest identical-repeats-stop-gracefully
  (testing "same tool+args 3rd time → loop-guard message, no throw"
    (with-temp-home
     (fn []
       (let [calls (atom 0)]
         (with-redefs [provider/complete
                       (fn [_provider _request]
                         (swap! calls inc)
                         {:content nil
                          :tool/requests [{:tool/name "shell"
                                           :tool/args {:cmd "echo same"}}]})
                       tool/handle-tool-request
                       (fn [_req _cfg] {:status :ok :output "same"})]
           (let [turn (core/run-turn! {:provider :fake :model "m"
                                       :react-ack false}
                                      "do the thing")
                 final (:assistant/final turn)]
             (is (str/includes? final "Loop guard")
                 "final message names the guard")
             (is (str/includes? final "shell")
                 "names the repeated tool")
             (is (<= @calls 4) "stops long before the 8-round cap"))))))))

(deftest varying-args-never-trip-the-guard
  (testing "distinct args run to completion normally"
    (with-temp-home
     (fn []
       (let [cmds (atom ["a" "b" "c"])]
         (with-redefs [provider/complete
                       (fn [_provider _request]
                         (if-let [c (first @cmds)]
                           (do (swap! cmds rest)
                               {:content nil
                                :tool/requests [{:tool/name "shell"
                                                 :tool/args {:cmd (str "echo " c)}}]})
                           {:content "all done"}))
                       tool/handle-tool-request
                       (fn [_req _cfg] {:status :ok :output "ok"})]
           (let [turn (core/run-turn! {:provider :fake :model "m"
                                       :react-ack false}
                                      "run three different echoes")]
             (is (= "all done" (:assistant/final turn))
                 "normal completion, no guard message"))))))))
