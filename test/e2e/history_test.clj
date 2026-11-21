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

(deftest persistent-compaction-checkpoint-avoids-resummarizing-unchanged-middle
  (let [home (fs/create-temp-dir {:prefix "theseus-hist-ckpt-"})
        summarize-calls (atom [])
        orig provider/complete]
    (try
      (with-redefs [config/home (constantly (str home))
                    provider/complete
                    (fn [prov req]
                      (let [prompt (str (-> req :messages last :content))]
                        (if (str/includes? prompt "Turns to summarize")
                          (do
                            (swap! summarize-calls conj prompt)
                            {:role :assistant
                             :content (pr-str {:summary (str "summary #" (count @summarize-calls))
                                               :facts [(str "fact-" (count @summarize-calls))]
                                               :decisions ["keep EDN"]
                                               :files ["src/bb_agent/compression.clj"]
                                               :pending []})})
                          (orig prov req))))]
        (doseq [i (range 6)]
          (session/append-turn!
           "hist-ckpt"
           {:user/input (str "q" i " " (str/join (repeat 120 "x")))
            :assistant/final (str "a" i " " (str/join (repeat 120 "y")))}))
        ;; First over-budget turn (budget 1400 -> tail budget 350) keeps turn 5
        ;; verbatim (~252 chars) and summarizes turns 0..4 once.
        (core/run-turn! {:provider :fake :model "m" :session/id "hist-ckpt"
                         :history-budget-chars 1400}
                        "short follow-up 1")
        (is (= 1 (count @summarize-calls)) "first over-budget turn summarizes once")
        ;; Turn 5 (~252 chars) + turn 6 (~35 chars) both fit inside the 350-char tail:
        ;; watermark does not advance -> 0 extra summarizer calls!
        (core/run-turn! {:provider :fake :model "m" :session/id "hist-ckpt"
                         :history-budget-chars 1400}
                        "short follow-up 2")
        (is (= 1 (count @summarize-calls))
            "checkpoint reused with zero extra summarizer calls when watermark did not advance")
        ;; Adding a large turn pushes turns 5..6 past the tail watermark:
        ;; summarizer runs ONLY on the delta, passing previous summary!
        (session/append-turn!
         "hist-ckpt"
         {:user/input (str "big " (str/join (repeat 300 "z")))
          :assistant/final (str "big-ans " (str/join (repeat 300 "w")))})
        (core/run-turn! {:provider :fake :model "m" :session/id "hist-ckpt"
                         :history-budget-chars 1400}
                        "after big turn")
        (is (= 2 (count @summarize-calls)) "delta turn triggers exactly one incremental summary")
        (is (str/includes? (second @summarize-calls) "summary #1")
            "incremental summary receives prior summary context"))
      (finally
        (fs/delete-tree home)))))
