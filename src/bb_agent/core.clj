(ns bb-agent.core
  (:require [bb-agent.brain :as brain]
            [bb-agent.circuit-breaker :as cb]
            [bb-agent.compression :as compression]
            [bb-agent.fallback :as fallback]
            [bb-agent.gaslighting :as gas]
            [bb-agent.loop-break :as loop-break]
            [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.plan :as plan]
            [bb-agent.provider :as provider]
            [bb-agent.redact :as redact]
            [bb-agent.retry :as retry]
            [bb-agent.saver-mode :as saver]
            [bb-agent.security :as sec]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.session :as session]
            [bb-agent.skill :as skill]
            [bb-agent.tool :as tool]
            [bb-agent.tool-repeat :as tool-repeat]
            [bb-agent.tools :as tools]
            [bb-agent.truncation :as trunc]
            [bb-agent.turn-integrity :as ti]
            [bb-agent.turn-signals :as turn-signals]
            [bb-agent.usage :as usage]
            [clojure.string :as str]))

(def ^:private default-max-tool-rounds 8)

(def ^:private default-nudge-final-rounds
  "Extension window when the budget runs out: one converge-NOW message
  instead of an instant death (the OpenCrabs nudge, ported in-turn)."
  10)

(defn- checkpoint-nudge-message
  "Periodic checkpoint for long turns: refocus before the budget burns."
  [consumed max-rounds]
  (str "\u26a1 Checkpoint \u2014 you've used " consumed " of " max-rounds " tool rounds. "
       "Take stock before continuing: what's done, what remains? Prioritize "
       "the fastest path to finishing; avoid re-reading files you already "
       "know or re-running exploratory commands."))

(defn- final-nudge-message
  "The extension window's opening message: converge now."
  [rounds]
  (str "\u26a1 Final " rounds " rounds \u2014 the turn will END when they're gone. "
       "Converge NOW: finish any in-flight writes with what you already "
       "know, then produce the final answer. No new exploration."))

(def ^:private default-loop-guard-threshold 3)

(defn- canonical-args
  "Stable identity for a tool call's args — repetition detection keys on
   this, so key order and formatting must not matter."
  [args]
  (pr-str (if (map? args) (sort-by (comp str key) args) args)))

(def ^:private default-loop-guard-nudge-threshold 2)

(defn- loop-guard-nudge-message
  "The warning a repeated call gets BEFORE the kill line — OpenCrabs parity
   (moe 2026-09-07: the loop guard should nudge on identical repeats, not
   only end the turn). Skipping execution is the point: the result cannot
   change, and the model must confront the warning as its tool result."
  [tool-name occurrence kill-threshold]
  (str "⚠️ Loop guard notice: `" tool-name "` has now been called " occurrence
       " times with identical arguments — the result will not change. "
       "STOP repeating it: use the result you already have, change your "
       "approach, or produce the final answer now. The next identical call "
       "ENDS this turn (limit " kill-threshold ")."))

(def ^:private default-reasoning-nudge-threshold 2)

(defn- reasoning-nudge-message
  "The model produced reasoning but neither an answer nor a tool call —
   the provider normalizes reasoning away, so it arrives as an empty round.
   Nudge it to converge instead of killing the turn on the first one
   (moe 2026-09-07: nudge when the model reasons without answering)."
  [streak threshold]
  (str "⚠️ You produced reasoning but no answer and no tool calls. "
       "Converge NOW: either call a tool to make concrete progress, or "
       "write the final answer. Empty round " streak " of " (dec threshold)
       " — the next one ends this turn."))

(defn- reasoning-death-message [streak]
  (str "⚠️ Turn ended: the model produced reasoning without answering or "
       "calling a tool " streak " rounds in a row. Narrow or rephrase the "
       "request."))

(def ^:private provider-breaker
  "Shared per-provider breaker threaded through retry calls. The
  breaker modules stay pure; this atom is the one mutable seam."
  (atom (cb/breaker 5 30)))

