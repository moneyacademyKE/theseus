(ns bb-agent.tool.common
  "Shared tool-handler plumbing: result shapes, argument bounds, and the
   argument heal that runs at the ONE seam every tool request passes through.

   THE HEAL (2026-11-03, from Akas333/TYSA). A model emits tool arguments as
   JSON text, and a weak model sometimes encodes a value as a string where the
   schema declares a boolean or an integer. In Clojure every non-nil string is
   truthy, so such an argument does not fail — it silently INVERTS the flag it
   carries. Measured live before this existed:

     write_file {:append? \"false\"}  ->  :append? true, the file appended
     write_file {:append? \"\"}       ->  :append? true, the file appended
     write_file {:append? \"0\"}      ->  :append? true, the file appended

   i.e. a model that asked to overwrite `brain/…` got its old bytes preserved
   plus new ones appended — on the one seam that writes knowledge pages, the
   same seam whose `append? true` clobbered two of them on 2026-10-14.

   TYSA hit the identical shape and named the fix as a rule rather than a patch:
   \"Every weak-model argument wobble incident produced a heal wired ONLY to the
   tool where it landed … and the whitehead death loop (2026-10-09: quiz
   `type: \"\\\"predict\\\"\"` rejected ten times, byte-identical) happened precisely
   because the practice heal never reached the quiz tools. The owner ruling: this
   is ONE standard pattern, applied at EVERY tool — heal what is mechanically
   recognizable … let validation reject the rest honestly.\"
   https://github.com/Akas333/TYSA/blob/main/packages/engine/src/agent/tools/heal.ts

   So the types come from `bb-agent.tools/definitions` — the same registry the
   provider is advertised — and a tool added tomorrow is healed without anyone
   remembering to wire it. Two limits, both deliberate:

   * well-formed args pass through UNTOUCHED, and anything not mechanically
     recognizable is left alone rather than guessed (TYSA: \"unhealable values
     stay for validation to reject honestly\");
   * a string arg whose declared type IS a string is never touched, so `content`
     holding \"3000\" or `path` holding \"false\" survive byte-for-byte.

   Negative scope: this heals the ARGUMENTS of a request. It does not validate
   them — a healed `:append? false` still reaches the handler, and a value the
   handler cannot use is the handler's business. It also runs inside
   `normalize-request`, so a caller that invokes a handler function directly
   bypasses it, and `core.clj`'s loop-guard key is computed from pre-heal args
   (a `\"false\"` call and a `false` call key differently — cosmetic, and fixing
   it would mean healing before the turn loop reads the request)."
  (:require [bb-agent.tools :as tools]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def default-shell-timeout-ms 5000)
(def max-read-bytes 1000000)
(def max-write-bytes 1000000)
(def max-search-results 50)
(def max-doc-chars 20000)
(def safe-auto-tools #{"read_file" "search" "document_read" "git_status"})

(defn arg-schema
  "tool name -> {arg keyword -> declared :type}, derived from the definitions the
   provider is advertised. Derived, not restated: a second hardcoded table would
   be a copy that drifts, which is the failure TYSA's heal.ts header records."
  []
  (into {}
        (map (fn [d]
               (let [f (:function d)]
                 [(:name f)
                  (into {}
                        (map (fn [[k v]] [(keyword k) (:type v)]))
                        (get-in f [:parameters :properties]))])))
        tools/definitions))

(defn- parse-literal
  "JSON-parse a string that may be an encoded literal; nil when it is not one.
   `read-string` over a bare token is enough here — we only accept boolean and
   integer shapes, and anything else returns nil or throws into nil."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn- heal-one
  "The mechanically recognizable part only: a string carrying an encoded value of
   the declared type. Everything else returns nil, meaning \"leave it\".

   A declared boolean also takes the two literals case-insensitively, which is
   still an exact match rather than a guess: `parse-literal` over FALSE yields
   the SYMBOL false, so without this branch the live probe of 2026-11-03 still
   appended (`:append?` carrying the uppercase literal -> truthy string -> ORIG
   kept plus NEW). Any
   other string is left untouched and stays truthy — the honest limit, since the
   handler then sees exactly what the model sent."
  [declared-type value]
  (when (string? value)
    (let [trimmed (str/trim value)
          parsed (when (seq trimmed) (parse-literal trimmed))]
      (cond
        (and (= "boolean" declared-type) (re-matches #"(?i)true" trimmed)) true
        (and (= "boolean" declared-type) (re-matches #"(?i)false" trimmed)) false
        ;; A quoted string like "\"recall\"" parses back to a string — that is
        ;; the GLM incident's shape, and it is only a heal when the schema wants
        ;; a string, which it already has. Skip: not mechanically distinguishable
        ;; from prose, and guessing would corrupt content.
        (nil? parsed) nil
        (and (boolean? parsed) (= "boolean" declared-type)) parsed
        (and (integer? parsed) (= "integer" declared-type)) parsed
        (and (number? parsed) (= "number" declared-type)) parsed
        :else nil))))

(defn- declared-type
  "The schema's :type for one arg key, matched against either spelling: the
   provider's JSON parse yields keyword keys, a hand-built or replayed request
   can carry strings. Only the VALUE is ever healed — the key keeps the spelling
   it arrived with, because key normalization is a different concern and this
   seam has never had it — launch_goal reads both spellings of :spec itself,
   and that must keep working."
  [schema k]
  (get schema (if (keyword? k) k (keyword (str k)))))

(defn heal-args
  "Heal one tool's args against its declared schema, under their original keys.
   Well-formed args come back as the same map; a healed arg replaces only its own
   value. An unknown tool, a tool with no declared properties, or a non-map arg
   returns the input untouched.

   Idempotent by construction: a healed value is no longer a string, so a second
   pass is a no-op — TYSA: heals are idempotent, so a per-tool heal composed
   under the record-level wrapper is a harmless second pass."
  [tool-name args]
  (let [schema (get (arg-schema) (str tool-name))]
    (if (or (nil? schema) (not (map? args)))
      args
      (reduce-kv
       (fn [m k v]
         (let [healed (when-let [declared (declared-type schema k)]
                        (heal-one declared v))]
           (if (nil? healed) m (assoc m k healed))))
       args
       args))))

(defn normalize-request [request]
  (-> request
      (update :tool/name str)
      (update :tool/args #(heal-args (str (:tool/name request)) %))
      (update :approval/policy #(or % :ask))
      (assoc :approval/required? true)))

(defn approval-decision [{:approval/keys [policy] :tool/keys [name]}]
  (case policy
    :auto-all :approved
    :auto-safe (if (contains? safe-auto-tools name) :approved :denied)
    :never :denied
    :ask :ask
    :denied))

(defn deny-result
  "A denied tool call. The message names the denial SOURCE and the recourse
   (B4, 2026-09-07: a floor-denied `rm` produced a reply that explained
   nothing) — the model relays this, so the human learns which rule fired
   and what would grant it, instead of a bare 🚫."
  ([request] (deny-result request :approval))
  ([{:tool/keys [name] :as request} source]
   {:tool/name name
    :status :denied
    :executed? false
    :approval/required? (:approval/required? request)
    ;; :status :denied alone cannot carry the difference that matters to the
    ;; caller: an approval denial has a human pending, a policy denial has a
    ;; law and nobody to ask. The turn loop branches on this (see
    ;; core/awaiting-human?) — without it, both looked like "wait for a
    ;; reply" and a constitution veto ended turns that could have recovered.
    :denial/source source
    :error/message
    (case source
      :policy (str "Tool " name " denied by the tool constitution (brain/rules.clj). "
                   "This denies the CALL, not the task — recover instead of stopping: "
                   "use write_file/edit_file for file edits (never perl/sed/awk -i), "
                   "mv instead of rm, absolute paths inside the home root. "
                   "Only ask the owner when no alternative exists, and name the rule.")
      :approval (str "Tool " name " requires explicit approval and no approver is "
                     "present in this turn. Recourse: ask the user to approve, or "
                     "report this step as skipped — do not silently retry.")
      (str "Tool " name " requires explicit approval"))}))

(defn ok-result [name body]
  (merge {:tool/name name
          :status :ok
          :executed? true}
         body))

(defn error-result [name message body]
  (merge {:tool/name name
          :status :error
          :executed? true
          :error/message message}
         body))

(defn stringify-path [path]
  (when path
    (str path)))

(defn safe-timeout-ms [value]
  (let [n (cond
            (integer? value) value
            (number? value) (long value)
            :else default-shell-timeout-ms)]
    (-> n (max 1) (min 60000))))

(defn bounded-count [value default]
  (let [n (cond
            (integer? value) value
            (number? value) (long value)
            :else default)]
    (-> n (max 1) (min max-search-results))))
