(ns e2e.telegram-group-context-test
  "Group turns must see recent group history (the rolling buffer), DM turns
   must not, and non-responded group messages still land in the buffer."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-lifecycle :as telegram]
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

(defn- topic-message
  "A forum-topic message: same chat, but Telegram marks it with
   is_topic_message + message_thread_id — sessions and history must
   isolate per topic."
  [update-id message-id thread-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id it-id :is_bot false :first_name "I.T"}
             :chat {:id group-id :type "supergroup"}
             :is_topic_message true
             :message_thread_id thread-id
             :text text}})

(defn- dm-message [update-id message-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id it-id :is_bot false :first_name "I.T"}
             :chat {:id it-id :type "private"}
             :text text}})

(defn- run-poll
  "Boot a fake Bot API with the given updates, run one poll against a temp
   home, return {:calls :home :result}. Optional second arg overrides the
   group config (e.g. :respond-to :all for command-path tests — under
   :mention, normalize-command strips the @bot from \"/goals@bot\" before
   the respond gate sees it, so the command never fires)."
  ([updates] (run-poll updates {}))
  ([updates {:keys [respond-to] :or {respond-to :mention}}]
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
                                :groups {group-id {:respond-to respond-to
                                                   :allow-user-ids [it-id]}}}}))
      (let [result (with-redefs [config/home (fn [] (str home))]
                     (telegram/poll-once!))]
        {:calls @calls :home (str home) :result result})
      (finally
        (stop-server))))))

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

(deftest topic-history-is-isolated
  "Two topics, one chat: a turn in topic 196 sees topic 196 chatter only,
   a turn in topic 200 sees topic 200 chatter only. Cross-topic leakage is
   context pollution (owner directive 2026-09-08: parallel dogfood topics)."
  (let [{:keys [home]} (run-poll [(topic-message 10 300 196 "the linkcheck spec lives in topic 196")
                                  (topic-message 11 301 200 "@eileenslybot what is the mdtoc spec?")
                                  (topic-message 12 302 196 "@eileenslybot what is the linkcheck spec?")])
        inputs (session-inputs home)
        turn-200 (first (filter #(str/includes? (or % "") "mdtoc spec?") inputs))
        turn-196 (first (filter #(str/includes? (or % "") "linkcheck spec?") inputs))]
    (try
      (is (some? turn-200) "topic 200 turn ran")
      (is (some? turn-196) "topic 196 turn ran")
      (is (str/includes? turn-196 "the linkcheck spec lives in topic 196")
          "same-topic history visible")
      (is (not (str/includes? turn-196 "mdtoc spec"))
          "topic 196 turn never sees topic 200 chatter")
      (is (not (str/includes? turn-200 "linkcheck spec lives"))
          "topic 200 turn never sees topic 196 chatter")
      (finally
        (fs/delete-tree home)))))

(deftest assistant-replies-are-recorded
  "Eileen's own answers must land in the topic buffer: Telegram never
   echoes a bot's messages back via getUpdates, so without this seam the
   agent is amnesiac about everything it itself said (2026-09-08)."
  (let [{:keys [home]} (run-poll [(group-message 20 400 "just chatter")
                                  (group-message 21 401 "@eileenslybot hello")])]
    (try
      (with-redefs [config/home (fn [] home)]
        (let [entries (gctx/recent group-id 30)]
          (is (some #(= "assistant" (:from %)) entries)
              "the assistant's own reply is in the buffer")))
      (finally
        (fs/delete-tree home)))))

(deftest command-replies-persist-as-turns
  "Chat-command replies (/goals, /usage, goal launches) ride outside the
   LLM turn path — without an explicit append they leave no session trace
   and follow-ups find amnesia. They must become session turns AND buffer
   entries."
  (let [{:keys [home]} (run-poll [(group-message 30 500 "/goals@eileenslybot")]
                                 {:respond-to :all})]
    (try
      (with-redefs [config/home (fn [] home)]
        (let [turns (->> (fs/glob (fs/path home "state" "sessions") "*.edn")
                         (filter fs/regular-file?)
                         (mapcat #(edn/read-string (slurp (str %)))))
              cmd-turn (first (filter #(str/includes? (str (:user/input %)) "/goals") turns))]
          (is (some? cmd-turn) "command reply became a session turn")
          (is (seq (str (:assistant/final cmd-turn))) "with the reply text as final")
          (let [entries (gctx/recent group-id 30)]
            (is (some #(and (= "assistant" (:from %))
                            (str/includes? (str/lower-case (str (:text %))) "goal"))
                      entries)
                "command reply visible in the group buffer"))))
      (finally
        (fs/delete-tree home)))))
