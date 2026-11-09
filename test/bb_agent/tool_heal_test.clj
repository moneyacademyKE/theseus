(ns bb-agent.tool-heal-test
  "The tool-argument heal at ONE seam, and the unpriced-usage report.

  Source of both rules: Akas333/TYSA (2026-11-03 scout), level 2 — read in files
  the repo ships:
   * https://github.com/Akas333/TYSA/blob/main/packages/engine/src/agent/tools/heal.ts
     — \"Every weak-model argument wobble incident produced a heal wired ONLY to
     the tool where it landed … The owner ruling: this is ONE standard pattern,
     applied at EVERY tool — heal what is mechanically recognizable … let
     validation reject the rest honestly.\" plus \"so no tool can ship unwrapped\".
   * https://github.com/Akas333/TYSA/blob/main/packages/engine/src/core/argument-heal.ts
     — \"Well-formed args pass through UNTOUCHED (same reference); unhealable
     values stay for validation to reject honestly.\"
   * https://github.com/Akas333/TYSA/blob/main/docs/compaction-accounting.md
     — \"Missing models or required usage stay unpriced\"; \"No catalog limit means
     no invented denominator\"; partial/errored/all-zero measurements \"do not
     replace the last measurement\".

  The gap each test names was measured live before this file existed:
  `write_file` with `:append? \"false\"` APPENDED (probe receipt 2026-11-03: the
  file read \"ORIG\\nNEW\\n\"), because any non-nil string is truthy in Clojure and
  nothing at the tool seam coerced the `:type \"boolean\"` the schema already
  declares. `bb stats` printed `773 events, 34499877 tokens, $0.0000 est` while
  every event's provider (`:openai-compatible`) has no entry in
  `usage/default-pricing` — 773 zeros summed into a confident dollar figure."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.tool :as tool]
            [bb-agent.tool.common :as common]
            [bb-agent.tools :as tools]
            [bb-agent.usage :as usage]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-file!
  "A fresh file holding ORIG, so an append and an overwrite are distinguishable."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "tool-heal-"}))
        p (str (fs/file dir "f.txt"))]
    (spit p "ORIG\n")
    [dir p]))

(defn- write-file-result [args]
  (tool/execute-tool-request {:tool/name "write_file" :tool/args args}))

;; ---------------------------------------------------------------- heal

(deftest string-false-on-a-declared-boolean-is-healed-to-false
  (let [[dir p] (temp-file!)]
    (try
      (let [r (write-file-result {:path p :content "NEW\n" :append? "false"})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= "NEW\n" (slurp p))
            "the model asked to overwrite; a truthy string must not append")
        (is (false? (:append? r))))
      (finally (fs/delete-tree dir)))))

(deftest string-true-on-a-declared-boolean-is-healed-to-true
  (let [[dir p] (temp-file!)]
    (try
      (let [r (write-file-result {:path p :content "NEW\n" :append? "true"})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= "ORIG\nNEW\n" (slurp p)))
        (is (true? (:append? r))))
      (finally (fs/delete-tree dir)))))

(deftest string-keyed-args-are-healed-too
  ;; SUPERSEDED 2026-11-03, same turn it was written: the first version of this
  ;; test called write_file with string keys and asserted :ok. That encoded a
  ;; contract the seam never had — `write-file!` destructures `{:keys [path …]}`,
  ;; so string keys were refused before this patch and still are (unchanged
  ;; behaviour, not a regression). The real contract is narrower and is what this
  ;; now asserts: the HEAL matches either key spelling, because launch_goal and
  ;; telegram_send_file both read `(or (get args "spec") (get args :spec))` and
  ;; must not lose their healed value.
  (is (= {"append?" false} (common/heal-args "write_file" {"append?" "false"}))
      "a string key's value is healed under the same declared schema")
  (is (= {"spec" "x" "append?" false}
         (common/heal-args "write_file" {"spec" "x" "append?" "false"}))
      "an undeclared arg is left alone")
  (is (= {"photo?" false} (common/heal-args "telegram_send_file" {"photo?" "false"}))
      "the heal reaches a handler that reads both spellings"))

(deftest boolean-literals-are-healed-case-insensitively
  ;; RED FIRST, added against the working implementation: this FAILED, because
  ;; edn/read-string over FALSE returns a SYMBOL, so heal-one saw nothing
  ;; recognizable and left a truthy string in place — the same defect the seam
  ;; exists to close, wearing a different case. An exact match on the two
  ;; literals is still an exact match; a heuristic would not be.
  (is (= {:append? false} (common/heal-args "write_file" {:append? "FALSE"})))
  (is (= {:append? false} (common/heal-args "write_file" {:append? "False"})))
  (is (= {:append? true} (common/heal-args "write_file" {:append? "TRUE"})))
  (is (= {:append? false} (common/heal-args "write_file" {:append? "  false  "}))
      "surrounding whitespace is trimmed before the match")
  (is (= {:append? "falsy"} (common/heal-args "write_file" {:append? "falsy"}))
      "anything that is not one of the two literals is left alone"))

(deftest a-healed-false-overwrites-through-the-real-seam
  (let [[dir p] (temp-file!)]
    (try
      (let [r (write-file-result {:path p :content "NEW\n" :append? "FALSE"})]
        (is (= :ok (:status r)) (pr-str r))
        (is (= "NEW\n" (slurp p))
            "the probe that found this: FALSE must not append")
        (is (false? (:append? r))))
      (finally (fs/delete-tree dir)))))

