(ns e2e.outbox-test
  "Pins the durable-outbox contract: persist-before-send, ack-on-success,
   truthful attempt counts, dead-letter on exhaustion, poison isolation,
   oldest-first order, and the drain batch cap. Transport is injected — the
   outbox owns queue mechanics, never HTTP."
  (:require [babashka.fs :as fs]
            [bb-agent.outbox :as outbox]
            [bb-agent.telegram-delivery :as delivery]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-root []
  (let [dir (str (fs/temp-dir) "/outbox-test-" (random-uuid))]
    (fs/create-dirs dir)
    dir))

(defn- cfg [root] {:outbox-root root :base-url "https://t.test" :token "T"})

(defn- intent [text]
  {:method "sendMessage" :params {:chat_id -1 :text text}
   :routing {:chat-id -1 :thread-id nil}})

(deftest enqueue-persist-ack-roundtrip
  (let [root (temp-root)
        id (outbox/enqueue! (cfg root) (intent "hello"))]
    (is (= 1 (outbox/pending-count (cfg root))))
    (is (= "hello" (get-in (first (outbox/pending (cfg root)))
                           [:record :params :text])))
    (outbox/ack! (cfg root) id)
    (is (zero? (outbox/pending-count (cfg root))))
    (testing "ack is idempotent — a missing file is already acked"
      (outbox/ack! (cfg root) id)
      (is (zero? (outbox/pending-count (cfg root)))))))

(deftest drain-sends-oldest-first-and-acks
  (let [root (temp-root)
        c (cfg root)
        _ (outbox/enqueue! c (assoc (intent "first") :created/at "2026-01-01T00:00:00Z"))
        _ (outbox/enqueue! c (assoc (intent "second") :created/at "2026-01-02T00:00:00Z"))
        sent (atom [])
        result (outbox/drain! c (fn [r] (swap! sent conj (get-in r [:params :text]))))]
    (is (= ["first" "second"] @sent))
    (is (= {:sent 2 :deferred 0 :dead 0 :poison 0} result))
    (is (zero? (outbox/pending-count c)))))

(deftest drain-failure-marks-then-dead-letters
  (let [root (temp-root)
        c (cfg root)
        id (outbox/enqueue! c (intent "doomed"))
        boom (fn [_] (throw (ex-info "provider down" {})))]
    (dotimes [n outbox/max-attempts]
      (let [r (outbox/drain! c boom)]
        (if (< n (dec outbox/max-attempts))
          (is (= 1 (:deferred r)))
          (is (= 1 (:dead r))))))
    (testing "the file left pending for dead-letter inspection, then moved"
      (is (zero? (outbox/pending-count c)))
      (is (fs/exists? (str root "/dead/" id ".edn"))))))

(deftest poison-bytes-dead-letter-on-first-sight
  (let [root (temp-root)
        c (cfg root)]
    (spit (str root "/broken.edn") "{not edn at all")
    (let [result (outbox/drain! c (fn [_] (throw (ex-info "must not run" {}))))]
      (is (= 1 (:poison result)))
      (is (zero? (outbox/pending-count c)))
      (is (fs/exists? (str root "/dead/broken.edn"))))))

(deftest drain-respects-batch-cap
  (let [root (temp-root)
        c (cfg root)]
    (dotimes [n 25]
      (outbox/enqueue! c (assoc (intent (str "m" n))
                                :created/at (format "2026-01-%02dT00:00:00Z" (inc n)))))
    (let [attempts (atom 0)
          result (outbox/drain! c (fn [_] (swap! attempts inc)))]
      (is (= outbox/drain-batch-size @attempts))
      (is (= outbox/drain-batch-size (:sent result)))
      (is (= 5 (outbox/pending-count c))))))

(deftest send-failure-leaves-intent-for-drain
  (let [root (temp-root)
        c (cfg root)]
    (testing "a dying transport persists the intent with a truthful count"
      (is (thrown? Exception
                   (delivery/send-message!
                    c -1001 "lost?"
                    {:transport (fn [_ _] (throw (ex-info "boom" {})))
                     :sleep-fn (fn [_])})))
      (let [[{:keys [record]} & more] (outbox/pending c)]
        (is (nil? more))
        (is (= "sendMessage" (:method record)))
        (is (= 1 (:attempts record)))
        (is (= "lost?" (get-in record [:params :text])))))
    (testing "a later drain delivers it through a healthy transport"
      (let [calls (atom [])]
        (outbox/drain! c (fn [record]
                           (swap! calls conj (get-in record [:params :text]))))
        (is (= ["lost?"] @calls))
        (is (zero? (outbox/pending-count c)))))))

(deftest successful-send-leaves-nothing-behind
  (let [root (temp-root)
        c (cfg root)]
    (delivery/send-message!
     c -1001 "clean"
     {:transport (fn [_ _] {:status 200
                            :body (json/generate-string
                                   {:ok true :result {:message_id 1}})})
      :sleep-fn (fn [_])})
    (is (zero? (outbox/pending-count c)))))

(deftest mismatched-filename-cannot-strand-a-record
  (testing "a foreign file whose name disagrees with its :id is acked by
            FILE on success — a recomputed path would strand it into
            endless redelivery"
    (let [root (temp-root)
          c (cfg root)]
      (spit (str root "/foreign-name.edn")
            (pr-str {:id "different-id" :method "sendMessage" :attempts 0
                     :created/at "2026-01-01T00:00:00Z"
                     :params {:chat_id -1 :text "foreign"}
                     :routing {:chat-id -1}}))
      (let [result (outbox/drain! c (fn [_] :ok))]
        (is (= 1 (:sent result)))
        (is (zero? (outbox/pending-count c)))))))
