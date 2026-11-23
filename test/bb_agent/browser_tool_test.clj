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
  ;; Enabled via config, the auto-safe policy still refuses it: approval-gated.
  (is (= :denied (:status (tool/handle-tool-request
                          {:tool/name "browser_read" :tool/args {:url "https://example.com"}
                           :approval/policy :auto-safe}
                          {:tools/enable-optional ["browser_read"]}))))
  ;; Not opted in: refused as a dormant capability before any approval logic.
  (is (= :dormant (:denial/source (tool/handle-tool-request
                                   {:tool/name "browser_read"
                                    :tool/args {:url "https://example.com"}})))))

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
  (doseq [url [nil "" "file:///etc/passwd" "javascript:alert(1)" "https://user:secret@example.com"
               "https://localhost/admin" "https://127.0.0.1:8443/api"
               "https://169.254.169.254/latest/meta-data" "https://10.0.0.1/secret"
               "https://192.168.1.1/router" "https://172.16.0.1/internal"
               "https://[::1]/local" "https://0.0.0.0/"]]
    (with-redefs [mcp/connect (fn [_] (throw (ex-info "must not connect" {})))]
      (let [r (browser/read-page {:url url})]
        (is (= :error (:status r)) (str "should refuse: " url))
        (is (false? (:executed? r)) (str "must not execute: " url))))))

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

(deftest allows-https-fragments-and-preserves-read-on-close-hiccup
  (testing "HTTPS URLs with #anchors are accepted"
    (with-redefs [mcp/connect (fn [_] {:ok? true})
                  mcp/call-tool (fn [_ name args]
                                  (case [name (:action args)]
                                    ["tabs" "list"] (response "ok")
                                    ["tabs" "new"] (response "opened page 9")
                                    ["read" nil] (response "Anchored docs")
                                    ["tabs" "close"] (response "closed")))]
      (let [res (browser/read-page {:url "https://clojure.org/reference/reader#_literals"})]
        (is (= :ok (:status res)))
        (is (str/includes? (:output res) "Anchored docs")))))
  (testing "successful page read is preserved with warning when tab close fails"
    (with-redefs [mcp/connect (fn [_] {:ok? true})
                  mcp/call-tool (fn [_ name args]
                                  (case [name (:action args)]
                                    ["tabs" "list"] (response "ok")
                                    ["tabs" "new"] (response "opened page 9")
                                    ["read" nil] (response "Important page body")
                                    ["tabs" "close"] {:ok? false :error "transient close timeout"}))]
      (let [res (browser/read-page {:url "https://example.com/page"})]
        (is (= :ok (:status res)))
        (is (str/includes? (:output res) "Important page body"))
        (is (string? (:warning res)))))))