(deftest well-formed-args-pass-through-untouched
  (is (= {:path "/tmp/x" :content "hi" :append? false}
         (common/heal-args "write_file" {:path "/tmp/x" :content "hi" :append? false}))
      "same values, and nothing invented"))

(deftest unhealable-value-is-left-for-the-handler-to-reject-honestly
  (is (= {:append? "maybe"} (common/heal-args "write_file" {:append? "maybe"}))
      "\"maybe\" is a boolean in no encoding — heal nothing, do not guess")
  (is (= {:append? ""} (common/heal-args "write_file" {:append? ""}))
      "the empty string is not a JSON literal either"))

(deftest integer-arg-in-a-string-is-healed-to-a-number
  (is (= {:timeout-ms 3000}
         (common/heal-args "shell" {:timeout-ms "3000"}))
      "safe-timeout-ms silently dropped \"3000\" to the 5000 default before this"))

(deftest heal-is-driven-by-the-declared-schema-not-a-hardcoded-list
  ;; The point of ONE standard pattern: a tool added to `definitions` tomorrow is
  ;; healed without anyone remembering to wire it (TYSA: "no tool can ship
  ;; unwrapped"). Assert the seam reads the registry, not a literal map.
  (let [names (set (map #(get-in % [:function :name]) tools/definitions))]
    (is (every? names (keys (common/arg-schema)))
        "every declared tool has a schema entry derived from tools/definitions")
    (is (= #{:path :content :append? :create-dirs?}
           (set (keys (get (common/arg-schema) "write_file")))))))

(deftest heal-never-touches-a-path-or-content-string
  ;; A string arg whose declared type IS string must survive byte-for-byte —
  ;; including content that looks like a boolean or a number.
  (is (= {:path "false" :content "3000"}
         (common/heal-args "write_file" {:path "false" :content "3000"}))))

(deftest unknown-tool-args-pass-through
  (is (= {:anything "false"} (common/heal-args "no_such_tool" {:anything "false"}))))

;; ------------------------------------------------------- unpriced usage

(defn- turn-event [provider model cost]
  {:usage/event :turn
   :session/id "s1"
   :provider provider
   :model model
   :tokens/input 10
   :tokens/output 5
   :tokens/cache-read 0
   :tokens/cache-write 0
   :tokens/total 15
   :cost/estimate-usd cost
   :created/at "2026-11-03T00:00:00Z"})

(deftest report-separates-priced-from-unpriced-events
  (let [evs [(turn-event :openai-compatible "glm-5.3" 0.0)
             (turn-event :anthropic "claude-sonnet-4-6" 0.05)]]
    (with-redefs [usage/load-events (fn [] evs)]
      (let [r (usage/report)]
        (is (= 2 (:usage/events r)) "the event count is unchanged")
        (is (= 1 (:cost/priced-events r)) (pr-str r))
        (is (= 1 (:cost/unpriced-events r)))
        (is (= ["openai-compatible"] (:cost/unpriced-providers r))
            "the unpriced provider is named, not averaged away")
        (is (= 0.05 (:cost/estimate-usd r))
            "the total is the priced events' cost, never padded with zeros")))))

(deftest a-wholly-unpriced-ledger-reports-no-total-rather-than-zero
  ;; The live defect: 773 events, 34.5M tokens, "$0.0000 est". A zero here is
  ;; not a measured zero — it is 773 events nobody could price.
  (with-redefs [usage/load-events
                (fn [] [(turn-event :openai-compatible "glm-5.3" 0.0)
                        (turn-event :openai-compatible "qwen3.8-max" 0.0)])]
    (let [r (usage/report)]
      (is (= 0 (:cost/priced-events r)))
      (is (= 2 (:cost/unpriced-events r)))
      (is (nil? (:cost/estimate-usd r))
          "no priced event means no total to state — not-found is not zero")
      (is (= 2 (:usage/events r)) "tokens and events still count; only the dollar figure is withheld"))))

(deftest a-priced-ledger-is-byte-identical-in-shape-to-before
  ;; no-regression-promotion: when every event is priced the report must read
  ;; exactly as it did, with the two new keys added and nothing rewritten.
  (with-redefs [usage/load-events
                (fn [] [(turn-event :anthropic "claude-sonnet-4-6" 0.05)
                        (turn-event :anthropic "claude-sonnet-4-6" 0.02)])]
    (let [r (usage/report)]
      (is (= 2 (:usage/events r)))
      (is (= 0.07 (:cost/estimate-usd r)))
      (is (= 2 (:cost/priced-events r)))
      (is (= 0 (:cost/unpriced-events r)))
      (is (empty? (:cost/unpriced-providers r)))
      (is (= 30 (:tokens/total r))))))

(deftest price-entry-is-nil-for-an-uncatalogued-provider
  (is (nil? (usage/price-entry :openai-compatible "glm-5.3"))
      "the live provider has no catalog entry — this is the measured gap")
  (is (some? (usage/price-entry :anthropic "claude-sonnet-4-6"))))

(deftest tool-funnel-events-do-not-count-as-turns
  (with-redefs [usage/load-events
                (fn [] [(turn-event :openai-compatible "glm-5.3" 0.0)
                        {:usage/event :tool :tool/name "shell" :ok true
                         :created/at "2026-11-03T00:00:00Z"}])]
    (let [r (usage/report)]
      (is (= 1 (:usage/events r)))
      (is (= 1 (:cost/unpriced-events r))
          "the tool event has no provider, so it must not be priced or counted"))))
