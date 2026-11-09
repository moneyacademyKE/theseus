(ns bb-agent.stats
  "Operational stats from ledgers that already exist on disk (audit F5).
   No new infra, no daemon: reads usage events, the outbox, and goal
   run logs, and returns one plain map. `bb stats` prints it."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.goal-liveness :as liveness]
            [bb-agent.usage :as usage]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- outbox-dir []
  (fs/path (config/home) "state" "outbox"))

(defn outbox-summary []
  (let [root (outbox-dir)
        pending (if (fs/exists? root) (count (fs/glob (str root) "*.edn")) 0)
        dead-root (fs/path root "dead")
        dead (if (fs/exists? dead-root) (count (fs/glob (str dead-root) "*.edn")) 0)]
    {:outbox/pending pending :outbox/dead dead}))

(defn- verdict-of [run-log]
  (let [lines (->> (slurp (str run-log))
                   str/split-lines
                   (take-last 20))]
    (cond
      (some #(re-find #"GOAL FULFILLED" %) lines) :fulfilled
      (some #(re-find #"HALT: stall" %) lines)    :stalled
      (some #(re-find #"GOAL FAILED" %) lines)    :failed
      :else :unresolved)))

(defn goal-summary []
  (let [root (str (fs/path (config/home) "goals"))
        registry (let [f (fs/path root "active.edn")]
                   (if (fs/regular-file? f)
                     (edn/read-string (slurp (str f)))
                     {}))
        workspaces (when (fs/exists? root)
                     (->> (fs/list-dir root)
                          (filter fs/directory?)
                          (remove #(str/ends-with? (str %) "logs"))))]
    (-> (reduce (fn [acc ws]
                  (let [rl (fs/path ws "run.log")
                        v (if (fs/regular-file? rl) (verdict-of rl) :no-run)]
                    (update acc v (fnil inc 0))))
                {:goal/active (count registry)
                 :fulfilled 0 :stalled 0 :failed 0 :unresolved 0 :no-run 0}
                workspaces)
        (assoc :goal/workspaces (count workspaces)))))

(defn liveness-summary
  "Dispatch liveness over the same goals root: how many dispatched authorings
   left no receipt. Additive — an absent goals root is :missing, never 0.
   dead-authoring is the 2026-10-09 shape (a dispatch ack read as a completion
   receipt); unstamped workspaces are unverifiable, not alive.

   2026-11-04: this summary was missing TWO buckets `bb goal-liveness` already
   carried — :errored (2026-11-03, an authoring that returned an error) and
   :delivery-blind (2026-11-04, a dispatch whose own ack was refused by the
   wire) — so the ops screen printed 20 of 24 workspaces and read as healthy
   while four dispatches had each died on a provider timeout. A measurement only
   one command reports is a measurement nobody runs (the 2026-10-12 lesson).
   :goal/errored is a STATUS bucket and adds to the partition;
   :goal/delivery-blind is an ANNOTATION of rows other buckets already count and
   must not. :goal/workspaces-audited is the denominator, so the partition is
   checkable rather than assumed — a count over part of the input reads exactly
   like a count over all of it (armory's `PASS — 28555 valid`, 2026-10-20)."
  []
  (let [a (liveness/audit (str (fs/path (config/home) "goals")))]
    (if (= :missing (:root-status a))
      {:goal/liveness-status :missing}
      {:goal/liveness-status :read
       :goal/live (count (:live a))
       :goal/receipted (count (:receipted a))
       :goal/dead-authoring (count (:failed a))
       :goal/errored (count (:errored a))
       :goal/abandoned (count (:abandoned a))
       :goal/unstamped (count (:unstamped a))
       :goal/delivery-blind (count (:delivery-blind a))
       :goal/workspaces-audited (:workspaces a)
       :goal/dead-names (->> (concat (:failed a) (:abandoned a))
                             (map :name) sort vec)
       :goal/errored-names (->> (:errored a) (map :name) sort vec)
       :goal/blind-names (->> (:delivery-blind a) (map :name) sort vec)})))
(defn summary []
  {:usage (usage/report)
   :outbox (outbox-summary)
   :goals (goal-summary)
   :goal-liveness (liveness-summary)})

(defn print-liveness!
  "One line on the ops screen: a dead authoring must be visible without anyone
   remembering a new command. Reports, never gates.

   The format string and its argument list move TOGETHER — splicing one half is
   what produced `Format string expects 7 arguments instead of 8` on the first
   attempt at this wiring (caught by bb lint, 2026-11-04). The line also checks
   its own partition and says so when the buckets do not sum to the workspaces
   audited, because a short sum is how two buckets went missing unnoticed."
  [l]
  (if (= :missing (:goal/liveness-status l))
    (println "liveness:   goals root not read — not-found, not zero")
    (let [summed (+ (:goal/live l) (:goal/receipted l) (:goal/dead-authoring l)
                    (:goal/errored l) (:goal/abandoned l) (:goal/unstamped l))]
      (println (format "liveness:   %d live, %d receipted, %d FAILED (dead authoring), %d errored, %d abandoned, %d unstamped, %d delivery-blind%s%s"
                       (:goal/live l) (:goal/receipted l) (:goal/dead-authoring l)
                       (:goal/errored l) (:goal/abandoned l) (:goal/unstamped l)
                       (:goal/delivery-blind l)
                       (if (seq (:goal/dead-names l))
                         (str " — " (str/join ", " (:goal/dead-names l)))
                         "")
                       (if (seq (:goal/blind-names l))
                         (str " | delivery-blind: " (str/join ", " (:goal/blind-names l)))
                         "")))
      (when (not= summed (:goal/workspaces-audited l))
        (println (format "liveness:   [PARTITION DOES NOT SUM] %d classified of %d workspace(s) audited — a bucket is missing, so the counts above are not a census"
                         summed (:goal/workspaces-audited l)))))))
(defn print-summary!
  "Human one-screen rendering of `summary`. Returns nil."
  []
  (let [{:keys [usage outbox goals]} (summary)]
    (println "== Theseus stats ==")
    (println (str "home:       " (config/home)))
    ;; The dollar figure names its own coverage. Before 2026-11-03 this printed
    ;; `$0.0000 est` for 34.5M tokens across 773 events whose provider has no
    ;; catalog entry — 773 zeros summed into a confident number. Now an unpriced
    ;; ledger says `unpriced` and names the providers; a partly priced one says
    ;; how much of it is priced. Report only, exit 0: a measurement with a named
    ;; evidence class, not a gate.
    (println (format "usage:      %d events, %d tokens, %s"
                     (:usage/events usage) (:tokens/total usage)
                     (let [est (:cost/estimate-usd usage)
                           unpriced (or (:cost/unpriced-events usage) 0)]
                       (cond
                         (nil? est)
                         (str "unpriced — no catalog entry for "
                              (if (seq (:cost/unpriced-providers usage))
                                (str/join ", " (:cost/unpriced-providers usage))
                                "any provider")
                              " (not $0: the cost is unknown)")

                         (zero? unpriced)
                         (format "$%.4f est" est)

                         :else
                         (format "$%.4f est over %d priced event(s); %d unpriced (%s)"
                                 est (:cost/priced-events usage) unpriced
                                 (str/join ", " (:cost/unpriced-providers usage)))))))
    (println (format "outbox:     %d pending, %d dead-lettered"
                     (:outbox/pending outbox) (:outbox/dead outbox)))
    (println (format "goals:      %d workspaces, %d active | fulfilled %d, stalled %d, failed %d, unresolved %d"
                     (:goal/workspaces goals) (:goal/active goals)
                     (:fulfilled goals) (:stalled goals) (:failed goals) (:unresolved goals)))
    (print-liveness! (:goal-liveness (summary)))))
