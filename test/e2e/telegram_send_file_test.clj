(ns e2e.telegram-send-file-test
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-delivery :as delivery]
            [bb-agent.telegram-upload :as upload]
            [bb-agent.tool :as tool]
            [bb-agent.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def telegram-config
  {:base-url "https://telegram.test"
   :token "TESTTOKEN"})

(defn- response
  [status body]
  {:status status
   :body (if (string? body) body (json/generate-string body))})

(defn- scripted-transport
  [responses calls]
  (fn [url request]
    (swap! calls conj {:url url :request request})
    (let [next-response (first @responses)]
      (swap! responses #(vec (rest %)))
      next-response)))

(defn- part
  [multipart name]
  (some #(when (= name (:name %)) %) multipart))

(deftest send-file-rides-the-bounded-ladder
  (testing "a document posts multipart to sendDocument and returns the message id"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-file-"})
          path (fs/path home "report.txt")
          _ (spit (str path) "report body")
          responses (atom [(response 200 {:ok true :result {:message_id 77}})])
          calls (atom [])
          result (upload/send-file!
                  telegram-config -1001 (str path) :document
                  {:caption "probe report"
                   :thread-id 4721
                   :transport (scripted-transport responses calls)})]
      (is (= {:attempts 1 :message-id 77} result))
      (is (str/ends-with? (:url (first @calls)) "/botTESTTOKEN/sendDocument"))
      (let [multipart (get-in (first @calls) [:request :multipart])]
        (is (= "-1001" (:content (part multipart "chat_id"))))
        (is (= "4721" (:content (part multipart "message_thread_id"))))
        (is (= "probe report" (:content (part multipart "caption"))))
        (is (= (str path) (str (:content (part multipart "document"))))))
      (fs/delete-tree home)))

  (testing "photos post to sendPhoto with the photo field"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-photo-"})
          path (fs/path home "chart.png")
          _ (spit (str path) "png")
          responses (atom [(response 200 {:ok true :result {:message_id 8}})])
          calls (atom [])
          _ (upload/send-file!
             telegram-config 42 (str path) :photo
             {:transport (scripted-transport responses calls)})]
      (is (str/ends-with? (:url (first @calls)) "/botTESTTOKEN/sendPhoto"))
      (is (some? (part (get-in (first @calls) [:request :multipart]) "photo")))
      (fs/delete-tree home)))

  (testing "a 429 waits once then retries on the same endpoint"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-retry-"})
          path (fs/path home "f.bin")
          _ (spit (str path) "x")
          responses (atom [(response 429 {:ok false :error_code 429
                                          :description "Too Many Requests"
                                          :parameters {:retry_after 3}})
                           (response 200 {:ok true :result {:message_id 5}})])
          calls (atom [])
          sleeps (atom [])
          result (upload/send-file!
                  telegram-config 42 (str path) :document
                  {:transport (scripted-transport responses calls)
                   :sleep-fn #(swap! sleeps conj %)})]
      (is (= 2 (:attempts result)))
      (is (= [3000] @sleeps))
      (is (= 2 (count @calls)))
      (fs/delete-tree home)))

  (testing "a terminal failure throws structured data"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-term-"})
          path (fs/path home "f.bin")
          _ (spit (str path) "x")
          responses (atom [(response 400 {:ok false :error_code 400
                                          :description "FILE_PARTS_INVALID"})])
          calls (atom [])]
      (try
        (upload/send-file!
         telegram-config 42 (str path) :document
         {:transport (scripted-transport responses calls)})
        (is false "terminal failure must throw")
        (catch Exception error
          (let [data (ex-data error)]
            (is (= :terminal (:failure/kind data)))
            (is (= 400 (:failure/status data))))))
      (fs/delete-tree home))))

