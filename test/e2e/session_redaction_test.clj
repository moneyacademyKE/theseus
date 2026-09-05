(ns e2e.session-redaction-test
  "append-turn! is the single persistence seam for turns. Anything a tool
   result carries — including a config echo — lands on disk verbatim unless
   the seam redacts. Known-secret shapes (Telegram bot tokens, sk- API keys)
   must be replaced before write, and the file must be owner-only (0600)."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest secrets-in-turn-content-are-redacted-before-write
  (let [home (fs/create-temp-dir {:prefix "theseus-redact-"})]
    (try
      (with-redefs [config/home (constantly home)]
        (session/append-turn!
          "redact-1"
          {:user/input "show me the config"
           :assistant/final "here it is"
           :tool/results [{:tool/name "read_file"
                           :content ":token \"8511646577:AAFakeTokenForTestingPurposesOnly\"\n:api-key \"sk-airo-FakeKey123\""}]})
        (let [on-disk (slurp (str (session/session-file "redact-1")))]
          (is (not (str/includes? on-disk "8511646577:AAFake"))
              "bot token must not persist verbatim")
          (is (not (str/includes? on-disk "sk-airo-FakeKey123"))
              "API key must not persist verbatim")
          (is (str/includes? on-disk "<REDACTED")
              "a redaction marker must replace the secret")))
      (finally (fs/delete-tree home)))))

(deftest session-files-are-owner-only
  (let [home (fs/create-temp-dir {:prefix "theseus-redact-perms-"})]
    (try
      (with-redefs [config/home (constantly home)]
        (session/append-turn! "redact-2" {:user/input "hi" :assistant/final "hello"})
        (let [perms (set (map str (fs/posix-file-permissions (session/session-file "redact-2"))))]
          (is (= #{"OWNER_READ" "OWNER_WRITE"} perms)
              "session files must be born 0600, not chmodded later")))
      (finally (fs/delete-tree home)))))
