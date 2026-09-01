(ns e2e.telegram-delivery-test
  (:require [bb-agent.telegram-delivery :as delivery]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(def telegram-config
  {:base-url "https://telegram.test"
   :token "TESTTOKEN"})

(defn- response
  [status body]
  {:status status
   :body (if (string? body) body (json/generate-string body))})

(defn- scripted-transport
  [responses calls]
  (fn [url request]
    (swap! calls conj {:url url :request request})
    (let [next-response (first @responses)]
      (swap! responses #(vec (rest %)))
      next-response)))

(defn- request-bodies
  [calls]
  (mapv #(json/parse-string (get-in % [:request :body]) keyword) @calls))

(deftest retry-after-is-bounded-and-routing-is-stable
  (testing "a short 429 waits once and then delivers"
    (let [responses (atom [(response 429 {:ok false
                                           :error_code 429
                                           :description "Too Many Requests"
                                           :parameters {:retry_after 2}})
                           (response 200 {:ok true :result {:message_id 9}})])
          calls (atom [])
          sleeps (atom [])
          result (delivery/send-message!
                  telegram-config -1001 "hello"
                  {:thread-id 4721
                   :reply-to-message-id 88
                   :transport (scripted-transport responses calls)
                   :sleep-fn #(swap! sleeps conj %)})
          bodies (request-bodies calls)]
      (is (= 2 (:attempts result)))
      (is (= [2000] @sleeps))
      (is (= 2 (count bodies)))
      (is (every? #(= 4721 (:message_thread_id %)) bodies))
      (is (every? #(= {:message_id 88} (:reply_parameters %)) bodies))))

  (testing "large windows are clamped and attempts terminate"
    (let [rate-limit (response 429 {:ok false
                                    :error_code 429
                                    :description "flood limited"
                                    :parameters {:retry_after 90}})
          responses (atom [rate-limit rate-limit rate-limit])
          calls (atom [])
          sleeps (atom [])]
      (try
        (delivery/send-message!
         telegram-config 42 "hello"
         {:transport (scripted-transport responses calls)
          :sleep-fn #(swap! sleeps conj %)})
        (is false "repeated rate limits must fail after bounded attempts")
        (catch Exception error
          (is (= :rate-limited (:failure/kind (ex-data error))))
          (is (= 3 (:attempts (ex-data error))))))
      (is (= [30000 30000] @sleeps))
      (is (= 3 (count @calls))))))

(deftest non-rate-limit-and-malformed-responses-fail-without-retry
  (doseq [[label terminal]
          [["HTTP rejection" (response 500 {:ok false :description "server error"})]
           ["Bot API rejection" (response 200 {:ok false
                                                :error_code 403
                                                :description "forbidden"})]
           ["malformed body" (response 200 "not-json")]]]
    (testing label
      (let [responses (atom [terminal])
            calls (atom [])
            sleeps (atom [])]
        (try
          (delivery/send-message!
           telegram-config 42 "hello"
           {:transport (scripted-transport responses calls)
            :sleep-fn #(swap! sleeps conj %)})
          (is false "terminal response must surface as an error")
          (catch Exception error
            (is (= :terminal (:failure/kind (ex-data error))))))
        (is (= 1 (count @calls)))
        (is (empty? @sleeps))))))

(deftest rejected-html-falls-back-once-to-plain-through-the-same-route
  (let [responses (atom [(response 400 {:ok false
                                         :error_code 400
                                         :description "Bad Request: can't parse entities"})
                         (response 200 {:ok true :result {:message_id 10}})])
        calls (atom [])
        results (delivery/send-html!
                 telegram-config -1001
                 "<b>bold</b> &amp; <a href=\"https://example.com\">link</a>"
                 {:thread-id 4721
                  :reply-to-message-id 88
                  :transport (scripted-transport responses calls)
                  :sleep-fn (fn [_])})
        [html plain] (request-bodies calls)]
    (is (= 1 (count results)))
    (is (= "HTML" (:parse_mode html)))
    (is (nil? (:parse_mode plain)))
    (is (= "bold & link (https://example.com)" (:text plain)))
    (is (= (select-keys html [:chat_id :message_thread_id :reply_parameters])
           (select-keys plain [:chat_id :message_thread_id :reply_parameters])))))

(deftest non-markup-failure-does-not-trigger-plain-fallback
  (let [responses (atom [(response 500 {:ok false :description "server error"})])
        calls (atom [])]
    (try
      (delivery/send-html!
       telegram-config 42 "<b>hello</b>"
       {:transport (scripted-transport responses calls)
        :sleep-fn (fn [_])})
      (is false "server failures must not masquerade as markup failures")
      (catch Exception error
        (is (= :terminal (:failure/kind (ex-data error))))))
    (is (= 1 (count @calls)))))

(deftest a-terminal-chunk-failure-stops-the-sequence
  (let [responses (atom [(response 500 {:ok false :description "server error"})])
        calls (atom [])]
    (try
      (delivery/send-html!
       telegram-config 42 (apply str (repeat 5000 "x"))
       {:transport (scripted-transport responses calls)
        :sleep-fn (fn [_])})
      (is false "later chunks must not send after terminal failure")
      (catch Exception error
        (is (:telegram/delivery? (ex-data error)))))
    (is (= 1 (count @calls)))))
