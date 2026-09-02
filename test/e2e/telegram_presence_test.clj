(ns e2e.telegram-presence-test
  (:require [bb-agent.telegram-presence :as presence]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def cfg
  {:base-url "https://telegram.test"
   :token "TESTTOKEN"})

(defn- capture-transport
  [calls status body]
  (fn [url request]
    (swap! calls conj {:url url :request request})
    {:status status :body body}))

(deftest typing-posts-the-typing-action
  (testing "thread routing rides along when present"
    (let [calls (atom [])
          result (presence/typing! cfg 4242
                                   {:thread-id 18
                                    :transport (capture-transport calls 200 "{\"ok\":true}")})]
      (is (:ok result))
      (is (= 200 (:status result)))
      (let [{:keys [url request]} (first @calls)]
        (is (str/includes? url "/botTESTTOKEN/sendChatAction"))
        (is (= "application/json" (get-in request [:headers "content-type"])))
        (is (= {:chat_id 4242 :action "typing" :message_thread_id 18}
               (json/parse-string (:body request) keyword))))))
  (testing "no thread keeps the body minimal"
    (let [calls (atom [])]
      (presence/typing! cfg 4242
                        {:transport (capture-transport calls 200 "{\"ok\":true}")})
      (is (= {:chat_id 4242 :action "typing"}
             (-> @calls first :request :body (json/parse-string keyword)))))))

(deftest reaction-acks-post-the-emoji-reaction
  (testing "explicit emoji rides the typed reaction wrapper"
    (let [calls (atom [])
          result (presence/reaction! cfg 4242 7
                                     {:emoji "🔥"
                                      :transport (capture-transport calls 200 "{\"ok\":true}")})]
      (is (:ok result))
      (let [{:keys [url request]} (first @calls)]
        (is (str/includes? url "/botTESTTOKEN/setMessageReaction"))
        (is (= {:chat_id 4242 :message_id 7
                :reaction [{:type "emoji" :emoji "🔥"}]}
               (json/parse-string (:body request) keyword))))))
  (testing "the default ack emoji is the mark"
    (let [calls (atom [])]
      (presence/reaction! cfg 4242 7
                          {:transport (capture-transport calls 200 "{\"ok\":true}")})
      (let [body (-> @calls first :request :body (json/parse-string keyword))]
        (is (= "👌" (get-in body [:reaction 0 :emoji])))
        (is (= "emoji" (get-in body [:reaction 0 :type])))))))

(deftest presence-failures-surface-as-data-and-never-throw
  (let [rejected (presence/reaction! cfg 4242 7
                                     {:transport (capture-transport
                                                  (atom [])
                                                  400
                                                  "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: REACTION_INVALID\"}")})]
    (is (false? (:ok rejected)))
    (is (= 400 (:status rejected)))
    (is (= "Bad Request: REACTION_INVALID" (:description rejected))))
  (let [exploded (presence/typing! cfg 4242
                                   {:transport (fn [_ _] (throw (ex-info "connection refused" {})))})]
    (is (false? (:ok exploded)))
    (is (some? (:error exploded)))))
