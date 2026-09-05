(ns e2e.telegram-edits-test
  "Gap 1 of bk-2c7f: edited-message handling. An edit to a message the bot
   already answered must re-run the turn and update the prior reply IN
   PLACE — a stale answer sitting next to an edited question is a lie.
   Edits to messages without a prior reply stay context notes."
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

(defn- msg [id text]
  {:message_id id
   :from {:id owner-id :is_bot false :first_name "moe"}
   :chat {:id owner-id :type "private"}
   :text text})

(defn- run-edit-polls
  "Drive three poll-once! rounds against a fake Bot API:
   round 1 delivers a message, round 2 an edit of it (or of a stranger id),
   round 3 drains. Returns {:calls [...] :answers [...]}."
  [provider-impl {:keys [edit]}]
  (let [home (fs/create-temp-dir {:prefix "theseus-edits-"})
        port (free-port)
        calls (atom [])
        get-updates-count (atom 0)
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:id 8511646577 :username "eileenslybot" :is_bot true}})}
               "/botTESTTOKEN/getUpdates"
               (let [n (swap! get-updates-count inc)]
                 {:status 200 :headers {"content-type" "application/json"}
                  :body (json/generate-string
                         {:ok true
                          :result (cond
                                    (= n 1) [{:update_id 1 :message (msg 42 "what is 2+2")}]
                                    (= n 2) [{:update_id 2
                                              :edited_message (merge (msg (:id edit) (:text edit))
                                                                     {:edit_date 1000})}]
                                    :else [])})})
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string
                       {:ok true :result {:message_id 90}})})))
         {:port port})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :policy {:enabled true}
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-user-ids [owner-id]}}))
      (let [answers (atom [])
            provider-wrapped (fn [cfg req]
                               (let [resp (provider-impl cfg req)]
                                 (swap! answers conj (:content resp))
                                 resp))]
        (with-redefs [config/home (fn [] (str home))
                      provider/complete provider-wrapped]
          (telegram/poll-once!)
          (telegram/poll-once!)
          (telegram/poll-once!))
        {:calls @calls :answers @answers})
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(defn- parsed-calls
  [calls needle]
  (->> calls
       (filter (fn [c] (str/includes? (:uri c) needle)))
       (map (fn [c] (json/parse-string (:body c) keyword)))))

(deftest edited-answered-message-reruns-and-edits-reply-in-place
  (let [rounds (atom 0)
        provider-impl (fn [_ _]
                        (if (zero? @rounds)
                          (do (swap! rounds inc)
                              {:role :assistant :content "the answer to 2+2"})
                          {:role :assistant :content "the edited answer to 2+3"}))
        {:keys [calls answers]}
        (run-edit-polls provider-impl {:edit {:id 42 :text "actually what is 2+3"}})
        edits (parsed-calls calls "editMessageText")
        sends (parsed-calls calls "sendMessage")]
    (testing "the original reply was sent once"
      (is (some (fn [s] (str/includes? (str (:text s)) "the answer to 2+2")) sends)))
    (testing "the re-run saw the edited text"
      (is (some (fn [a] (and a (str/includes? a "2+3"))) answers)))
    (testing "the prior reply (message 90) is edited in place with the new answer"
      (is (some (fn [e]
                  (and (= 90 (long (:message_id e)))
                       (= owner-id (long (:chat_id e)))
                       (str/includes? (str (:text e)) "the edited answer to 2+3")))
                edits)))))

(deftest edit-without-prior-reply-stays-a-context-note
  (let [rounds (atom 0)
        provider-impl (fn [_ _]
                        (if (zero? @rounds)
                          (do (swap! rounds inc)
                              {:role :assistant :content "first answer"})
                          {:role :assistant :content "should not run"}))
        ;; Edit message id 999 — no reply was ever recorded for it.
        {:keys [calls answers]}
        (run-edit-polls provider-impl {:edit {:id 999 :text "edit of an unanswered message"}})
        edits (parsed-calls calls "editMessageText")]
    (testing "no in-place edit is attempted"
      (is (empty? edits)))
    (testing "no turn ran for the orphan edit"
      (is (not (some (fn [a] (and a (str/includes? a "should not run"))) answers))))))
