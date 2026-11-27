(ns bb-agent.telegram-auto-register-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-auto-register :as auto-reg]
            [bb-agent.telegram-group :as group]
            [clojure.test :refer [deftest is testing]]))

(def ^:private base-cfg
  {:provider :infered-local
   :model "infered/auto-budget"
   :telegram {:token "tok"
              :allowed-user-ids [1608111860 293040805]
              :allowed-chat-ids [1608111860 293040805]
              :respond-to :mention
              :groups {-1003995594829 {:allowed-user-ids [1608111860 293040805]
                                       :open true
                                       :respond-to :all}}}})

(deftest eligible-admin-promotion-predicate-test
  (testing "accepts group/supergroup/channel where an existing allowed user promotes bot to admin"
    (is (true? (auto-reg/eligible-admin-event?
                base-cfg
                {:chat {:id -100999888777 :type "supergroup" :title "Ops"}
                 :from {:id 1608111860 :is_bot false}
                 :new_chat_member {:status "administrator"}})))
    (is (true? (auto-reg/eligible-admin-event?
                base-cfg
                {:chat {:id -100111222333 :type "channel" :title "Alerts"}
                 :from {:id 293040805 :is_bot false}
                 :new_chat_member {:status "administrator"}}))))

  (testing "rejects stranger promotion, non-admin status, private chats, or bot actors"
    (is (false? (auto-reg/eligible-admin-event?
                 base-cfg
                 {:chat {:id -100999888777 :type "supergroup"}
                  :from {:id 999999999 :is_bot false}
                  :new_chat_member {:status "administrator"}}))
        "stranger adding bot as admin must be refused")
    (is (false? (auto-reg/eligible-admin-event?
                 base-cfg
                 {:chat {:id -100999888777 :type "supergroup"}
                  :from {:id 1608111860 :is_bot false}
                  :new_chat_member {:status "member"}}))
        "plain member (non-admin) must not auto-register")
    (is (false? (auto-reg/eligible-admin-event?
                 base-cfg
                 {:chat {:id 1608111860 :type "private"}
                  :from {:id 1608111860 :is_bot false}
                  :new_chat_member {:status "administrator"}})))
    (is (false? (auto-reg/eligible-admin-event?
                 base-cfg
                 {:chat {:id -100999888777 :type "group"}
                  :from {:id 1608111860 :is_bot true}
                  :new_chat_member {:status "administrator"}})))))

(deftest register-group-persists-to-config-idempotently-test
  (let [home (fs/create-temp-dir {:prefix "theseus-autoreg-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (config/write-config! base-cfg)
        (let [res1 (auto-reg/handle-my-chat-member!
                    base-cfg
                    {:chat {:id -100777666555 :type "supergroup" :title "New HQ"}
                     :from {:id 1608111860 :is_bot false}
                     :new_chat_member {:status "administrator"}})
              reloaded (config/load-config)
              gcfg (group/group-config reloaded -100777666555)]
          (is (true? (:registered? res1)))
          (is (= -100777666555 (:chat-id res1)))
          (is (= [1608111860 293040805] (:allowed-user-ids gcfg)))
          (is (true? (:open gcfg)))
          (is (= :all (:respond-to gcfg)))
          ;; Second call is idempotent (already registered -> :already-registered? true)
          (let [res2 (auto-reg/handle-my-chat-member!
                      reloaded
                      {:chat {:id -100777666555 :type "supergroup" :title "New HQ"}
                       :from {:id 1608111860 :is_bot false}
                       :new_chat_member {:status "administrator"}})]
            (is (false? (:registered? res2)))
            (is (true? (:already-registered? res2))))))
      (finally
        (fs/delete-tree home)))))

(deftest maybe-register-existing-admin-group-on-message-test
  (let [home (fs/create-temp-dir {:prefix "theseus-autoreg-msg-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (config/write-config! base-cfg)
        (let [msg {:chat {:id -100555444333 :type "supergroup" :title "Pre-Added"}
                   :from {:id 1608111860 :is_bot false}
                   :text "hello sly"}
              cfg* (auto-reg/maybe-register-on-message!
                    base-cfg
                    {:id 8511646577}
                    msg
                    (fn [_chat-id _bot-id] {:status "administrator"}))]
          (is (some? (group/group-config cfg* -100555444333)))
          (is (= :all (:respond-to (group/group-config cfg* -100555444333))))
          (is (some? (group/group-config (config/load-config) -100555444333)))))
      (finally
        (fs/delete-tree home)))))
