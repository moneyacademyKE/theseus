(ns bb-agent.browser-tool-test
  (:require [bb-agent.browser-tool :as browser]
            [bb-agent.mcp-client :as mcp]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- response [text]
  {:ok? true :result {:isError false :content [{:type "text" :text text}]}})

(deftest registry-and-approval
  (is (some #(= "browser_read" (get-in % [:function :name])) tools/definitions))
  (is (not (contains? common/safe-auto-tools "browser_read")))
  (is (= :denied (:status (tool/handle-tool-request
                          {:tool/name "browser_read" :tool/args {:url "https://example.com"}
                           :approval/policy :auto-safe})))))

(deftest lifecycle-owns-its-tab
  (let [calls (atom [])]
    (with-redefs [mcp/connect (fn [_] {:ok? true :initialized? true})
                  mcp/call-tool (fn [_ name args]
                                  (swap! calls conj [name args])
                                  (case [name (:action args)]
                                    ["tabs" "list"] (response "existing pages hidden")
                                    ["tabs" "new"] (response "opened page 42")
                                    ["read" nil] (response "Example page")
                                    ["tabs" "close"] (response "closed")))]
      (let [result (tool/execute-tool-request {:tool/name "browser_read"
                                               :tool/args {:url "https://example.com"}})]
        (is (= :ok (:status result)))
        (is (str/includes? (:output result) "Example page"))
        (is (= [["tabs" {:action "list"}]
                ["tabs" {:action "new" :url "https://example.com"}]
                ["read" {:page 42 :format "text"}]
                ["tabs" {:action "close" :page 42}]] @calls))
        (is (not (str/includes? (pr-str result) "existing pages hidden")))))))

(deftest refuses-invalid-input-before-connecting
  (doseq [url [nil "" "file:///etc/passwd" "javascript:alert(1)" "https://user:secret@example.com"]]
    (with-redefs [mcp/connect (fn [_] (throw (ex-info "must not connect" {})))]
      (is (= :error (:status (browser/read-page {:url url})))))))

(deftest errors-fail-closed-and-clean-up
  (testing "an MCP-level tool error is not a success and the owned tab is closed"
    (let [calls (atom [])]
      (with-redefs [mcp/connect (fn [_] {:ok? true})
                    mcp/call-tool (fn [_ name args]
                                    (swap! calls conj [name args])
                                    (case [name (:action args)]
                                      ["tabs" "list"] (response "ok")
                                      ["tabs" "new"] (response "opened page 42")
                                      ["read" nil] {:ok? true :result {:isError true :content [{:type "text" :text "private"}]}}
                                      ["tabs" "close"] (response "closed")))]
        (let [result (browser/read-page {:url "https://example.com"})]
          (is (= :error (:status result)))
          (is (not (str/includes? (pr-str result) "private")))
          (is (= ["tabs" {:action "close" :page 42}] (last @calls)))))))
  (testing "unrecognized open result cannot target an existing page"
    (let [calls (atom [])]
      (with-redefs [mcp/connect (fn [_] {:ok? true})
                    mcp/call-tool (fn [_ name args]
                                    (swap! calls conj [name args])
                                    (if (= "new" (:action args)) (response "unclear") (response "ok")))]
        (is (= :error (:status (browser/read-page {:url "https://example.com"}))))
        (is (= 2 (count @calls)))))))

(deftest output-is-bounded
  (with-redefs [mcp/connect (fn [_] {:ok? true})
                mcp/call-tool (fn [_ name args]
                                (case [name (:action args)]
                                  ["tabs" "list"] (response "ok")
                                  ["tabs" "new"] (response "opened page 5")
                                  ["read" nil] (response (apply str (repeat 30000 "x")))
                                  ["tabs" "close"] (response "closed")))]
    (let [result (browser/read-page {:url "https://example.com"})]
      (is (= :ok (:status result)))
      (is (<= (count (:output result)) 20000)))))
