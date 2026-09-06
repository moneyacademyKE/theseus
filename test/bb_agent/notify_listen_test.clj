(ns bb-agent.notify-listen-test
  (:require [babashka.http-client :as http]
            [bb-agent.notify-listen :as nl]
            [clojure.test :as t :refer [deftest is testing]]))

(deftest format-page-shape
  (let [page (nl/format-page {:name "dogfood" :reason "budget-exhausted"
                              :iterations 12
                              :bundle-path "/w/logs/halt-bundle.edn"
                              :ts "2026-09-06T07:00:00Z"})]
    (is (re-find #"axiom halt — dogfood" page))
    (is (re-find #"reason: budget-exhausted" page))
    (is (re-find #"iterations: 12" page))
    (is (re-find #"/w/logs/halt-bundle.edn" page))
    (is (re-find #"ts: 2026-09-06" page))))

(deftest format-page-defaults
  (let [page (nl/format-page {})]
    (is (re-find #"<unnamed>" page))
    (is (re-find #"reason: unknown" page))
    (is (re-find #"iterations: \?" page))
    (is (re-find #"bundle: none" page))))

(defn- post
  "Drive the handler with a raw string body, like httpkit would."
  [handler s]
  (handler {:body (java.io.ByteArrayInputStream. (.getBytes s))}))

(deftest handler-pages-and-acks
  (let [sent    (atom [])
        handler (nl/make-handler #(swap! sent conj %))]
    (testing "a halt map is formatted, sent, acked"
      (let [res (post handler (pr-str {:name "x" :reason "stall" :iterations 3}))]
        (is (= 200 (:status res)))
        (is (= "paged" (:body res)))
        (is (= 1 (count @sent)))
        (is (re-find #"axiom halt — x" (first @sent)))))
    (testing "malformed EDN is rejected without paging"
      (let [res (post handler "not edn {{{")]
        (is (= 400 (:status res)))
        (is (= 1 (count @sent)))))))

(deftest handler-rejects-non-map
  (let [handler (nl/make-handler (constantly :sent))]
    (is (= 400 (:status (post handler "42"))))))

(deftest server-roundtrip
  (testing "a real POST to a real localhost socket pages and acks"
    (let [sent (atom [])
          stop (nl/start! #(swap! sent conj %) 17787)]
      (try
        (let [res (http/post "http://127.0.0.1:17787/halt"
                             {:headers {"Content-Type" "application/edn"}
                              :body (pr-str {:name "roundtrip"
                                             :reason "test"
                                             :iterations 1})})]
          (is (= 200 (:status res)))
          (is (= "paged" (:body res)))
          (is (= 1 (count @sent)))
          (is (re-find #"roundtrip" (first @sent))))
        (finally (stop))))))
