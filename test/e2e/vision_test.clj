(ns e2e.vision-test
  "Photo attachments ride the provider request as bounded multimodal parts.
   Without images the wire stays byte-for-byte unchanged. A vision-rejecting
   model gets exactly one text-only retry; unrelated 400s still fail loudly."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(def ^:private image-bytes "JPEGBYTES")
(def ^:private expected-b64 "SlBFR0JZVEVT")

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- temp-image [home]
  (let [path (str (fs/path home "probe.jpg"))]
    (spit path image-bytes)
    {:path path :mime-type "image/jpeg"}))

(defn- completion-server
  [port bodies]
  (server/run-server
   (fn [req]
     (let [body (json/parse-string (slurp (:body req)) keyword)]
       (swap! bodies conj body)
       (case (:uri req)
         "/v1/chat/completions"
         {:status 200
          :headers {"content-type" "application/json"}
          :body (json/generate-string {:choices [{:message {:content "ok"}}]})}
         "/v1/messages"
         {:status 200
          :headers {"content-type" "application/json"}
          :body (json/generate-string {:content [{:type "text" :text "ok"}]})}
         {:status 404
          :headers {"content-type" "application/json"}
          :body (json/generate-string {:ok false})})))
   {:port port}))

(defn- scripted-server
  [port handler]
  (server/run-server
   (fn [req]
     (let [body (json/parse-string (slurp (:body req)) keyword)]
       (handler (:uri req) body)))
   {:port port}))

(defn- vision-rejecting-handler
  "400 on multimodal blocks, 200 on plain text; records every body."
  [bodies content-path]
  (fn [_uri body]
    (swap! bodies conj body)
    (if (vector? (get-in body content-path))
      {:status 400
       :headers {"content-type" "application/json"}
       :body (json/generate-string {:error {:message "model does not support image input"}})}
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/generate-string
              (if (= [:messages 1 :content] content-path)
                {:choices [{:message {:content "text ok"}}]}
                {:content [{:type "text" :text "text ok"}]}))})))

