(ns e2e.telegram-group-context-test
  "Group turns must see recent group history (the rolling buffer), DM turns
   must not, and non-responded group messages still land in the buffer."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram :as telegram]
            [bb-agent.telegram-group-context :as gctx]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- bot-info []
  {:id 8511646577 :username "eileenslybot" :is_bot true})

(def group-id -1001)
(def it-id 7)

(defn- group-message [update-id message-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id it-id :is_bot false :first_name "I.T"}
             :chat {:id group-id :type "supergroup"}
             :text text}})

(defn- dm-message [update-id message-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id it-id :is_bot false :first_name "I.T"}
             :chat {:id it-id :type "private"}
             :text text}})

(defn- run-poll
  "Boot a fake Bot API with the given updates, run one poll against a temp
   home, return {:calls :home :result}."
  [updates]
  (let [home (fs/create-temp-dir {:prefix "theseus-gctx-"})
        port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/getMe"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result (bot-info)})}
               "/botTESTTOKEN/getUpdates"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result updates})}
               "/botTESTTOKEN/sendMessage"
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 90}})}
               {:status 200 :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result true})})))
         {:port port})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :react-ack false
                                :typing-indicator false
                                :allowed-user-ids [it-id]
                                :groups {group-id {:respond-to :mention
                                                   :allow-user-ids [it-id]}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))]
        {:calls @calls :home (str home) :result result})
      (finally
        (stop-server)))))

(defn- session-inputs
  "All :user/input values recorded in the poll's session files."
  [home]
  (->> (fs/glob (fs/path home "state" "sessions") "*.edn")
       (filter fs/regular-file?)
       (mapcat (fn [f] (edn/read-string (slurp (str f)))))
       (map :user/input)))

(deftest group-turn-sees-recent-history
  (let [{:keys [home]} (run-poll [(group-message 1 100 "the deploy is on friday")
                                  (group-message 2 101 "@eileenslybot what day is the deploy?")])
        inputs (session-inputs home)
        group-input (first (filter #(str/includes? (or % "") "what day is the deploy?") inputs))]
    (try
      (is (some? group-input) "the mentioned message ran a turn")
      (is (str/includes? group-input "[Recent group history") "history block present")
      (is (str/includes? group-input "the deploy is on friday") "prior message visible")
      (is (str/includes? group-input "I.T") "sender names included")
      (finally
        (fs/delete-tree home)))))

(deftest dm-turns-get-no-history
  (let [{:keys [home]} (run-poll [(dm-message 3 200 "hello bot")])
        inputs (session-inputs home)]
    (try
      (is (= 1 (count inputs)) "one turn ran")
      (is (not (str/includes? (first inputs) "[Recent group history"))
          "DM input has no history block")
      (finally
        (fs/delete-tree home)))))

(deftest non-responded-group-messages-still-record
  (let [{:keys [home calls result]} (run-poll [(group-message 4 300 "just chatter, no mention")])]
    (try
      (is (= 1 (:updates result)))
      (is (empty? (filter #(str/includes? (:uri %) "sendMessage") calls))
          "no reply sent for a non-mention")
      (is (= "just chatter, no mention"
             (:text (first (with-redefs [config/home (fn [] home)]
                             (gctx/recent group-id 30)))))
          "message recorded for future turns")
      (finally
        (fs/delete-tree home)))))

(deftest buffer-bounds-at-configured-size
  (let [home (str (fs/create-temp-dir {:prefix "theseus-gctx-"}))]
    (try
      (with-redefs [config/home (fn [] home)]
        (dotimes [i 40]
          (gctx/record! group-id {:message-id i :from "x" :text (str "m" i)} :size 30))
        (let [entries (gctx/recent group-id 30)]
          (is (= 30 (count entries)))
          (is (= "m10" (:text (first entries))))
          (is (= "m39" (:text (last entries))))))
      (finally
        (fs/delete-tree home)))))
