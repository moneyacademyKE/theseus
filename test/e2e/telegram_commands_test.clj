(ns e2e.telegram-commands-test
  "Chat-level session commands: /new archives the session (fresh context),
   /usage reports tokens and cost. Both answer directly without an LLM
   turn."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [bb-agent.telegram :as telegram]
            [bb-agent.usage :as usage]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def owner-id 1608111860)

(defn- dm-message [update-id message-id text]
  {:update_id update-id
   :message {:message_id message-id
             :from {:id owner-id :is_bot false :first_name "moe"}
             :chat {:id owner-id :type "private"}
             :text text}})

(defn- run-poll
  ([updates] (run-poll updates nil))
  ([updates setup-fn]
   (let [home (fs/create-temp-dir {:prefix "theseus-cmd-"})
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
                 :body (json/generate-string {:ok true :result {:id 1 :username "eileenslybot" :is_bot true}})}
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
                                 :allowed-user-ids [owner-id]}}))
       (when setup-fn (setup-fn home))
       (let [result (with-redefs [config/home (fn [] (str home))]
                      (telegram/poll-once!))]
         {:calls @calls :home (str home) :result result})
       (finally
         (stop-server))))))

(defn- replies [calls]
  (->> calls
       (filter #(str/includes? (:uri %) "sendMessage"))
       (mapv #(:text (json/parse-string (:body %) keyword)))))

(deftest new-archives-the-session
  (testing "a turn, then /new: session file is archived, next turn starts empty"
    (let [{:keys [home calls]} (run-poll [(dm-message 1 10 "remember the word quixotic")
                                          (dm-message 2 11 "/new")
                                          (dm-message 3 12 "what word?")])
          replies (replies calls)
          session-dir (fs/path home "state" "sessions")
          live-files (filter #(str/ends-with? (str %) ".edn") (fs/list-dir session-dir))
          archived (filter #(str/includes? (str %) "archived") (fs/list-dir session-dir))
          last-input (->> live-files
                          (map #(edn/read-string (slurp (str %))))
                          (mapcat identity)
                          (map :user/input)
                          last)]
      (try
        (is (some #(str/includes? % "Session reset") replies) "/new acknowledges")
        (is (seq archived) "old session archived, not deleted")
        (is (not (str/includes? (or last-input "") "quixotic"))
            "post-/new turn sees no prior history")
        (finally
          (fs/delete-tree home))))))

(deftest usage-reports-tokens
  (testing "/usage answers with token totals without an LLM turn"
    (let [{:keys [home calls]} (run-poll [(dm-message 1 20 "hello")
                                          (dm-message 2 21 "/usage")])
          replies (replies calls)
          usage-reply (last replies)]
      (try
        (is (str/includes? usage-reply "tokens") "usage reply mentions tokens")
        (is (str/includes? usage-reply "fake") "names the provider")
        (finally
          (fs/delete-tree home))))))

(deftest slash-command-triggers-skill
  (testing "typing /skill-name composes the skill prompt with input into the LLM turn"
    (let [{:keys [home]} (run-poll [(dm-message 1 30 "/explain recursion")]
                                   (fn [home]
                                     (let [sdir (fs/path home "skills" "explain")]
                                       (fs/create-dirs sdir)
                                       (spit (str (fs/path sdir "SKILL.md"))
                                             "---\nname: explain\ndescription: Explain concept\n---\nExplain concisely.\n"))))
          session-file (fs/path home "state" "sessions" "telegram-1608111860.edn")]
      (try
        (is (fs/regular-file? session-file) "session recorded")
        (let [turns (edn/read-string (slurp (str session-file)))
              turn (first turns)]
          (is (str/includes? (:user/input turn) "Explain concisely.") "contains skill body")
          (is (str/includes? (:user/input turn) "recursion") "contains user input"))
        (finally
          (fs/delete-tree home))))))
