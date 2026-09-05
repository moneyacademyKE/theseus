(ns e2e.telegram-flow-test
  "Visual parity with the reference flow UX: a turn's whole procedural
   trace collapses into ONE growing message — an HTML expandable
   blockquote edited in place — instead of one message per tool call.

   Contract under test:
   - multi-tool turns: one sendMessage carrying <blockquote expandable>,
     then editMessageText calls grow it in place; per-tool lines render
     as <b>{icon} {name}</b> <code>{context}</code> with ⚙️ live and
     ✅/❌ on completion;
   - the settled footer reads ✅ Finished (N tool calls, 45s) and the
     final answer is a separate, clean message;
   - a lone tool line stays a plain one-liner (no blockquote);
   - a denied tool flips to ❌ and the reply is a clean sentence, never
     raw EDN (no \"tool-results=\");
   - a mid-turn provider failure settles the flow with ❌ Failed.

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

(defn- dm-update []
  [{:update_id 1
    :message {:message_id 42
              :from {:id owner-id :is_bot false :first_name "moe"}
              :chat {:id owner-id :type "private"}
              :text "run the probe"}}])

(defn- run-flow-poll
  "Drive one poll-once! against a fake Bot API recording every call.
   provider-impl receives the round counter fn. rules-content, when
   given, is written to brain/rules.clj. Returns {:calls [...]}."
  [provider-impl rules-content]
  (let [home (fs/create-temp-dir {:prefix "theseus-flow-"})
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
                       {:ok true :result (dm-update)})}

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
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)
                                :allowed-user-ids [owner-id]}}))
      (with-redefs [config/home (fn [] (str home))
                    provider/complete provider-impl]
        {:result (telegram/poll-once!) :calls @calls})
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(defn- bodies
  "Parsed JSON bodies of calls whose URI contains needle, in order."
  [calls needle]
  (->> calls
       (filter (fn [c] (str/includes? (:uri c) needle)))
       (map (fn [c] (json/parse-string (:body c) keyword)))))

(defn- flow-texts
  "The flow message's text over time: the initial sendMessage followed by
   every editMessageText, in order — the growing block's full history."
  [calls]
  (map :text (concat (bodies calls "/sendMessage")
                     (bodies calls "/editMessageText"))))

(def allow-shell
  "{:rules [{:name :allow-shell
              :pred (fn [tool args]
                      (and (= tool \"shell\")
                           (re-find #\"^echo\" (str (:cmd args)))))
              :decision :allow}]}")

(deftest multi-tool-turn-collapses-into-one-growing-flow-message
  (let [round (atom 0)
        provider-impl
        (fn [_ _]
          (case (swap! round inc)
            1 {:role :assistant
               :tool/requests [{:tool/id "t1" :tool/name "shell"
                                :tool/args {:cmd "echo alpha"}}]}
            2 {:role :assistant
               :tool/requests [{:tool/id "t2" :tool/name "shell"
                                :tool/args {:cmd "echo beta"}}]}
            {:role :assistant :content "both done"}))
        {:keys [calls]} (run-flow-poll provider-impl allow-shell)
        sends (bodies calls "/sendMessage")
        edits (bodies calls "/editMessageText")
        flow-sends (filter (fn [s] (str/includes? (or (:text s) "")
                                                    "<blockquote expandable"))
                           sends)
        final-send (last sends)
        settled (last edits)]
    (testing "the flow is one growing message: one send, then edits"
      (is (= 1 (count (filter (fn [s] (str/includes? (or (:text s) "")
                                                     "echo alpha"))
                              sends))))
      (is (>= (count edits) 2)))
    (testing "the trace wraps in an expandable blockquote once it grows"
      (is (some (fn [t] (str/includes? (or t "") "<blockquote expandable"))
                (map :text edits))))
    (testing "per-tool lines are bold-icon + monospace context"
      (is (some (fn [t] (str/includes? (or t "") "<code>echo alpha</code>"))
                (map :text edits)))
      (is (str/includes? (or (:text settled) "") "<b>✅ shell</b>")))
    (testing "the settled footer summarizes the turn"
      (is (str/includes? (or (:text settled) "") "Finished (2 tool calls")))
    (testing "the final answer is a separate clean message"
      (is (= "both done" (:text final-send)))
      (is (not (str/includes? (:text final-send) "blockquote"))))
    (testing "typing indicator is refreshed on flow edits"
      (let [actions (bodies calls "/sendChatAction")]
        (is (>= (count actions) 2)
            "typing is sent initially and re-asserted on tool flow updates")))))

(deftest denied-tool-renders-a-clean-rejection-never-raw-edn
  (let [provider-impl
        (fn [_ _]
          {:role :assistant
           :tool/requests [{:tool/id "t1" :tool/name "shell"
                            :tool/args {:cmd "definitely-not-allowed"}}]})
        {:keys [calls]} (run-flow-poll provider-impl nil)
        texts (map :text (bodies calls "/sendMessage"))
        reply (some (fn [t] (when (and t (str/includes? t "🚫")) t)) texts)
        all-text (str/join "\n" texts)]
    (testing "the reply is a clean rejection sentence"
      (is (some? reply))
      (is (str/includes? reply "shell")))
    (testing "no raw EDN leaks to the user"
      (is (not (str/includes? all-text "tool-results=")))
      (is (not (str/includes? all-text ":status"))))))

(deftest lone-tool-line-stays-a-plain-one-liner
  (let [round (atom 0)
        provider-impl
        (fn [_ _]
          (if (= 1 (swap! round inc))
            {:role :assistant
             :tool/requests [{:tool/id "t1" :tool/name "shell"
                              :tool/args {:cmd "echo only"}}]}
            {:role :assistant :content "one tool, one line"}))
        {:keys [calls]} (run-flow-poll provider-impl allow-shell)
        flow-texts (flow-texts calls)
        flow-lines (filter (fn [t] (and t (str/includes? t "shell"))) flow-texts)]
    (testing "a single tool never wraps in a blockquote"
      (is (seq flow-lines))
      (is (every? (fn [t] (not (str/includes? t "<blockquote"))) flow-lines)))
    (testing "the line flips to the completed icon"
      (is (some (fn [t] (str/includes? t "✅ shell")) flow-lines)))))

(deftest provider-failure-settles-the-flow-as-failed
  (let [round (atom 0)
        provider-impl
        (fn [_ _]
          (case (swap! round inc)
            1 {:role :assistant
               :tool/requests [{:tool/id "t1" :tool/name "shell"
                                :tool/args {:cmd "echo first"}}]}
            2 {:role :assistant
               :tool/requests [{:tool/id "t2" :tool/name "shell"
                                :tool/args {:cmd "echo second"}}]}
            (throw (ex-info "provider exploded" {}))))
        {:keys [calls]} (run-flow-poll provider-impl allow-shell)
        edits (map :text (bodies calls "/editMessageText"))
        settled (last (filter (fn [t] (and t (str/includes? t "Failed"))) edits))]
    (testing "the flow settles with a failure footer"
      (is (some? settled)))))
