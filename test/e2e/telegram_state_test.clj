(ns e2e.telegram-state-test
  "Pins the durable poll-cursor semantics of bb-agent.telegram-state (audit F6).
   The contract under test:
   - offset: missing file -> nil (fresh start); roundtrip; CORRUPT FILE THROWS
     (fail-loud by design -- silently defaulting would replay every acked update)
   - seen: missing file -> #{} (idempotence belt-and-suspenders)
   - reply ledger: per-chat map, long-keyed, nil-safe, self-healing on corruption."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.telegram-state :as tstate]
            [clojure.test :refer [deftest is use-fixtures]]))

(def ^:dynamic *home* nil)

(defn- fresh-home [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "tg-state-test"}))]
    (binding [*home* tmp]
      (with-redefs [config/home (constantly tmp)]
        (f)))))

(use-fixtures :each fresh-home)

(deftest offset-defaults-and-roundtrip
  ;; fresh install: no file, no offset
  (is (nil? (tstate/load-offset)))
  ;; save creates state/ if absent and roundtrips
  (is (= 42160008 (tstate/save-offset! 42160008)))
  (is (= 42160008 (tstate/load-offset)))
  ;; overwrite wins (monotonic advance is the caller's job; state just stores)
  (is (= 42160009 (tstate/save-offset! 42160009)))
  (is (= 42160009 (tstate/load-offset))))

(deftest offset-corruption-fails-loud
  ;; Deliberate contract: a corrupt offset file throws instead of silently
  ;; replaying the world. Replay storms are worse than a failed boot.
  (fs/create-dirs (fs/path *home* "state"))
  (spit (str (fs/path *home* "state" "telegram-offset.edn")) "{not edn at all{{{")
  (is (thrown? Exception (tstate/load-offset))))

(deftest seen-set-defaults-and-roundtrip
  (is (= #{} (tstate/load-seen)))
  (is (= #{7 8 9} (tstate/save-seen! #{7 8 9})))
  (is (= #{7 8 9} (tstate/load-seen))))

(deftest reply-ledger-semantics
  (let [home *home*]
    ;; nil args are no-ops (no crash, no file)
    (is (nil? (tstate/record-reply! nil 1 2)))
    (is (nil? (tstate/lookup-reply "123" nil)))
    ;; string ids coerce to long keys; lookup is long-coerced too
    (is (= 999 (tstate/record-reply! 123 "555" 999)))
    (is (= 999 (tstate/lookup-reply 123 555)))
    (is (= 999 (tstate/lookup-reply "123" "555")))
    ;; distinct chats are distinct files
    (tstate/record-reply! 456 1 2)
    (is (= 2 (tstate/lookup-reply 456 1)))
    (is (nil? (tstate/lookup-reply 123 1)))
    ;; corrupt ledger: lookup degrades to nil, next record self-heals
    (spit (str (fs/path home "state" "telegram-replies" "123.edn")) "garbage{{{:")
    (is (nil? (tstate/lookup-reply 123 555)))
    (is (= 1000 (tstate/record-reply! 123 556 1000)))
    (is (= 1000 (tstate/lookup-reply 123 556)))))

(deftest offset-file-lives-under-state
  ;; R1 follow-through: the cursor is durable state and must live in state/,
  ;; where the hygiene gate can see it.
  (tstate/save-offset! 1)
  (is (fs/regular-file? (fs/path *home* "state" "telegram-offset.edn"))))