(deftest send-file-validates-before-any-http
  (testing "a missing file is refused without touching the network"
    (let [calls (atom [])]
      (try
        (upload/send-file!
         telegram-config 42 "/nonexistent/report.pdf" :document
         {:transport (scripted-transport (atom []) calls)})
        (is false "missing file must be refused")
        (catch Exception error
          (is (str/includes? (ex-message error) "not found"))))
      (is (empty? @calls))))

  (testing "an oversized file is refused without touching the network"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-big-"})
          path (fs/path home "big.bin")
          _ (spit (str path) (apply str (repeat 100 "x")))
          calls (atom [])]
      (try
        (upload/send-file!
         telegram-config 42 (str path) :document
         {:max-bytes 10
          :transport (scripted-transport (atom []) calls)})
        (is false "oversized file must be refused")
        (catch Exception error
          (is (str/includes? (ex-message error) "byte limit"))))
      (is (empty? @calls))
      (fs/delete-tree home)))

  (testing "an over-long caption is refused without touching the network"
    (let [home (fs/create-temp-dir {:prefix "theseus-send-cap-"})
          path (fs/path home "f.bin")
          _ (spit (str path) "x")
          calls (atom [])]
      (try
        (upload/send-file!
         telegram-config 42 (str path) :document
         {:caption (apply str (repeat 1025 "x"))
          :transport (scripted-transport (atom []) calls)})
        (is false "over-long caption must be refused")
        (catch Exception error
          (is (str/includes? (ex-message error) "caption"))))
      (is (empty? @calls))
      (fs/delete-tree home))))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest telegram-send-file-tool-is-wired-to-the-session
  (let [home (fs/create-temp-dir {:prefix "theseus-send-tool-"})
        port (free-port)
        calls (atom [])
        stop-server
        (server/run-server
         (fn [req]
           (let [body (when-let [stream (:body req)] (slurp stream))]
             (swap! calls conj {:uri (:uri req) :body body})
             (case (:uri req)
               "/botTESTTOKEN/sendDocument"
               {:status 200
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok true :result {:message_id 91}})}
               {:status 404
                :headers {"content-type" "application/json"}
                :body (json/generate-string {:ok false :description "nope"})})))
         {:port port})
        workdir (fs/path home "work")
        _ (fs/create-dirs workdir)
        report (fs/path workdir "report.txt")
        _ (spit (str report) "the report")
        request (fn [args]
                  {:tool/name "telegram_send_file"
                   :tool/args args
                   :approval/policy :auto-all})]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :fake
                     :model "fake-deterministic"
                     :telegram {:token "TESTTOKEN"
                                :base-url (str "http://127.0.0.1:" port)}}))
      (testing "the tool sends to the session chat with the injected context"
        (let [result (with-redefs [config/home (fn [] (str home))]
                       (tool/handle-tool-request
                        (request {"path" (str report) "caption" "wire check"})
                        {:telegram/send-context {:chat-id -1001 :thread-id 4721}}))]
          (is (= :ok (:status result)))
          (is (true? (:executed? result)))
          (is (= 91 (:message-id result)))
          (let [send (last (filter #(= "/botTESTTOKEN/sendDocument" (:uri %)) @calls))]
            (is (some? send) "the wire must have seen one sendDocument")
            (is (str/includes? (:body send) "name=\"chat_id\""))
            (is (str/includes? (:body send) "-1001"))
            (is (str/includes? (:body send) "4721"))
            (is (str/includes? (:body send) "wire check")))))

      (testing "outside a Telegram session the tool refuses without network"
        (let [before (count @calls)
              result (with-redefs [config/home (fn [] (str home))]
                       (tool/handle-tool-request
                        (request {"path" (str report)})
                        {}))]
          (is (= :error (:status result)))
          (is (str/includes? (:error/message result) "Telegram session"))
          (is (= before (count @calls)))))

      (testing "a missing file errors without network"
        (let [before (count @calls)
              result (with-redefs [config/home (fn [] (str home))]
                       (tool/handle-tool-request
                        (request {"path" "/nonexistent/gone.pdf"})
                        {:telegram/send-context {:chat-id -1001}}))]
          (is (= :error (:status result)))
          (is (= before (count @calls)))))

      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest telegram-send-file-is-advertised
  (let [def (some #(when (= "telegram_send_file" (get-in % [:function :name])) %)
                  tools/definitions)]
    (is (some? def) "telegram_send_file must be advertised to providers")
    (is (= ["path"] (get-in def [:function :parameters :required])))))
