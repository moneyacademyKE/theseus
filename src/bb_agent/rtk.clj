(ns bb-agent.rtk
  "RTK output filters, ported from OpenCrabs' Rust Token Killer — the rules,
   not the proxy. Shell and git_status output is compacted (ANSI stripped,
   noise lines dropped, long output capped) before it reaches the provider,
   so tokens buy answers instead of table formatting.

   Rules are data: built-in defaults plus optional user rules in
   <home>/rtk-filters.edn (EDN vector, same shape). Compaction is gated by
   config: {:rtk {:enabled true}} — default off, and results carry :rtk
   stats so the savings stay measurable."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private ansi-pattern #"\u001B\[[0-9;]*[A-Za-z]")

(def default-rules
  [{:name "ps" :cmd #"^\s*ps\b"
    :drop-lines [#"^\s*USER\s+PID\s+%CPU" #"^\s*PID\s+TTY" #"^$"]
    :max-lines 30}
   {:name "lsof" :cmd #"^\s*lsof\b"
    :drop-lines [#"^COMMAND\s+PID" #".*libgcc_s\.dylib.*" #".*usr/lib/system/.*" #"^$"]
    :max-lines 30}
   {:name "git-log" :cmd #"^\s*git log\b"
    :drop-lines [#"^$" #"(?i)^signed-off-by:" #"^Merge: "]
    :max-lines 40}
   {:name "dig" :cmd #"^\s*dig\b"
    :drop-lines [#"^;" #"^$" #"^\s*;;"]
    :max-lines 20}])

(defn strip-ansi
  "Remove ANSI escape sequences from terminal output."
  [s]
  (str/replace (or s "") ansi-pattern ""))

(defn compact-text
  "Apply one rule to a text block: strip ANSI, drop lines matching any
   :drop-lines regex, cap at :max-lines (head kept, tail marker appended).
   :on-empty replaces fully-dropped output so the provider never sees void."
  [text {:keys [drop-lines max-lines on-empty]}]
  (let [text (strip-ansi text)
        lines (str/split-lines text)
        kept (if (seq drop-lines)
               (remove (fn [line] (some #(re-find % line) drop-lines)) lines)
               lines)
        out-lines (if (and max-lines (> (count kept) max-lines))
                    (take max-lines kept)
                    kept)
        out (str/join "\n" out-lines)]
    (cond
      (and (seq drop-lines) on-empty (str/blank? out)) on-empty
      (and max-lines (> (count kept) max-lines))
      (str out "\n… [rtk: " (count kept) "\u2192" (count out-lines) " lines]")
      :else out)))

(defn select-rule
  "First rule whose :cmd regex matches the command text, else nil."
  [cmd rules]
  (some #(when (re-find (:cmd %) (or cmd "")) %) rules))

(defn rules
  "Default rules plus user rules from <home>/rtk-filters.edn when present.
   A malformed user file is ignored (defaults win) — filters must never
   become a new way to break the tool layer."
  []
  (let [f (fs/path (config/home) "rtk-filters.edn")]
    (if (fs/regular-file? f)
      (try (into [] (concat default-rules (read-string (slurp (str f)))))
           (catch Exception _ default-rules))
      default-rules)))

(def ^:private compacted-tools #{"shell" "git_status"})

(defn- cmd-text
  "Command text a rule can match on, for the tools we compact."
  [request]
  (case (:tool/name request)
    "shell" (str (get-in request [:tool/args :cmd]))
    "git_status" "git status"
    nil))

(defn apply
  "Compaction seam: when {:rtk {:enabled true}} and the request's tool is a
   compacted one, apply the first matching rule to :stdout and :stderr and
   attach :rtk stats. Everything else returns the result untouched — disabled,
   unmatched, or non-compacted tools pay zero cost."
  [request result cfg]
  (if (and (get-in cfg [:rtk :enabled])
           (contains? compacted-tools (:tool/name request))
           (:executed? result))
    (if-let [rule (select-rule (cmd-text request) (rules))]
      (let [raw (or (:stdout result) "")
            raw-err (or (:stderr result) "")
            out (compact-text raw rule)
            out-err (compact-text raw-err rule)]
        (-> result
            (assoc :stdout out)
            (assoc :stderr out-err)
            (assoc :rtk {:rule (:name rule)
                         :raw-chars (count raw)
                         :out-chars (count out)})))
      result)
    result))
