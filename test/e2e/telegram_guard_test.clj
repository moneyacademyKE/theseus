(ns e2e.telegram-guard-test
  (:require [clojure.test :as t :refer [deftest is]]
            [bb-agent.telegram-guard :as guard]))

(deftest allowed-id-passes
  (let [cfg {:telegram {:allowed-chat-ids [111 222]}}]
    (is (true? (guard/allowed? cfg 111)))
    (is (true? (guard/allowed? cfg 222)))))

(deftest disallowed-id-denied
  (let [cfg {:telegram {:allowed-chat-ids [111 222]}}]
    (is (false? (guard/allowed? cfg 999)))
    (is (false? (guard/allowed? cfg nil)))))

(deftest missing-config-denies
  (is (false? (guard/allowed? {} 111)))
  (is (false? (guard/allowed? {:telegram {}} 111))))

(deftest empty-list-denies
  (is (false? (guard/allowed? {:telegram {:allowed-chat-ids []}} 111))))

;; ---------------------------------------------------------------------------
;; Authorization matrix for message-allowed? / callback-allowed?
;; Characterization tests for the single privilege gate. Backing receipt:
;; I.T directive 2026-09-06 ("all members same privilege as owner") — these
;; prove what each tier actually unlocks, so parity claims stay checked.

(def cfg-open
  {:telegram {:allowed-user-ids [1]
              :groups {-100555 {:open true}}}})

(defn- group-msg
  ([user-id chat-id] (group-msg user-id chat-id false))
  ([user-id chat-id bot?]
   {:from {:id user-id :is_bot (boolean bot?)}
    :chat {:id chat-id :type "supergroup"}}))

(defn- dm-msg [user-id]
  {:from {:id user-id :is_bot false}
   :chat {:id user-id :type "private"}})

(deftest open-group-admits-any-human
  (is (true? (guard/message-allowed? cfg-open (group-msg 999 -100555)))))

(deftest closed-group-denies-strangers
  (is (false? (guard/message-allowed?
               {:telegram {:allowed-user-ids [1]
                           :groups {-100555 {}}}}
               (group-msg 999 -100555)))))

(deftest open-mode-inert-without-operators
  (is (false? (guard/message-allowed?
               {:telegram {:groups {-100555 {:open true}}}}
               (group-msg 999 -100555)))))

(deftest bots-never-pass-any-gate
  (is (false? (guard/message-allowed? cfg-open (group-msg 999 -100555 true))))
  (is (false? (guard/message-allowed?
               cfg-open
               (assoc (group-msg 1 -100555) :from {:id 1 :is_bot true})))))

(deftest global-user-passes-anywhere
  (is (true? (guard/message-allowed? cfg-open (group-msg 1 -100555))))
  (is (true? (guard/message-allowed? cfg-open (group-msg 1 -100777))))
  (is (true? (guard/message-allowed? cfg-open (dm-msg 1)))))

(deftest group-allowlist-grants-that-group-only
  (let [cfg {:telegram {:allowed-user-ids [1]
                        :groups {-100555 {:allowed-user-ids [7]}}}}]
    (is (true? (guard/message-allowed? cfg (group-msg 7 -100555))))
    (is (false? (guard/message-allowed? cfg (group-msg 7 -100777))))))

(deftest dm-stranger-denied-even-with-open-groups
  (is (false? (guard/message-allowed? cfg-open (dm-msg 999)))))

(deftest callback-authorized-by-clicker
  (let [q {:from {:id 999 :is_bot false}
           :message {:chat {:id -100555 :type "supergroup"}}}]
    (is (true? (guard/callback-allowed? cfg-open q)))
    (is (false? (guard/callback-allowed?
                 cfg-open (assoc-in q [:from :is_bot] true))))))