(deftest openai-compatible-carries-images-as-data-urls
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-openai-"})
        port (free-port)
        bodies (atom [])
        stop-server (completion-server port bodies)
        image (temp-image home)]
    (try
      (let [_ (provider/complete
               :openai-compatible
               {:model "m"
                :messages [{:role :system :content "sys"}
                           {:role :user :content "describe"}]
                :images [image]
                :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "k"}})
            content (get-in (last @bodies) [:messages 1 :content])]
        (is (vector? content) "user content becomes multimodal blocks")
        (is (= {:type "text" :text "describe"} (first content)))
        (is (= {:type "image_url"
                :image_url {:url (str "data:image/jpeg;base64," expected-b64)}}
               (second content))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest anthropic-compatible-carries-images-as-base64-blocks
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-anthropic-"})
        port (free-port)
        bodies (atom [])
        stop-server (completion-server port bodies)
        image (temp-image home)]
    (try
      (let [_ (provider/complete
               :anthropic-compatible
               {:model "m"
                :messages [{:role :user :content "describe"}]
                :images [image]
                :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "k"}})
            content (get-in (last @bodies) [:messages 0 :content])]
        (is (vector? content) "user content becomes content blocks")
        (is (= {:type "text" :text "describe"} (first content)))
        (is (= {:type "image"
                :source {:type "base64"
                         :media_type "image/jpeg"
                         :data expected-b64}}
               (second content))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest no-images-leaves-the-wire-unchanged
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-plain-"})
        port (free-port)
        bodies (atom [])
        stop-server (completion-server port bodies)]
    (try
      (provider/complete
       :openai-compatible
       {:model "m"
        :messages [{:role :user :content "plain"}]
        :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                          :api-key "k"}})
      (let [messages (:messages (last @bodies))]
        (is (= [{:role "user" :content "plain"}] messages)))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest missing-image-file-throws-honestly
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-missing-"})
        port (free-port)
        bodies (atom [])
        stop-server (completion-server port bodies)
        raised? (try
                  (provider/complete
                   :openai-compatible
                   {:model "m"
                    :messages [{:role :user :content "describe"}]
                    :images [{:path "/nonexistent/probe.jpg"
                              :mime-type "image/jpeg"}]
                    :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                      :api-key "k"}})
                  false
                  (catch Exception _ true))]
    (try
      (is raised? "missing image bytes must fail before an HTTP request")
      (is (empty? @bodies) "missing image must not reach the provider")
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest openai-vision-rejection-falls-back-to-text-once
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-openai-fb-"})
        port (free-port)
        bodies (atom [])
        stop-server (scripted-server port (vision-rejecting-handler
                                           bodies [:messages 1 :content]))
        image (temp-image home)]
    (try
      (let [result (provider/complete
                    :openai-compatible
                    {:model "m"
                     :messages [{:role :system :content "sys"}
                                {:role :user :content "describe"}]
                     :images [image]
                     :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                       :api-key "k"}})]
        (is (= "text ok" (:content result)) "degraded turn still returns text")
        (is (:vision/degraded result) "degradation is honestly marked")
        (is (= 2 (count @bodies)) "exactly one text-only retry")
        (is (string? (get-in (last @bodies) [:messages 1 :content]))
            "retry carries plain text, not blocks"))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest openai-generic-400-still-fails-with-images
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-openai-400-"})
        port (free-port)
        bodies (atom [])
        stop-server
        (scripted-server
         port (fn [_uri _body]
                (swap! bodies conj ::called)
                {:status 400
                 :headers {"content-type" "application/json"}
                 :body (json/generate-string {:error {:message "invalid api key"}})}))
        image (temp-image home)]
    (try
      (let [raised? (try
                      (provider/complete
                       :openai-compatible
                       {:model "m"
                        :messages [{:role :user :content "describe"}]
                        :images [image]
                        :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                          :api-key "k"}})
                      false
                      (catch Exception _ true))]
        (is raised? "unrelated 400 must fail, not degrade")
        (is (= 1 (count @bodies)) "no retry on a non-vision rejection"))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest anthropic-vision-rejection-falls-back-to-text-once
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-anthropic-fb-"})
        port (free-port)
        bodies (atom [])
        stop-server (scripted-server port (vision-rejecting-handler
                                           bodies [:messages 0 :content]))
        image (temp-image home)]
    (try
      (let [result (provider/complete
                    :anthropic-compatible
                    {:model "m"
                     :messages [{:role :user :content "describe"}]
                     :images [image]
                     :provider/config {:base-url (str "http://127.0.0.1:" port "/v1")
                                       :api-key "k"}})]
        (is (= "text ok" (:content result)))
        (is (:vision/degraded result))
        (is (= 2 (count @bodies)))
        (is (string? (get-in (last @bodies) [:messages 0 :content]))))
      (finally
        (stop-server)
        (fs/delete-tree home)))))

(deftest run-turn-threads-user-images-into-the-provider-request
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-turn-"})
        captured (atom [])]
    (try
      (with-redefs [config/home (fn [] (str home))
                    provider/complete (fn [_ request]
                                        (swap! captured conj request)
                                        {:role :assistant :content "ok"})]
        (core/run-turn! {:provider :fake
                         :model "fake-deterministic"
                         :session/id "vision-turn"
                         :user/images [(temp-image home)]}
                        "describe"))
      (is (= 1 (count (:images (first @captured)))))
      (is (= "image/jpeg" (:mime-type (first (:images (first @captured))))))
      (finally
        (fs/delete-tree home)))))

(deftest run-turn-uses-vision-model-for-image-turns-only
  (let [home (fs/create-temp-dir {:prefix "theseus-vision-model-"})
        captured (atom [])]
    (try
      (with-redefs [config/home (fn [] (str home))
                    provider/complete (fn [_ request]
                                        (swap! captured conj request)
                                        {:role :assistant :content "ok"})]
        (core/run-turn! {:provider :fake
                         :model "primary-multimodal"
                         :vision-model "zai/glm-4.6v"
                         :session/id "vision-model-img"
                         :user/images [(temp-image home)]}
                        "describe")
        (core/run-turn! {:provider :fake
                         :model "primary-multimodal"
                         :vision-model "zai/glm-4.6v"
                         :session/id "vision-model-plain"}
                        "no images here"))
      (is (= "zai/glm-4.6v" (:model (first @captured)))
          "image turns ride the vision model")
      (is (= "primary-multimodal" (:model (second @captured)))
          "text turns keep the primary model")
      (finally
        (fs/delete-tree home)))))
