(ns bb-agent.digest-test
  "V7 pins (bk-6476): the daily digest fires at/after 08:00 local, once
   per calendar day, through the durable outbox — and never twice."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.digest :as digest]
            [bb-agent.outbox :as outbox]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn with-tmp-home [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "digest-test"}))]
    (binding [*tmp* tmp]
      (with-redefs [config/home (constantly tmp)]
        (f)))))

(use-fixtures :each with-tmp-home)

(def cfg {:notify {:chat-id 1608111860} :token "T" :base-url "http://127.0.0.1:1"})

(deftest due-pure-test
  (testing "fires at/after 08:00 local, once per day; before 08:00 never"
    (is (true? (digest/due? {:today "2026-09-10" :hour 8} {:last "2026-09-09"})))
    (is (true? (digest/due? {:today "2026-09-10" :hour 23} {:last "2026-09-09"})))
    (is (false? (digest/due? {:today "2026-09-10" :hour 7} {:last "2026-09-09"}))
        "before 08:00 stays quiet even on a new day")
    (is (false? (digest/due? {:today "2026-09-10" :hour 9} {:last "2026-09-10"}))
        "same day = already sent")
    (is (true? (digest/due? {:today "2026-09-10" :hour 9} {}))
        "no stamp = never sent = due")))

(deftest render-test
  (testing "the digest body carries the real ledger keys"
    (let [body (digest/render {:usage {:tokens/total 1800000 :usage/events 152
                                       :cost/estimate-usd 2.5}
                               :outbox {:outbox/pending 1 :outbox/dead 1}
                               :goals {:goal/workspaces 16 :fulfilled 6
                                       :stalled 3 :failed 0 :goal/active 1}})]
      (is (str/includes? body "daily digest"))
      (is (str/includes? body "1800000"))
      (is (str/includes? body "~$2.50"))
      (is (str/includes? body "1 pending"))
      (is (str/includes? body "6 fulfilled"))
      (is (str/includes? body "1 active")))))

(deftest maybe-digest-test
  (testing "first due call enqueues through the durable outbox; second is a no-op"
    (let [id (digest/maybe-digest! cfg {:today "2026-09-10" :hour 9})]
      (is (some? id) "returns the outbox id")
      (is (= 1 (outbox/pending-count {:telegram cfg})) "the digest is durable state")
      (is (nil? (digest/maybe-digest! cfg {:today "2026-09-10" :hour 10}))
          "same day: silence, not a second digest")
      (is (= 1 (outbox/pending-count {:telegram cfg}))))
    (testing "before 08:00 nothing fires even on a new day"
      (is (nil? (digest/maybe-digest! cfg {:today "2026-09-11" :hour 6})))
      (is (= 1 (outbox/pending-count {:telegram cfg}))))
    (testing "next day fires again"
      (is (some? (digest/maybe-digest! cfg {:today "2026-09-11" :hour 8})))
      (is (= 2 (outbox/pending-count {:telegram cfg}))))
    (testing "no notify chat-id → no enqueue but the day is stamped"
      (is (nil? (digest/maybe-digest! {:notify nil} {:today "2026-09-12" :hour 9})))
      (is (nil? (digest/maybe-digest! {:notify nil} {:today "2026-09-12" :hour 10}))
          "a stamped day without a destination does not retry"))))

(deftest digest-body-is-honest-test
  (testing "the rendered body renders from live ledger data (stats/summary)"
    (digest/maybe-digest! cfg {:today "2026-09-10" :hour 9})
    (let [wrapped (first (outbox/pending {:telegram cfg}))
          text (get-in wrapped [:record :params :text])]
      (is (some? text))
      (is (str/includes? text "Theseus daily digest"))
      (is (str/includes? text "goals:")))))
