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

(deftest render-flow-stays-under-telegram-limit
  (let [entries (vec (for [i (range 300)]
                       {:name (str "tool-" i) :context (str "/some/path/" i ".clj") :status :ok}))
        running (flow/render-flow {:entries entries :started-ms (System/currentTimeMillis)})
        settled (flow/render-flow {:entries entries :started-ms (System/currentTimeMillis)
                                   :settled {:ok? true}})]
    (is (<= (count running) 4096) "a 300-entry live render fits Telegram's limit")
    (is (<= (count settled) 4096) "a 300-entry settled render fits Telegram's limit")
    (is (str/includes? running "earlier calls") "the dropped middle is accounted for")
    (is (str/includes? running "tool-299") "the newest entry survives truncation")
    (is (str/includes? running "tool-0") "the origin entry survives truncation")
    (is (str/includes? settled "✅ Finished (300 tool calls") "the settled footer survives truncation")
    (is (str/includes? settled "300 tool calls") "the footer reports the TRUE count, not the visible one")))

(deftest render-flow-compresses-before-truncating
  (let [entries (vec (concat (repeat 80 {:name "file-read" :context "/src/a.clj" :status :ok})
                             (repeat 40 {:name "shell" :context "bb test" :status :ok})
                             [{:name "file-write" :context "/src/x.clj" :status :failed}]
                             (repeat 80 {:name "file-edit" :context "/src/b.clj" :status :ok})))
        html (flow/render-flow {:entries entries :started-ms (System/currentTimeMillis)})]
    (is (<= (count html) 4096) "201 entries compress under the limit")
    (is (not (str/includes? html "earlier calls")) "compression fit — nothing is dropped")
    (is (str/includes? html "file-read ×80") "the run collapses with its true count")
    (is (str/includes? html "shell ×40"))
    (is (str/includes? html "file-edit ×80"))
    (is (str/includes? html "<b>❌ file-write</b> <code>/src/x.clj</code>")
        "a failure inside the runs keeps its own line and context")
    (is (< (.indexOf html "file-read ×80")
           (.indexOf html "shell ×40")
           (.indexOf html "file-write")
           (.indexOf html "file-edit ×80"))
        "the run sequence is preserved in order")
    (is (str/includes? html "201 tool calls") "the footer counts calls, not lines")))

(deftest render-flow-keeps-detail-when-it-fits
  (let [entries [{:name "file-read" :context "/a.clj" :status :ok}
                 {:name "file-read" :context "/b.clj" :status :ok}]
        html (flow/render-flow {:entries entries :started-ms (System/currentTimeMillis)})]
    (is (str/includes? html "/a.clj") "short turns keep every context")
    (is (str/includes? html "/b.clj"))
    (is (not (str/includes? html "×2")) "no compression without pressure")))