(defn- complete-retrying
  "Wrap provider/complete with retry/backoff + the shared breaker.
  config.edn may override retry knobs via a `:retry` map. The turn's
  :status/emit is threaded in as the retry announcer: backoff is
  liveness, not silence, and a caller that judges liveness by silence
  (goal authoring's idle clock) must see the difference. The emit
  guard lives in retry/with-retries, which owns never-throws."
  [cfg provider request]
  (let [opts (cond-> (-> (retry/defaults)
                         (merge (:retry cfg))
                         (assoc :breaker @provider-breaker
                                :breaker-key provider))
               (:status/emit cfg) (assoc :emit (:status/emit cfg)))
        result (retry/with-retries opts #(provider/complete provider request))]
    (when-let [status (get-in (:breaker result) [:statuses provider])]
      (swap! provider-breaker assoc-in [:statuses provider] status))
    (case (:outcome result)
      :success (:value result)
      :breaker-open (throw (ex-info (str "Provider circuit breaker open for " provider)
                                    {:provider provider :outcome :breaker-open}))
      (throw (:error result)))))

(defn- request-for [base step]
  (-> base
      (assoc :provider (:provider step))
      (assoc :model (:model step))
      (assoc :provider/config (:provider/config step))))

(defn- provider-config-for [cfg p]
  (let [pcfg (get-in cfg [:providers p])]
    (if (and (:reasoning-effort cfg) (nil? (:reasoning-effort pcfg)))
      (assoc (or pcfg {}) :reasoning-effort (:reasoning-effort cfg))
      pcfg)))

(defn- fallback-steps [cfg base-request]
  (let [primary {:provider (:provider base-request)
                 :model (:model base-request)
                 :provider/config (:provider/config base-request)}]
    (into [primary]
          (map (fn [fb]
                 (let [p (keyword (:provider fb))]
                   {:provider p
                    :model (:model fb)
                    :provider/config (provider-config-for cfg p)})))
          (:provider/fallbacks cfg))))

(def ^:private sticky-fallbacks (atom {}))

(defn- complete-chain
  "Primary provider first (retries + breaker intact), then
  :provider/fallbacks as data — each step guarded against cross-provider
  model leaks and reordered when a fallback earns session stickiness."
  [cfg base-request]
  (let [raw-steps (fallback-steps cfg base-request)
        primary-p (:provider base-request)
        guarded (mapv #(fallback/guard-model-leak % primary-p (:providers cfg)) raw-steps)
        steps (fallback/reorder-with-sticky sticky-fallbacks (:session/id cfg) guarded)]
    (if (= 1 (count steps))
      (complete-retrying cfg (:provider base-request) base-request)
      (let [res (fallback/try-chain steps
                                    #(complete-retrying cfg (:provider %)
                                                        (request-for base-request %)))]
        (when (and (map? res) (seq (:fallback/tried res)) (:fallback/served-by res))
          (fallback/record-rescue! sticky-fallbacks (:session/id cfg)
                                   {:provider (:fallback/served-by res)
                                    :model (:fallback/model res)}))
        res))))

(defn- awaiting-human?
  "True when every result in the round is a denial a HUMAN can lift — the
   approval gate, with a request genuinely pending. A constitution veto is
   not one: nothing is waiting, and the denial's own text says 'recover
   instead of stopping'. Ending the turn there is a dead end the model
   cannot see around, and in a detached lane (goal authoring, a cron
   schedule) there is nobody to reply at all.

   2026-09-11: goal authoring ran 56 rounds / 24 min, was denied one
   `cat config.edn` by deny-secrets, and the turn ended with 'needs your
   approval' — a message no one could act on. The workspace recorded
   :result :authored and no project.edn existed. The short-circuit's real
   meaning is 'a human is needed', so it now asks that question instead of
   'was anything denied'."
  [results]
  (and (seq results)
       (every? #(and (= :denied (:status %))
                     (= :approval (:denial/source %)))
               results)))

(defn- tool-results-summary
  "The user-facing line when every tool call was denied: a clean sentence,
   never raw EDN. The provider wire keeps its own format; this text is the
   reply the human reads."
  [results]
  (let [names (->> results (map :tool/name) distinct (str/join ", "))]
    (str "🚫 " names " needs your approval — it's waiting right now. "
         "Reply `approve` (or tap Allow on the request) to let it run, "
         "`deny` to refuse. The request stays open for up to 10 minutes.")))

(defn- safe-emit
  "Best-effort status event. A broken status surface must never kill the turn."
  [cfg event]
  (when-let [emit (:status/emit cfg)]
    (try
      (emit event)
      (catch Exception e
        (binding [*out* *err*]
          (println (str "status emit failed: " (.getMessage e))))))))

(defn- memory-system-message [matches]
  {:role :system
   :content (str "Relevant memories:\n"
                 (str/join "\n" (map :memory/text matches)))})

(defn- semantic-system-message [context]
  {:role :system :content context})

(defn- brain-system-message [brain]
  {:role :system
   :content (str "Agent brain (identity & conventions):\n" brain)})

(defn- history-messages
  "Prior session turns as alternating user/assistant wire messages.
   Bounded by dynamic model-aware budget (or explicit :history-budget-chars):
   over budget, old turns are incrementally compacted via durable checkpoints
   in bb-agent.compression so unchanged history is never re-summarized."
  ([cfg session-id] (history-messages cfg session-id 0))
  ([cfg session-id overhead-chars]
   (when session-id
     (let [indexed-turns (->> (session/load-turns session-id)
                              (map-indexed (fn [idx t] (assoc t :turn/index idx)))
                              (filter (fn [{:keys [user/input assistant/final]}]
                                        (and (seq input) (seq final))))
                              vec)
           pairs (->> indexed-turns
                      (mapcat (fn [{:keys [user/input assistant/final]}]
                                [{:role :user :content input}
                                 {:role :assistant :content final}]))
                      vec)]
       (when (seq pairs)
         (let [budget (compression/history-budget-chars cfg overhead-chars)]
           (if (<= (compression/count-chars pairs) budget)
             pairs
             (compression/compress-session-turns!
              session-id
              indexed-turns
              {:tail-char-budget (max 200 (quot budget 4))
               :call-llm-fn (fn [prompt]
                              (:content
                               (complete-chain
                                cfg
                                {:provider (:provider cfg)
                                 :model (:model cfg)
                                 :messages [{:role :user :content prompt}]
                                 :provider/config (provider-config-for cfg (:provider cfg))})))}))))))))

(defn- skills-system-message [skills-ctx]
  {:role :system
   :content skills-ctx})

(defn- initial-messages [prompt memory-matches semantic-ctx brain-ctx skills-ctx history]
  (cond-> []
    (seq memory-matches) (conj (memory-system-message memory-matches))
    semantic-ctx (conj (semantic-system-message semantic-ctx))
    (seq brain-ctx) (conj (brain-system-message brain-ctx))
    (seq skills-ctx) (conj (skills-system-message skills-ctx))
    true (into (or history []))
    true (conj {:role :user :content prompt})))

(defn- tool-call-message [tool-requests]
  {:role :assistant
   :tool/requests tool-requests})

(defn- tool-result-message [tool-results]
  {:role :tool
   :content (pr-str tool-results)
   :tool/results tool-results})

(defn- append-tool-round [turn response tool-results]
  (-> turn
      (update :tool/requests into (or (:tool/requests response) []))
      (update :tool/results into tool-results)))

(defn- continue-messages [messages response tool-results]
  (cond-> messages
    (seq (:tool/requests response)) (conj (tool-call-message (:tool/requests response)))
    (seq tool-results) (conj (tool-result-message tool-results))))

(defn- finish-turn [id cfg prompt memory-matches semantic-ctx turn final-content usage]
  (let [completed (assoc turn
                         :session/id id
                         :user/input prompt
                         :provider (or (:provider turn) (:provider cfg))
                         :model (:model cfg)
                         :memory/backend (memory/backend)
                         :memory/matches memory-matches
                         :semantic/context semantic-ctx
                         :assistant/final final-content)]
    (session/touch-metadata! id cfg)
    (session/append-turn! id completed)
    (when (and (semantic-memory/enabled? cfg)
               (not (:session/shared? cfg)))
      (semantic-memory/index-session! id))
    ;; Test doubles are not usage: a :fake provider turn stays out of the
    ;; live ledger. 441 fixture events once rode test-suite runs into the
    ;; real usage.edn and bent every rate the RSI digest computed — the
    ;; goal runner's own act gates wrote them, 12 per attempt.
    (when (not= :fake (or (:provider turn) (:provider cfg)))
      (let [prev-prompts (when id
                           (mapv :user/input (take-last 4 (butlast (session/load-turns id)))))
            signals (turn-signals/extract-turn-signals
                     {:prompt prompt
                      :prev-prompts prev-prompts
                      :tool-results (:tool/results completed)})]
        (usage/append-event! (usage/event {:session-id id
                                           :provider (or (:provider turn) (:provider cfg))
                                           :model (:model cfg)
                                           :prompt prompt
                                           :final final-content
                                           :usage usage
                                           :fallback-tried (not-empty (:fallback/tried turn))
                                           :fallback-served (:fallback/served-by turn)
                                           :fallback-model (:fallback/model turn)
                                           :ok (:turn/ok completed true)
                                           :signals signals}))))
    completed))

(defn- vision-chain-cfg
  "Image turns with :vision-chain configured: each entry {:provider .. :model ..}
   becomes a chain step, resolved against :providers like any fallback entry.
   The head serves as primary; the tail prepends the generic :provider/fallbacks.
   Resolution is per-request — a vision model's death advances the chain instead
   of killing the turn. No chain (or no images): cfg unchanged, the legacy
   single :vision-model path."
  [cfg]
  (let [chain (:vision-chain cfg)]
    (if (and (seq (:user/images cfg)) (seq chain))
      (let [entries (mapv (fn [{:keys [provider model]}]
                            {:provider (keyword provider) :model model})
                          chain)
            head (first entries)]
        (-> cfg
            (assoc :provider (:provider head)
                   :model (:model head)
                   :provider/fallbacks (into (vec (rest entries))
                                             (:provider/fallbacks cfg)))))
      cfg)))

(def ^:private default-phantom-nudge-threshold 2)

(defn- phantom-tool-text
  "The tool name when trimmed content IS a bare tool call — exactly a known
   tool name (`write_file`) or a call shape (`write_file(...)`,
   `write_file {...}`). Prose mentioning tools never matches: a false
   positive would nudge a legitimate final answer."
  [content]
  (when (string? content)
    (let [t (str/trim content)]
      (some (fn [d]
              (let [name (get-in d [:function :name])]
                (when (and name
                           (or (= t name)
                               (and (str/starts-with? t (str name "("))
                                    (str/ends-with? t ")"))
                               (and (str/starts-with? t (str name " {"))
                                    (str/ends-with? t "}"))))
                  name)))
            tools/definitions))))

(defn- phantom-nudge-message [tool-name streak threshold]
  (str "⚠️ You emitted `" tool-name "` as plain message text — that is not a tool call. "
       "Tool calls must go through the tool channel; text shaped like a call does nothing. "
       "Re-emit it as a proper tool call, or answer in prose if you meant to explain. "
       "Phantom text-call " streak " of " threshold
       " — after that, text like this is accepted as the final answer."))

(def ^:private cacheable-read-tools
  #{"read_file" "search" "git_status" "document_read" "read_session_turns" "query_graph"})

(def ^:private live-tool-keep-recent 4)

(defn run-turn! [cfg prompt]
  (redact/register-config-secrets! cfg)
  (let [cfg (model/effective-config cfg)
        cfg (vision-chain-cfg cfg)
        saver-state (saver/evaluate cfg)
        cfg (saver/apply-cfg cfg saver-state)
        max-rounds (or (:max-tool-rounds cfg) default-max-tool-rounds)
        nudge-interval (:nudge-interval cfg)
        nudge-final-rounds (or (:nudge-final-rounds cfg) default-nudge-final-rounds)
        provider (:provider cfg)
        id (:session/id cfg)
        user-images (:user/images cfg)
        model (cond
                (and (seq user-images) (seq (:vision-chain cfg)))
                (:model cfg)
                (and (seq user-images) (:vision-model cfg))
                (:vision-model cfg)
                :else (:model cfg))
        metadata (session/load-metadata id)
        cfg (-> cfg
                (cond-> (:cwd metadata) (assoc :cwd (:cwd metadata)))
                ;; Record the model that will actually serve the turn —
                ;; image turns use :vision-model, and usage stats must not
                ;; credit the primary model for a fallback/vision serving.
                (assoc :model model))
        shared? (:session/shared? cfg)
        memory-matches (if shared? [] (memory/attach-memories prompt))
        semantic-ctx (when-not shared?
                       (semantic-memory/attach-context prompt cfg))
        brain-ctx (brain/load-brain-context)
        skills-ctx (skill/skills-summary)
        ;; One registry decision per turn, from config: optional capabilities
        ;; (web_search, browser_read, a2a_task) stay dormant unless
        ;; :tools/enable-optional opts them in (owner directive 2026-11-12).
        active-tools (tools/active-definitions cfg)
        overhead-chars (+ (count (str (or brain-ctx "")))
                          (count (str (or semantic-ctx "")))
                          (count (str (or skills-ctx "")))
                          (reduce + 0 (map #(count (str (:memory/text % "")))
                                           memory-matches)))
        live-budget (compression/history-budget-chars cfg overhead-chars)
        plan-ctx (plan/plan-state-block id)
        base-messages (initial-messages prompt memory-matches semantic-ctx brain-ctx skills-ctx
                                        (history-messages cfg id overhead-chars))
        init-messages (cond-> base-messages
                        plan-ctx (into [{:role :system :content plan-ctx}])
                        (:saver/active? cfg) (into [{:role :system :content saver/system-hint}]))
        heartbeat (atom (System/currentTimeMillis))
        skill-seen (or (:skill/seen cfg) (atom #{}))
        repl-evals (or (:repl/evals cfg) (atom 0))
        repl-enforce? (if (contains? cfg :repl/enforce) (:repl/enforce cfg) true)]
    (ti/with-stall-watchdog
     cfg heartbeat
     (fn []
       (loop [messages init-messages
              turn {:tool/requests []
                    :tool/results []}
              rounds-left max-rounds
              seen-calls {}
              read-cache {}
              exposure (sec/note-input-urls (or (:security/exposure cfg) sec/empty-exposure) prompt)
              discovered-tools (set (:tools/discovered cfg))
              final-nudge-used? false
              reasoning-streak 0
              phantom-streak 0
              truncation-nudges 0
              execute-nudges 0]
         (reset! heartbeat (System/currentTimeMillis))
         (when (neg? rounds-left)
           (throw (ex-info "Exceeded tool rounds" {:rounds max-rounds
                                                   :final-nudge-used? final-nudge-used?})))
         (let [consumed (max 0 (- max-rounds rounds-left))
               nudge-msg (when (and nudge-interval
                                    (pos? consumed)
                                    (zero? (mod consumed nudge-interval))
                                    (not final-nudge-used?))
                           (checkpoint-nudge-message consumed max-rounds))
               messages (cond-> messages nudge-msg (conj {:role :user :content nudge-msg}))
               messages (if (> (compression/count-chars messages) live-budget)
                          (compression/prune-old-tool-results messages live-tool-keep-recent)
                          messages)
               round-cfg (cond-> cfg
                           (nil? (:skill/seen cfg)) (assoc :skill/seen skill-seen)
                           (nil? (:repl/evals cfg)) (assoc :repl/evals repl-evals)
                           true (assoc :repl/enforce repl-enforce?)
                           true (assoc :turn/tool-calls (count (:tool/results turn)))
                           (seq discovered-tools) (assoc :tools/discovered discovered-tools))
               round-tools (if (seq discovered-tools)
                             (tools/active-definitions round-cfg)
                             active-tools)
               pressure-nudge (trunc/context-pressure-nudge (compression/count-chars messages) live-budget)
               request-messages (cond-> messages
                                  pressure-nudge (conj {:role :system :content pressure-nudge}))
               request {:provider provider
                        :model model
                        :messages request-messages
                        :memory/matches memory-matches
                        :provider/config (provider-config-for cfg provider)
                        :provider/tools round-tools}
               request (cond-> request (seq user-images) (assoc :images user-images))
               response (try
                          (complete-chain cfg request)
                          (catch Exception e
                            (if (tool-repeat/repetitive-tool-error? (ex-message e))
                              (let [{pruned :messages removed :removed}
                                    (tool-repeat/prune-repetitive-tool-calls messages)]
                                (if (pos? removed)
                                  (complete-chain cfg (assoc request :messages pruned))
                                  (throw e)))
                              (throw e))))
               _ (reset! heartbeat (System/currentTimeMillis))
               known-names (set (map #(get-in % [:function :name]) round-tools))
               rescued-call (when (empty? (:tool/requests response))
                              (ti/extract-bare-tool-call (:content response) known-names))
               response (if rescued-call
                          (assoc response :tool/requests [rescued-call])
                          response)
               tool-requests (:tool/requests response)
               truncated? (= :truncated (ti/classify-finish-reason (:finish/reason response)))
               threshold (or (:loop-guard-threshold cfg) default-loop-guard-threshold)
               ;; The nudge can never sit at/above the kill line — a misconfig
               ;; degrades to nudge-one-before-kill instead of silently
               ;; disabling the warning stage.
               nudge-threshold (min (or (:loop-guard-nudge-threshold cfg)
                                        default-loop-guard-nudge-threshold)
                                    (dec threshold))
               repeated (some (fn [req]
                                (let [k [(:tool/name req) (canonical-args (:tool/args req))]]
                                  (when (>= (get seen-calls k 0) (dec threshold)) k)))
                              tool-requests)]
           (if repeated
             ;; Identical call Nth time: report raw dropped call(s) & stop cleanly.
             (let [[r-tool _] repeated
                   pending (loop-break/describe-pending-calls tool-requests)
                   tried-n (inc (count (:fallback/tried turn)))]
               (finish-turn id cfg prompt memory-matches semantic-ctx
                            (assoc turn :turn/ok false)
                            (loop-break/chain-exhausted-message r-tool threshold pending tried-n)
                            (:usage response)))
             (let [can-parallel-read?
                   (and (>= (count tool-requests) 2)
                        (every? (fn [req]
                                  (let [tname (:tool/name req)
                                        k [tname (canonical-args (:tool/args req))]
                                        decision-req (if (and (nil? (:approval/policy req))
                                                              (:approval/mode round-cfg))
                                                       (assoc req :approval/policy (:approval/mode round-cfg))
                                                       req)]
                                    (and (contains? cacheable-read-tools tname)
                                         (not (contains? read-cache k))
                                         (not (and (pos? (get seen-calls k 0))
                                                   (>= (get seen-calls k 0) (dec nudge-threshold))))
                                         (= :approved (tool/approval-decision decision-req)))))
                                tool-requests))
                   {:keys [results cache exp]}
                   (cond
                     can-parallel-read?
                     (let [futs (mapv (fn [req]
                                        (let [tname (:tool/name req)]
                                          (safe-emit cfg {:status :tool/call :tool tname :args (:tool/args req)})
                                          (future
                                            (try
                                              (tool/handle-tool-request req (assoc round-cfg :security/exposure exposure))
                                              (catch Exception e
                                                {:tool/name tname
                                                 :status :error
                                                 :executed? false
                                                 :error/message (or (ex-message e) (.getName (class e)))})))))
                                      tool-requests)
                           par-results (mapv deref futs)]
                       (reset! heartbeat (System/currentTimeMillis))
                       (reduce
                        (fn [{:keys [results cache exp]} [req result]]
                          (let [tname (:tool/name req)
                                k [tname (canonical-args (:tool/args req))]
                                [exp* _scan] (sec/note-exposure exp tname result)]
                            (safe-emit cfg {:status :tool/done
                                            :tool tname
                                            :args {:ok? (= :ok (:status result))}})
                            {:results (conj results result)
                             :cache (if (= :ok (:status result))
                                      (assoc cache k result)
                                      cache)
                             :exp exp*}))
                        {:results [] :cache read-cache :exp exposure}
                        (map vector tool-requests par-results)))

                     (seq tool-requests)
                     (reduce
                      (fn [{:keys [results cache exp]} req]
                        (let [tname (:tool/name req)
                              k [tname (canonical-args (:tool/args req))]
                              cacheable? (contains? cacheable-read-tools tname)]
                          (cond
                            (and (pos? (get seen-calls k 0))
                                 (>= (get seen-calls k 0) (dec nudge-threshold)))
                            (do (safe-emit cfg {:status :tool/call :tool tname :args (:tool/args req)})
                                (safe-emit cfg {:status :tool/done :tool tname :args {:ok? false :loop-guard :nudged}})
                                {:results (conj results
                                                {:tool/name tname
                                                 :status :error
                                                 :executed? false
                                                 :loop-guard :nudged
                                                 :error/message (loop-guard-nudge-message
                                                                 tname
                                                                 (inc (get seen-calls k 0))
                                                                 threshold)})
                                 :cache cache
                                 :exp exp})

                            (and cacheable? (contains? cache k))
                            (do (safe-emit cfg {:status :tool/call :tool tname :args (:tool/args req)})
                                (safe-emit cfg {:status :tool/done :tool tname :args {:ok? true :cached? true}})
                                {:results (conj results (assoc (get cache k) :cached? true))
                                 :cache cache
                                 :exp exp})

                            :else
                            (do (safe-emit cfg {:status :tool/call :tool tname :args (:tool/args req)})
                                (let [result (tool/handle-tool-request req (assoc round-cfg :security/exposure exp))
                                      [exp* _scan] (sec/note-exposure exp tname result)]
                                  (reset! heartbeat (System/currentTimeMillis))
                                  (safe-emit cfg {:status :tool/done
                                                  :tool tname
                                                  :args {:ok? (= :ok (:status result))}})
                                  {:results (conj results result)
                                   :cache (cond
                                            (and cacheable? (= :ok (:status result)))
                                            (assoc cache k result)
                                            (not cacheable?) {}
                                            :else cache)
                                   :exp exp*})))))
                      {:results [] :cache read-cache :exp exposure}
                      tool-requests)

                     :else
                     {:results nil :cache read-cache :exp exposure})
                   tool-results results
                   read-cache* cache
                   exposure* exp
                   discovered-tools* (reduce (fn [acc r]
                                               (if (seq (:discovered r))
                                                 (into acc (:discovered r))
                                                 acc))
                                             discovered-tools
                                             (or tool-results []))
                   turn* (cond-> (append-tool-round turn response (or tool-results []))
                           (:fallback/tried response)
                           (assoc :fallback/tried (:fallback/tried response))
                           (:fallback/served-by response)
                           (assoc :provider (:fallback/served-by response)
                                  :fallback/served-by (:fallback/served-by response)
                                  :fallback/model (:fallback/model response)))
                   seen-calls* (reduce (fn [m req]
                                         (update m [(:tool/name req) (canonical-args (:tool/args req))]
                                                 (fnil inc 0)))
                                       seen-calls (or tool-requests []))]
               (if (seq tool-requests)
                 (if (awaiting-human? tool-results)
                   (finish-turn id cfg prompt memory-matches semantic-ctx turn* (tool-results-summary tool-results) (:usage response))
                   (let [rounds-next (dec rounds-left)
                         extend? (and (neg? rounds-next)
                                      (pos? nudge-final-rounds)
                                      (not final-nudge-used?))]
                     (recur (cond-> (continue-messages messages response tool-results)
                              (and truncated? (zero? truncation-nudges))
                              (conj {:role :user :content (ti/truncation-recovery-prompt tool-requests)})
                              extend? (conj {:role :user
                                             :content (final-nudge-message nudge-final-rounds)}))
                            turn*
                            (if extend? nudge-final-rounds rounds-next)
                            seen-calls*
                            read-cache*
                            exposure*
                            discovered-tools*
                            (or extend? final-nudge-used?)
                            0
                            0
                            (if (and truncated? (zero? truncation-nudges))
                              (inc truncation-nudges)
                              truncation-nudges)
                            execute-nudges)))
                 (if-let [content (:content response)]
                   (let [phantom (phantom-tool-text content)
                         pthreshold (or (:phantom-nudge-threshold cfg)
                                        default-phantom-nudge-threshold)
                         pstreak (inc phantom-streak)]
                     (cond
                       (and truncated? (zero? truncation-nudges))
                       (recur (-> messages
                                  (conj {:role :assistant :content content})
                                  (conj {:role :user :content (ti/truncation-recovery-prompt nil)}))
                              turn*
                              (dec rounds-left)
                              seen-calls*
                              read-cache*
                              exposure*
                              discovered-tools*
                              final-nudge-used?
                              0
                              phantom-streak
                              (inc truncation-nudges)
                              execute-nudges)

                       (and phantom (< pstreak pthreshold))
                       (recur (conj messages {:role :user
                                              :content (phantom-nudge-message phantom pstreak pthreshold)})
                              turn*
                              (dec rounds-left)
                              seen-calls*
                              read-cache*
                              exposure*
                              discovered-tools*
                              final-nudge-used?
                              0
                              pstreak
                              truncation-nudges
                              execute-nudges)

                       (and (zero? execute-nudges)
                            (ti/narration-without-mutation? prompt turn* content))
                       (recur (-> messages
                                  (conj {:role :assistant :content content})
                                  (conj {:role :user :content ti/execute-guard-nudge-message}))
                              turn*
                              (dec rounds-left)
                              seen-calls*
                              read-cache*
                              exposure*
                              discovered-tools*
                              final-nudge-used?
                              0
                              phantom-streak
                              truncation-nudges
                              (inc execute-nudges))

                       :else
                       (let [any-tool-ok? (some #(= :ok (:status %)) (:tool/results turn*))
                             final-content (if any-tool-ok?
                                             (or (gas/strip-gaslighting-preamble content) content)
                                             content)]
                         (finish-turn id cfg prompt memory-matches semantic-ctx
                                      (cond-> turn*
                                        (pos? phantom-streak)
                                        (assoc :turn/phantom-nudged phantom-streak))
                                      final-content (:usage response)))))
                   (let [streak (inc reasoning-streak)
                         rthreshold (or (:reasoning-nudge-threshold cfg)
                                        default-reasoning-nudge-threshold)]
                     (if (< streak rthreshold)
                       (recur (conj messages {:role :user
                                              :content (reasoning-nudge-message streak rthreshold)})
                              turn*
                              (dec rounds-left)
                              seen-calls*
                              read-cache*
                              exposure*
                              discovered-tools*
                              final-nudge-used?
                              streak
                              phantom-streak
                              truncation-nudges
                              execute-nudges)
                       (if-let [receipt (ti/synthesize-silent-receipt turn*)]
                         (finish-turn id cfg prompt memory-matches semantic-ctx
                                      (assoc turn* :turn/ok true :turn/synthesized-receipt? true)
                                      receipt (:usage response))
                         (finish-turn id cfg prompt memory-matches semantic-ctx
                                      (assoc turn* :turn/ok false)
                                      (reasoning-death-message streak) (:usage response)))))))))))))))