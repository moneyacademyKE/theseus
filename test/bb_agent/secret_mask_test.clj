(ns bb-agent.secret-mask-test
  (:require [bb-agent.secret-mask :as sm]
            [clojure.test :refer [deftest is testing]]))

(deftest mask-and-preserve-round-trip-test
  (let [stored {:url "https://api.example.com"
                :headers {"Authorization" "Bearer secret-abc"
                          "X-Custom" "val"}
                :env {"OPENAI_API_KEY" "sk-12345"
                      "LOG_LEVEL" "info"
                      "DB_PASSWORD" "hunter2"}}]
    (testing "masks sensitive env keys and literal headers while leaving non-secrets readable"
      (let [pub (sm/mask-config stored)]
        (is (= "https://api.example.com" (:url pub)))
        (is (= sm/masked-sentinel (get-in pub [:headers "Authorization"])))
        (is (= sm/masked-sentinel (get-in pub [:headers "X-Custom"])))
        (is (= sm/masked-sentinel (get-in pub [:env "OPENAI_API_KEY"])))
        (is (= sm/masked-sentinel (get-in pub [:env "DB_PASSWORD"])))
        (is (= "info" (get-in pub [:env "LOG_LEVEL"])))))

    (testing "posting back ****** preserves existing stored secrets while updating changed fields"
      (let [incoming {:url "https://api.v2.example.com"
                      :headers {"Authorization" sm/masked-sentinel}
                      :env {"OPENAI_API_KEY" sm/masked-sentinel
                            "DB_PASSWORD" "new-pass"
                            "LOG_LEVEL" "debug"}}
            merged (sm/merge-masked-update stored incoming)]
        (is (= "https://api.v2.example.com" (:url merged)))
        (is (= "Bearer secret-abc" (get-in merged [:headers "Authorization"])))
        (is (= "sk-12345" (get-in merged [:env "OPENAI_API_KEY"])))
        (is (= "new-pass" (get-in merged [:env "DB_PASSWORD"])))
        (is (= "debug" (get-in merged [:env "LOG_LEVEL"])))))))
