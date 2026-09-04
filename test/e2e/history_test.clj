(ns e2e.history-test
  "Session history is replayed into each turn as alternating user/assistant
   messages, bounded by :history-budget-chars — over budget the oldest middle
   turns are compressed behind a CONTEXT COMPACTION marker. Without prior
   turns the wire stays unchanged."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.core :as core]
            [bb-agent.provider :as provider]
            [bb-agent.session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private captured (atom []))

(defn- capturing-fake
  "Wrap the :fake provider: every request's messages are recorded, and
   compression-summarizer prompts get a real (short) summary — echoing
   them would inflate the compressed history and make the bound lie."
  [orig]
  (fn [provider request]
    (swap! captured conj (:messages request))
    (let [prompt (str (-> request :messages last :content))]
      (if (str/includes? prompt "Turns to summarize")
        {:role :assistant :content "short summary of earlier questions"}
        (orig provider request)))))

(defn- with-capture [f]
  (reset! captured [])
  (let [orig provider/complete]
    (with-redefs [provider/complete (capturing-fake orig)]
      (f))))

(defn- last-messages [] (last @captured))

(deftest prior-turns-are-replayed-before-the-prompt
  (with-capture
    (fn []
      (let [home (fs/create-temp-dir {:prefix "theseus-hist-replay-"})]
        (try
          (with-redefs [config/home (constantly (str home))]
            (session/append-turn! "hist-1" {:user/input "first question"
                                            :assistant/final "first answer"})
            (session/append-turn! "hist-1" {:user/input "second question"
                                            :assistant/final "second answer"})
            (core/run-turn! {:provider :fake :model "m" :session/id "hist-1"}
                            "third question")
            (is (= [{:role :user :content "first question"}
                    {:role :assistant :content "first answer"}
                    {:role :user :content "second question"}
                    {:role :assistant :content "second answer"}
                    {:role :user :content "third question"}]
                   (last-messages))
                "prior turns replay in order ahead of the new prompt"))
          (finally
            (fs/delete-tree home)))))))

(deftest over-budget-history-is-compacted
  (with-capture
    (fn []
      (let [home (fs/create-temp-dir {:prefix "theseus-hist-compact-"})]
        (try
          (with-redefs [config/home (constantly (str home))]
            (doseq [i (range 4)]
              (session/append-turn!
               "hist-2"
               {:user/input (str "question " i " about " (str/join (repeat 120 "x")))
                :assistant/final (str "answer " i " with " (str/join (repeat 120 "y")))}))
            (core/run-turn! {:provider :fake :model "m" :session/id "hist-2"
                             :history-budget-chars 400}
                            "new question")
            (let [msgs (last-messages)
                  total (reduce + 0 (map (fn [m] (count (str (:content m)))) msgs))
                  ;; the fake summarizer echoes its input, so the compressed
                  ;; result cannot shrink below the summarized middle — the
                  ;; honest contract is strictly-smaller-than-uncompressed
                   uncompressed (reduce + 0
                                        (map (fn [m] (count (str (:content m))))
                                             (concat (mapcat (fn [{:keys [user/input assistant/final]}]
                                                               [{:role :user :content input}
                                                                {:role :assistant :content final}])
                                                             (session/load-turns "hist-2"))
                                                     [{:role :user :content "new question"}])))]
              (is (some (fn [m] (and (= :user (:role m))
                                     (str/includes? (str (:content m)) "CONTEXT COMPACTION")))
                        msgs)
                  "oldest middle turns are summarized behind the marker")
              (is (some (fn [m] (str/includes? (str (:content m)) "answer 3")) msgs)
                  "recent tail turns survive verbatim")
              (is (< total uncompressed) "history shrinks under the budget")))
          (finally
            (fs/delete-tree home)))))))

(deftest no-prior-turns-leaves-the-wire-unchanged
  (with-capture
    (fn []
      (let [home (fs/create-temp-dir {:prefix "theseus-hist-fresh-"})]
        (try
          (with-redefs [config/home (constantly (str home))]
            (core/run-turn! {:provider :fake :model "m" :session/id "hist-3"}
                            "fresh question")
            (is (= [{:role :user :content "fresh question"}] (last-messages))
                "no session history means no extra messages"))
          (finally
            (fs/delete-tree home)))))))
