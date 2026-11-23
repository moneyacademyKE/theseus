(ns e2e.model-commands-test
  (:require [babashka.http-client]
            [bb-agent.config :as config]
            [bb-agent.model-commands :as model-commands]
            [clojure.test :refer [deftest is]]))

(deftest parse-models-commands
  (is (= {:op :status} (model-commands/parse "/models")))
  (is (= {:op :default :args ["infered-local" "infered/auto"]}
         (model-commands/parse "/models default infered-local infered/auto")))
  (is (= {:op :fallback :args ["infered-router" "infered/auto"]}
         (model-commands/parse "/models fallback infered-router infered/auto")))
  (is (= {:op :clear-fallback} (model-commands/parse "/models clear-fallback"))))

(deftest model-command-validation-is-fail-closed
  (let [cfg {:provider :fake
             :model "fake-deterministic"
             :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"}}}
        result (model-commands/apply! cfg
                                      (model-commands/parse
                                       "/models default missing infered/auto"))]
    (is (false? (:ok? result)))
    (is (re-find #"not configured" (:reply result)))))

(deftest model-command-writes-only-routing
  (let [written (atom nil)
        cfg {:provider :fake
             :model "fake-deterministic"
             :telegram {:token "secret"}
             :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"}}}]
    (with-redefs [config/write-config! (fn [candidate]
                                         (reset! written candidate)
                                         candidate)
                  babashka.http-client/get
                  (fn [_ _] {:status 200
                              :body "{\"data\":[{\"id\":\"infered/auto\"}]}"})]
      (let [result (model-commands/apply!
                    cfg
                    (model-commands/parse
                     "/models default infered-local infered/auto"))]
        (is (:ok? result))
        (is (= :infered-local (:provider @written)))
        (is (= "infered/auto" (:model @written)))
        (is (= {:token "secret"} (:telegram @written)))
        (is (= {:base-url "http://127.0.0.1:8790/v1"}
               (get-in @written [:providers :infered-local])))))))

(deftest model-command-sets-single-fallback
  (let [written (atom nil)
        cfg {:provider :infered-local
             :model "infered/auto"
             :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"}
                         :infered-router {:base-url "https://example.test/v1"}}}]
    (with-redefs [config/write-config! (fn [candidate]
                                         (reset! written candidate)
                                         candidate)
                  babashka.http-client/get
                  (fn [_ _] {:status 200
                              :body "{\"data\":[{\"id\":\"infered/auto\"}]}"})]
      (is (:ok? (model-commands/apply!
                 cfg
                 (model-commands/parse
                  "/models fallback infered-router infered/auto"))))
      (is (= [{:provider "infered-router" :model "infered/auto"}]
             (:provider/fallbacks @written))))))

(deftest model-command-forwards-auth-checks-status-and-accepts-plain-model-ids
  (let [seen-opts (atom nil)
        written (atom nil)
        cfg {:provider :fake
             :model "fake-deterministic"
             :providers {:openai-compatible {:base-url "https://api.example.test/v1"
                                             :api-key "sk-test-123"}}}]
    (with-redefs [config/write-config! (fn [candidate]
                                         (reset! written candidate)
                                         candidate)
                  babashka.http-client/get
                  (fn [_ opts]
                    (reset! seen-opts opts)
                    {:status 200
                     :body "{\"data\":[{\"id\":\"gpt-4o\"}]}"})]
      (let [res (model-commands/apply!
                 cfg
                 (model-commands/parse "/models default openai-compatible gpt-4o"))]
        (is (:ok? res) "plain model IDs like gpt-4o must be accepted when advertised")
        (is (= "gpt-4o" (:model @written)))
        (is (= "Bearer sk-test-123" (get-in @seen-opts [:headers "authorization"])))
        (is (number? (:timeout @seen-opts)))))
    (with-redefs [babashka.http-client/get
                  (fn [_ _]
                    {:status 401
                     :body "{\"data\":[{\"id\":\"gpt-4o\"}]}"})]
      (let [res (model-commands/apply!
                 cfg
                 (model-commands/parse "/models default openai-compatible gpt-4o"))]
        (is (false? (:ok? res)) "non-200 /models responses must be rejected")))))
