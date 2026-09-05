(ns e2e.telegram-turn-status-test
  "Turn-status UX: the channel must see a turn's process, not just its
   result. These tests assert the ordered message sequence a user
   receives over time:

   - each tool call surfaces as one compact, redacted status line before
     the final reply;
   - the typing indicator renews for the turn's whole duration
     (Telegram's TTL is ~5s, so a one-shot dies on slow turns);
   - a turn failure produces an error reply and swaps the ack reaction
     instead of leaving silence;
   - an expired approval removes its inline keyboard.

   The fake Bot API records every call in order; provider/complete is
   redefed for deterministic rounds."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.provider :as provider]
            [bb-agent.telegram :as telegram]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def owner-id 1608111860)

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- dm-update [text]
  [{:update_id 1
    :message {:message_id 42
              :from {:id owner-id :is_bot false :first_name "moe"}
              :chat {:id owner-id :type "private"}
              :text text}}])

(defn- run-status-poll
  "Drive one poll-once! against a fake Bot API that records every call in
   order. provider-impl replaces provider/complete for deterministic
   rounds. telegram-overrides merge into the :telegram config map.
   rules-content, when given, is written to brain/rules.clj.
   Returns {:result poll-result :calls [ordered calls]}."
  [provider-impl telegram-overrides rules-content]
  (let [home (fs/create-temp-dir {:prefix "theseus-turn-status-"})
        port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true
                        :result {:id 8511646577 :username "eileenslybot"
                                 :is_bot true}})}

               "/botTESTTOKEN/getUpdates"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result (dm-update "run the probe")})}

               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:message_id 90}})})))
         {:port port})]
    (try
      (when rules-content
        (fs/create-dirs (fs/path home "brain"))
        (spit (str (fs/path home "brain" "rules.clj")) rules-content))
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :policy {:enabled true}
                     :telegram (merge {:token "TESTTOKEN"
                                       :base-url (str "http://127.0.0.1:" port)
                                       :allowed-user-ids [owner-id]}
                                      telegram-overrides)}))
      (let [result (with-redefs [config/home (fn [] (str home))
                                 provider/complete provider-impl]
                     (telegram/poll-once!))]
        {:result result :calls @calls})
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(defn- parsed-sends
  "Ordered sendMessage bodies, parsed."
  [calls]
  (->> calls
       (filter (fn [c] (str/includes? (:uri c) "/sendMessage")))
       (map (fn [c] (json/parse-string (:body c) keyword)))))

(defn- parsed-calls
  [calls needle]
  (->> calls
       (filter (fn [c] (str/includes? (:uri c) needle)))
       (map (fn [c] (json/parse-string (:body c) keyword)))))

(deftest tool-calls-surface-as-compact-redacted-status-lines
  (let [rounds (atom 0)
        provider-impl
        (fn [_ _]
          (if (zero? @rounds)
            (do (swap! rounds inc)
                {:role :assistant
                 :tool/requests [{:tool/id "t1"
                                  :tool/name "shell"
                                  :tool/args
                                  {:cmd (str "echo status-probe "
                                             "8511646577:AAfakeTOKENfakeTOKENfakeTOKENfake12345")}}]})
            {:role :assistant :content "done with the probe"}))
        {:keys [calls]} (run-status-poll provider-impl {}
                                         "{:rules [{:name :allow-echo
                                                     :pred (fn [tool args]
                                                             (and (= tool \"shell\")
                                                                  (re-find #\"^echo\"
                                                                           (str (:cmd args)))))
                                                     :decision :allow}]}")
        sends (parsed-sends calls)
        texts (map :text sends)
        status-idx (first (keep-indexed (fn [i t]
                                          (when (and t (str/includes? t "🔧"))
                                            i))
                                        texts))
        final-idx (first (keep-indexed (fn [i t]
                                         (when (and t (str/includes? t "done with the probe"))
                                           i))
                                       texts))]
    (testing "a compact status line precedes the final reply"
      (is (some? status-idx))
      (is (some? final-idx))
      (when (and status-idx final-idx)
        (is (< status-idx final-idx))
        (let [line (nth texts status-idx)]
          (is (str/includes? line "echo status-probe"))
          (is (<= (count line) 120) "status lines stay compact"))))
    (testing "tool args are redacted in the status line"
      (when status-idx
        (is (not (str/includes? (nth texts status-idx) "AAfakeTOKEN")))))))

(deftest typing-renews-for-the-turns-duration
  (let [provider-impl (fn [_ _]
                        (Thread/sleep 350)
                        {:role :assistant :content "slow answer"})
        {:keys [calls]} (run-status-poll provider-impl
                                         {:typing-heartbeat-ms 50}
                                         nil)
        typings (filter (fn [c] (str/includes? (:uri c) "sendChatAction"))
                        calls)]
    (is (>= (count typings) 2)
        "a turn longer than Telegram's typing TTL renews the indicator")))

(deftest turn-failure-surfaces-an-error-reply-and-reaction-swap
  (let [provider-impl (fn [_ _] (throw (ex-info "provider exploded" {})))
        {:keys [result calls]} (run-status-poll provider-impl {} nil)
        texts (map :text (parsed-sends calls))
        reactions (parsed-calls calls "setMessageReaction")
        emojis (map (fn [r] (get-in r [:reaction 0 :emoji])) reactions)]
    (is (= 1 (:updates result)) "a dead turn does not kill the poll")
    (is (some (fn [t] (and t
                           (str/includes? t "Turn failed")
                           (str/includes? t "provider exploded")))
              texts)
        "the user hears about the failure")
    (is (some (fn [e] (= "🤯" e)) emojis)
        "the ack reaction swaps to a failure signal")))

(deftest expired-approval-drops-its-keyboard
  (let [provider-impl
        (fn [_ _]
          {:role :assistant
           :tool/requests [{:tool/id "t1"
                            :tool/name "shell"
                            :tool/args {:cmd "definitely-not-allowed"}}]})
        {:keys [calls]} (run-status-poll provider-impl
                                         {:approval-timeout-ms 400}
                                         nil)
        edits (parsed-calls calls "editMessageText")
        edit-texts (map :text edits)
        texts (map :text (parsed-sends calls))]
    (is (some (fn [t] (and t (str/includes? t "approval"))) texts)
        "the approval prompt was sent")
    (is (some (fn [t] (and t (str/includes? t "xpired"))) edit-texts)
        "the expired prompt's keyboard is removed")
    (is (some (fn [t] (and t (str/includes? t "denied"))) texts)
        "the denial is the final receipt")))
