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

(def ^:private test-secret "test-secret-hmac")

(defn- hmac
  [secret s]
  (require '[pod.babashka.buddy.core.mac :as mac])
  (let [mac (resolve 'pod.babashka.buddy.core.mac/hash)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                    (mac s {:key (.getBytes secret) :alg :hmac :sha :256})))))

(defn- post-signed
  [handler s sig]
  (handler {:body (java.io.ByteArrayInputStream. (.getBytes s))
            :headers {"x-signature" sig}}))

(deftest hmac-signature-gate
  (let [sent    (atom [])
        handler (nl/make-handler #(swap! sent conj %) {:hmac-secret test-secret})
        halt    (pr-str {:name "signed" :reason "x" :iterations 1})]
    (testing "a valid signature pages"
      (let [res (post-signed handler halt (hmac test-secret halt))]
        (is (= 200 (:status res)))
        (is (= "paged" (:body res)))
        (is (= 1 (count @sent)))))
    (testing "a tampered signature is 403 without paging"
      (let [res (post-signed handler halt (hmac test-secret (str halt "tamper")))]
        (is (= 403 (:status res)))
        (is (= 1 (count @sent)))))
    (testing "a missing signature is 403"
      (let [res (post handler halt)]
        (is (= 403 (:status res)))
        (is (= 1 (count @sent)))))
    (testing "garbage signature is 403"
      (let [res (post-signed handler halt "zz-nothex")]
        (is (= 403 (:status res)))
        (is (= 1 (count @sent)))))))

(deftest hmac-back-compat-without-secret
  (let [sent (atom [])
        handler (nl/make-handler #(swap! sent conj %))]
    (is (= 200 (:status (post handler (pr-str {:name "y"})))))
    (is (= 1 (count @sent)))))
