(ns bb-agent.a2a-client-test
  "A2A client wire logic against a redefs'd post-json seam, plus the
  a2a_task handler mapping and its approval gating."
  (:require [bb-agent.a2a-client :as a2a]
            [bb-agent.a2a-tool :as a2a-tool]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- rpc-res
  ([result] (rpc-res 1 result))
  ([id result]
   {:status 200 :body (json/generate-string {:jsonrpc "2.0" :id id :result result})}))

(defn- fake-post-seq
  "Return a post-json replacement serving `responses` in order, repeating
  the last one forever (poll loops)."
  [responses]
  (let [n (atom 0)]
    (fn [_url _body _token]
      (swap! n inc)
      (nth responses (dec (min @n (count responses)))))))

(deftest run-task-completes-with-reply
  (let [submitted {:id "t-1" :status {:state "submitted"}}
        working {:id "t-1" :status {:state "working"}}
        done {:id "t-1" :status {:state "completed"}
              :artifacts [{:parts [{:kind "text" :text "pong"}]}]}]
    (with-redefs [a2a/post-json (fake-post-seq [(rpc-res 1 submitted)
                                                (rpc-res 2 working)
                                                (rpc-res 2 done)])]
      (let [r (a2a/run-task {:base-url "http://127.0.0.1:18790"
                             :text "reply pong"
                             :token "tok"
                             :timeout-ms 5000
                             :poll-ms 1})]
        (is (:ok? r))
        (is (= "completed" (:state r)))
        (is (= "pong" (:text r)))
        (is (= "t-1" (:task-id r)))))))

(deftest send-fails-closed-on-401
  (with-redefs [a2a/post-json (fn [_ _ _] {:status 401 :body "{\"error\":\"unauthorized\"}"})]
    (let [r (a2a/run-task {:base-url "http://127.0.0.1:18790" :text "x" :token "tok"})]
      (is (not (:ok? r)))
      (is (= 401 (:status r)))
      (is (str/includes? (:error r) "401")))))

(deftest outgoing-message-is-spec-shaped
  ;; OpenCrabs' server rejects a role-less message with
  ;; "Invalid params: missing field `role`" — pinned live 2026-11-12.
  (let [sent (atom nil)]
    (with-redefs [a2a/post-json (fn [_url body _token]
                                  (reset! sent body)
                                  {:status 500 :body "{}"})]
      (a2a/run-task {:base-url "http://127.0.0.1:18790" :text "x"})
      (let [message (get-in @sent [:params :message])]
        (is (= "user" (:role message)))
        (is (= "text" (get-in message [:parts 0 :kind])))
        (is (= "x" (get-in message [:parts 0 :text])))
        (is (= "theseus-a2a" (:contextId message)))))))

(deftest rpc-error-is-surfaced-not-guessed
  (with-redefs [a2a/post-json (fn [_ _ _]
                                {:status 200
                                 :body (json/generate-string
                                        {:jsonrpc "2.0" :id 1
                                         :error {:code -32602 :message "empty text"}})})]
    (let [r (a2a/run-task {:base-url "http://127.0.0.1:18790" :text ""})]
      (is (not (:ok? r)))
      (is (str/includes? (:error r) "empty text")))))

(deftest failed-task-surfaces-remote-error
  (let [submitted {:id "t-9" :status {:state "submitted"}}
        failed {:id "t-9" :status {:state "failed"
                                    :message {:parts [{:kind "text" :text "model unavailable"}]}}}]
    (with-redefs [a2a/post-json (fake-post-seq [(rpc-res 1 submitted)
                                                (rpc-res 2 failed)])]
      (let [r (a2a/run-task {:base-url "http://127.0.0.1:18790" :text "x"
                             :timeout-ms 500 :poll-ms 1})]
        (is (not (:ok? r)))
        (is (= "failed" (:state r)))
        (is (str/includes? (:error r) "model unavailable"))))))

(deftest await-times-out-honestly
  (let [working {:id "t-2" :status {:state "working"}}]
    (with-redefs [a2a/post-json (fake-post-seq [(rpc-res 1 working)
                                                (rpc-res 2 working)])]
      (let [r (a2a/run-task {:base-url "http://127.0.0.1:18790" :text "x"
                             :timeout-ms 60 :poll-ms 10})]
        (is (not (:ok? r)))
        (is (:timed-out? r))
        (is (= "t-2" (:task-id r)))))))

(deftest task-text-tolerates-part-shapes
  (is (= "a\nb" (a2a/task-text {:artifacts [{:parts [{:kind "text" :text "a"}]}
                                             {:parts [{:type "text" :text "b"}]}]})))
  (is (= "c" (a2a/task-text {:artifacts [{:parts [{:text "c"}]}]})))
  (is (= "" (a2a/task-text nil))))

(deftest registry-and-approval
  (is (some #(= "a2a_task" (get-in % [:function :name])) tools/definitions))
  (is (not (contains? common/safe-auto-tools "a2a_task")))
  (is (= :denied (:status (tool/handle-tool-request
                           {:tool/name "a2a_task"
                            :tool/args {:url "http://127.0.0.1:18790" :text "x"}
                            :approval/policy :auto-safe})))))

(deftest handler-maps-run-task-to-result-shapes
  (testing "completed reply becomes a bounded ok-result"
    (with-redefs [a2a/run-task (fn [_] {:ok? true :state "completed"
                                        :text (apply str (repeat 30000 "y"))
                                        :task-id "t"})]
      (let [r (tool/execute-tool-request {:tool/name "a2a_task"
                                          :tool/args {:url "http://127.0.0.1:18790"
                                                      :text "make a pdf"}})]
        (is (= :ok (:status r)))
        (is (<= (count (:output r)) 20000))
        (is (= "t" (:task-id r))))))
  (testing "remote failure becomes an error-result, executed"
    (with-redefs [a2a/run-task (fn [_] {:ok? false :error "boom" :state "failed"})]
      (let [r (tool/execute-tool-request {:tool/name "a2a_task"
                                          :tool/args {:url "http://127.0.0.1:18790"
                                                      :text "x"}})]
        (is (= :error (:status r)))
        (is (= "boom" (:error/message r))))))
  (testing "invalid url rejected before any network call"
    (let [called (atom false)]
      (with-redefs [a2a/run-task (fn [_] (reset! called true) {:ok? false :error "unreachable"})]
        (let [r (tool/execute-tool-request {:tool/name "a2a_task"
                                            :tool/args {:url "ftp://nope" :text "x"}})]
          (is (= :error (:status r)))
          (is (not (:executed? r)))
          (is (false? @called)))))))

(deftest bearer-attaches-only-to-localhost
  (let [seen (atom {})]
    (with-redefs [a2a-tool/stored-token (fn [] "bridge-secret")
                  a2a/run-task (fn [opts]
                                 (swap! seen assoc :token (:token opts)
                                        :base-url (:base-url opts))
                                 {:ok? false :error "captured"})]
      (a2a-tool/a2a-task {:url "http://127.0.0.1:18790" :text "x"})
      (is (= "bridge-secret" (:token @seen)))
      (a2a-tool/a2a-task {:url "https://example.com" :text "x"})
      (is (= "https://example.com" (:base-url @seen)))
      (is (nil? (:token @seen))))))
