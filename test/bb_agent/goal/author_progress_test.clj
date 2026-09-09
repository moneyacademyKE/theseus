(ns bb-agent.goal.author-progress-test
  "V1a pins (bk-4876): the edited-in-place authoring progress message and
   the detached authoring path. Pure decisions are pinned hermetically —
   no Telegram, no provider."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.goal.author-progress :as ap]
            [bb-agent.goal-bridge :as bridge]
            [bb-agent.goal.registry :as registry]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp* nil)

(defn with-tmp-goals [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "author-progress-test"}))]
    (binding [*tmp* tmp]
      (with-redefs [registry/goals-root (constantly tmp)
                    config/home (constantly tmp)]
        (f)))))

(use-fixtures :each with-tmp-goals)

(deftest render-test
  (testing "call count, last tool, elapsed minutes; singular/plural honest"
    (is (= "⚙️ authoring — 1 tool call · last: read_file · 0m elapsed"
           (ap/render {:calls 1 :last-tool "read_file" :started-ms 0} 30000)))
    (is (= "⚙️ authoring — 12 tool calls · last: write_file · 3m elapsed"
           (ap/render {:calls 12 :last-tool "write_file" :started-ms 0} 180000)))
    (is (= "⚙️ authoring — 0 tool calls · 0m elapsed"
           (ap/render {:calls 0 :last-tool nil :started-ms 0} 1000)))))

(deftest should-edit-throttle-test
  (testing "no edit before the first call lands (the initial post already says authoring started)"
    (is (false? (ap/should-edit? {:calls 0 :last-edit-ms 0} 999999 {}))))
  (testing "edits ride the 15s default window"
    (is (true? (ap/should-edit? {:calls 3 :last-edit-ms 0} 15001 {})))
    (is (false? (ap/should-edit? {:calls 3 :last-edit-ms 0} 14999 {}))))
  (testing "override throttle wins (tests use tiny windows)"
    (is (true? (ap/should-edit? {:calls 1 :last-edit-ms 0} 51 {:throttle-ms 50})))))

(deftest summarize-test
  (testing "final edit: first non-blank lines only, bounded"
    (is (= "🚀 Goal x launched — outcome lands here.\nextra detail\nmore"
           (ap/summarize "\n🚀 Goal x launched — outcome lands here.\n\nextra detail\nmore\n")))
    (is (= "" (ap/summarize nil)))))

(deftest make-progress-emit-test
  (testing "happy path: initial post, per-emit edits at throttle 0, final
            edit rides the same message and returns the reply"
    (let [edits (atom [])
          progress (ap/make-progress-emit
                    {:send! (fn [_] {:message-id 7})
                     :edit! (fn [id text] (swap! edits conj [id text]))
                     :throttle-ms 0})]
      (is (some? progress))
      ((:emit progress) {:status :tool/call :tool "read_file"})
      ((:emit progress) {:status :tool/call :tool "write_file"})
      (is (= 7 (ffirst @edits)) "edits carry the sent message's id")
      (is (str/includes? (second (last @edits)) "2 tool calls"))
      (let [reply ((:finish! progress) "🚀 launched")]
        (is (= "🚀 launched" reply))
        (is (str/includes? (second (last @edits)) "🚀 launched"))
        (is (= 7 (first (last @edits))) "final edit rides the same message")))))

(deftest emit-ignores-non-call-events-test
  (testing "only :tool/call events move the count (:tool/done is noise)"
    (let [edits (atom [])
          progress (ap/make-progress-emit
                    {:send! (fn [_] {:message-id 7})
                     :edit! (fn [id text] (swap! edits conj [id text]))
                     :throttle-ms 0})]
      ((:emit progress) {:status :tool/done :tool "read_file"})
      (is (empty? @edits) "done events are not calls"))))

(deftest failed-initial-post-degrades-to-nil-test
  (testing "a failed send! returns nil — silent authoring, never a blocked launch"
    (is (nil? (ap/make-progress-emit
               {:send! (fn [_] (throw (ex-info "telegram down" {})))
                :edit! (fn [_ _] (is false "edit must never fire"))})))))

(deftest gate-test
  (testing ":goal/authoring-progress defaults on; false turns it off"
    (with-redefs [config/load-config (constantly {})]
      (is (true? (ap/enabled?))))
    (with-redefs [config/load-config (constantly {:goal/authoring-progress false})]
      (is (false? (ap/enabled?))))))

(deftest authoring-lock-test
  (testing "the per-topic authoring lock: acquire, refuse second, stale pid
            is removable, unlock frees (V1a — detached authoring's guard)"
    (let [chat 777 thread 888
          lock-file (str *tmp* "/.authoring-" chat "-" thread ".edn")]
      (is (false? (bridge/authoring-busy? chat thread)) "no lock → free")
      (is (true? (bridge/authoring-lock! chat thread)))
      (is (false? (bridge/authoring-lock! chat thread)) "second acquire refused")
      (is (true? (bridge/authoring-busy? chat thread)))
      ;; stale lock: a dead pid must not wedge the topic forever
      (spit lock-file (pr-str {:pid 999999999}))
      (is (false? (bridge/authoring-busy? chat thread)) "dead pid → stale → free")
      ;; corrupt lock content is stale too (readable > loadable)
      (spit lock-file "not-edn(")
      (is (false? (bridge/authoring-busy? chat thread)))
      (bridge/authoring-unlock! chat thread)
      (is (false? (bridge/authoring-busy? chat thread)) "unlock frees"))))

(deftest dispatch-detaches-test
  (testing "dispatch acks immediately: scaffolds, writes launch args, spawns
            the detached script, never authors inline (V1a)"
    (let [spawned (atom nil)]
      (with-redefs [bridge/scaffold! (fn [name]
                                       (let [d (str *tmp* "/" name)]
                                         (fs/create-dirs d) d))
                    bridge/spawn-detached! (fn [_dir cmd] (reset! spawned cmd) "12345")]
        (let [reply (bridge/dispatch-spec! "build a small thing" -31 4721)
              _ (is (str/includes? reply "authoring started"))
              _ (is (str/includes? @spawned "goal_launch.bb")
                  "the detached script is the authoring process")
              args-file (nth (str/split @spawned #" ") 2)
              args (edn/read-string (slurp args-file))]
          (is (= "build a small thing" (:spec args)))
          (is (= -31 (:chat-id args)))
          (is (= 4721 (:thread-id args)))
          (is (str/includes? (str (:home args)) "author-progress-test")
              "the child reads config from the dispatching home — data, not ambient env"))))))

(deftest dispatch-busy-authoring-test
  (testing "a live authoring lock gives the immediate busy reply (no second
            wasted authoring in one topic)"
    (bridge/authoring-lock! -32 4722)
    (let [reply (bridge/dispatch-spec! "build another thing" -32 4722)]
      (is (str/includes? reply "already authoring")))
    (bridge/authoring-unlock! -32 4722)))

(deftest dispatch-busy-registry-test
  (testing "a registered RUNNING goal also refuses at dispatch (the registry
            guard moved to the front line when authoring went detached)"
    (let [pid (.pid (java.lang.ProcessHandle/current)) ; this test process is alive
          f (str *tmp* "/active.edn")]
      (with-redefs [registry/active-file (constantly f)]
        (registry/register-run! -33 4723 {:pid pid :name "runner-alive"
                                          :chat-id -33 :thread-id 4723})
        (let [reply (bridge/dispatch-spec! "build a third thing" -33 4723)]
          (is (str/includes? reply "runner-alive"))
          (is (str/includes? reply "one goal per topic")))))))
