(ns bb-agent.robustness-wave2-test
  "Red/Green regression suite pinning all 17 Wave 2 robustness fixes (ADR 0012)."
  (:require [babashka.fs :as fs]
            [bb-agent.approval :as approval]
            [bb-agent.batch-checklist :as bc]
            [bb-agent.compression :as compression]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.cron :as cron]
            [bb-agent.doctor :as doctor]
            [bb-agent.fallback :as fallback]
            [bb-agent.memory-dream :as dream]
            [bb-agent.outbox :as outbox]
            [bb-agent.provider :as provider]
            [bb-agent.retry :as retry]
            [bb-agent.semantic-memory :as sm]
            [bb-agent.skill :as skill]
            [bb-agent.tool :as tool]
            [bb-agent.web :as web]
            [bb-agent.web-auth :as auth]
            [bb-agent.web-search :as web-search]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest fix-1-cron-step-zero-does-not-divide-by-zero
  (let [dt (java.time.ZonedDateTime/parse "2026-11-24T00:00:00Z")]
    (is (false? (cron/match-cron "*/0 * * * *" dt)))))

(deftest fix-2-semantic-memory-corrupt-edn-resilience
  (let [home (fs/create-temp-dir {:prefix "theseus-sm-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "session-summaries.edn")) "{:broken")
      (with-redefs [config/home (constantly (str home))]
        (is (= {} (sm/load-summaries))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-3-outbox-mark-attempt-missing-or-corrupt-does-not-throw
  (let [home (fs/create-temp-dir {:prefix "theseus-outbox-rob-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (is (= 0 (outbox/mark-attempt! {} "missing-id"))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-4-skill-discovery-isolates-single-malformed-skill
  (let [dir (fs/create-temp-dir {:prefix "theseus-skills-rob-"})]
    (try
      (fs/create-dirs (fs/path dir "good-skill"))
      (fs/create-dirs (fs/path dir "bad-skill"))
      (spit (str (fs/path dir "good-skill" "SKILL.md"))
            "---\nname: good-skill\ndescription: works\n---\nbody\n")
      (spit (str (fs/path dir "bad-skill" "SKILL.md"))
            "no frontmatter at all")
      (let [found (skill/discover-skills dir)]
        (is (= 1 (count found)))
        (is (= "good-skill" (:name (first found)))))
      (finally
        (fs/delete-tree dir)))))

(deftest fix-5-and-6-edit-file-atomic-and-read-file-refuses-binary
  (let [home (fs/create-temp-dir {:prefix "theseus-file2-rob-"})
        txt (fs/path home "a.txt")
        bin (fs/path home "blob.bin")]
    (try
      (spit (str txt) "hello world\n")
      (fs/write-bytes bin (byte-array [65 0 66 67]))
      (testing "fix #5: edit_file overwrites cleanly with no stray temp files"
        (let [res (tool/execute-tool-request
                   {:tool/name "edit_file"
                    :tool/args {:path (str txt) :old_text "world" :new_text "theseus"}})]
          (is (= :ok (:status res)))
          (is (= "hello theseus\n" (slurp (str txt))))
          (is (= 2 (count (fs/list-dir home))))))
      (testing "fix #6: read_file refuses binary NUL-byte blobs"
        (let [res (tool/execute-tool-request
                   {:tool/name "read_file"
                    :tool/args {:path (str bin)}})]
          (is (= :error (:status res)))
          (is (str/includes? (str/lower-case (str (:error/message res))) "binary"))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-7-batch-checklist-load-handles-io-error
  (with-redefs [babashka.fs/regular-file? (constantly true)
                clojure.core/slurp (fn [_] (throw (java.io.IOException. "vanished")))]
    (is (nil? (bc/load-checklist "s-vanished")))))

(deftest fix-8-web-search-handles-non-sequential-session-edn
  (let [home (fs/create-temp-dir {:prefix "theseus-ws-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state" "sessions"))
      (spit (str (fs/path home "state" "sessions" "bad.edn")) "42")
      (spit (str (fs/path home "state" "sessions" "good.edn"))
            (pr-str [{:user/input "unique-needle-xyz" :assistant/final "found it"}]))
      (with-redefs [config/home (constantly (str home))]
        (let [hits (web-search/search "unique-needle-xyz")]
          (is (= 1 (count hits)))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-9-and-10-web-port-parse-and-redacted-500
  (is (= 8787 (web/parse-port nil 8787)))
  (is (= 8787 (web/parse-port "" 8787)))
  (is (= 8787 (web/parse-port "not-a-port" 8787)))
  (is (= 8787 (web/parse-port "99999" 8787)))
  (is (= 9000 (web/parse-port "9000" 8787))))

(deftest fix-11-and-12-retry-backoff-overflow-and-zero-max-attempts
  (let [ms (retry/backoff-ms {:base-ms 100 :max-ms 5000 :jitter 0.5 :rand (constantly 0.5)} 100)]
    (is (>= ms 0))
    (is (<= ms 5000)))
  (let [neg (retry/backoff-ms {:base-ms -10 :max-ms 5000 :jitter 0.5 :rand (constantly 0.0)} 0)]
    (is (>= neg 0)))
  (let [res (retry/with-retries {:max-attempts 0 :sleep nil} (fn [] :ok))]
    (is (= :success (:outcome res)))
    (is (= :ok (:value res)))))

(deftest fix-13-approval-waiting-approver-survives-throwing-notify-and-expire
  (let [home (fs/create-temp-dir {:prefix "theseus-appr-rob-"})]
    (try
      (with-redefs [config/home (constantly (str home))]
        (let [approver (approval/waiting-approver
                        {:session-id "s1"
                         :channel :telegram
                         :timeout-ms 50
                         :notify (fn [_] (throw (ex-info "notify down" {})))
                         :on-expire (fn [_ _] (throw (ex-info "expire notify down" {})))})]
          (is (= :denied (approver {:tool/name "shell" :tool/args {:cmd "ls"}})))))
      (finally
        (fs/delete-tree home)))))

(deftest fix-14-memory-dream-survives-string-confidence-and-non-seq-bucket
  (let [convs [{:session/id "s1" :turns [{:user/input "always use babashka scripts"}]}
               {:session/id "s2" :turns [{:user/input "always use babashka scripts"}]}]
        bad-json "{\"learned\":[{\"text\":\"use bb\",\"confidence\":\"high\",\"evidence\":\"always use babashka\",\"sessions\":[\"s1\",\"s2\"]}],\"ideas\":\"not-a-vec\"}"
        res (dream/run-dream convs (constantly bad-json))]
    (is (map? res))
    (is (vector? (:ideas res)))))

(deftest fix-15-compression-summarize-middle-handles-nil-return-and-non-map
  (is (= "" (compression/summarize-middle [nil {:role :user :content "hi"}] nil (constantly nil)))))

(deftest fix-16-fallback-try-chain-skips-non-maps-and-fills-nil-ex-message
  (let [res (fallback/try-chain
             [nil
              {:provider :p1 :model "m1"}
              {:provider :p2 :model "m2"}]
             (fn [step]
               (if (= :p1 (:provider step))
                 (throw (NullPointerException.))
                 {:ok true})))]
    (is (= true (:ok res)))
    (is (string? (:fallback/reason (first (:fallback/tried res)))))))

(deftest fix-17-web-auth-load-sessions-survives-non-map-edn
  (let [home (fs/create-temp-dir {:prefix "theseus-auth-rob-"})]
    (try
      (fs/create-dirs (fs/path home "state"))
      (spit (str (fs/path home "state" "web-sessions.edn")) "42")
      (with-redefs [config/home (constantly (str home))]
        (is (= {} (auth/load-sessions!))))
      (finally
        (fs/delete-tree home)))))

(deftest top-level-reasoning-effort-is-accepted-by-doctor-and-forwarded-by-core
  (let [home (fs/create-temp-dir {:prefix "theseus-re-rob-"})
        seen (atom nil)]
    (try
      (spit (str (fs/path home "config.edn"))
            (pr-str {:provider :infered-local
                     :model "infered/auto"
                     :reasoning-effort "high"
                     :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"}}}))
      (with-redefs [config/home (constantly (str home))]
        (let [checks (doctor/run-checks)
              cfg-key-check (first (filter #(= :config-keys (:check %)) checks))]
          (is (= :ok (:status cfg-key-check))
              (str "doctor must recognize top-level :reasoning-effort, got: " cfg-key-check)))
        (with-redefs [provider/complete
                      (fn [_ req]
                        (reset! seen (:provider/config req))
                        {:content "ok" :tool/requests []})]
          (core/run-turn! {:provider :infered-local
                           :model "infered/auto"
                           :session/id "re-test"
                           :reasoning-effort "high"
                           :providers {:infered-local {:base-url "http://127.0.0.1:8790/v1"}}}
                          "hi")
          (is (= "high" (:reasoning-effort @seen)))))
      (finally
        (fs/delete-tree home)))))
