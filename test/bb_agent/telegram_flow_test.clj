(ns bb-agent.telegram-flow-test
  (:require [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-flow :as flow]
            [bb-agent.telegram-presence :as presence]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def sent (atom []))

(defn fake-transport! [f]
  "Runs f with the Telegram transport faked: sends return message-id 4242,
   edits are recorded, typing is a no-op. The redefs must wrap f itself —
   a helper that redefs and returns would expire the bindings before the
   code under test runs."
  (reset! sent [])
  (with-redefs [delivery/send-message!
                (fn [_cfg _chat text _opts]
                  (swap! sent conj [:send text])
                  {:message-id 4242})
                delivery/edit-message-text!
                (fn [_cfg _chat mid text _opts]
                  (swap! sent conj [:edit mid text])
                  {:ok true})
                presence/typing! (fn [& _] nil)]
    (f)))

(deftest with-flow-streams-and-settles
  (fake-transport!
   (fn []
     (let [reply (flow/with-flow! {:token "t"} 1 22
                   (fn [emit]
                     (emit {:status :tool/call :tool "file-write" :args {:path "src/x.clj"}})
                     (emit {:status :tool/call :tool "shell" :args {:cmd "bb test"}})
                     (emit {:status :tool/done :tool "file-write" :args {:ok? true}})
                     (emit {:status :tool/done :tool "shell" :args {:ok? true}})
                     "launched"))]
       (is (= "launched" reply) "the thunk's reply passes through")
       (is (= :send (ffirst @sent)) "first entry SENDS the flow message")
       (is (= 4242 (-> @sent (nth 1) second)) "later entries EDIT the same message")
       (is (str/includes? (-> @sent last last) "✅ Finished") "settle stamps the verdict")))))

(deftest with-flow-settles-failed
  (fake-transport!
   (fn []
     (let [reply (try
                   (flow/with-flow! {:token "t"} 1 22
                     (fn [emit]
                       (emit {:status :tool/call :tool "shell" :args {:cmd "boom"}})
                       (emit {:status :tool/call :tool "shell" :args {:cmd "boom 2"}})
                       (throw (ex-info "kaboom" {}))))
                   (catch Exception _ :threw))]
       (is (= :threw reply) "the error still propagates to the caller")
       (is (str/includes? (-> @sent last last) "❌ Failed") "failure settles the footer")))))
