(ns bb-agent.core
  (:require [bb-agent.brain :as brain]
            [bb-agent.circuit-breaker :as cb]
            [bb-agent.compression :as compression]
            [bb-agent.fallback :as fallback]
            [bb-agent.memory :as memory]
            [bb-agent.model :as model]
            [bb-agent.provider :as provider]
            [bb-agent.retry :as retry]
            [bb-agent.semantic-memory :as semantic-memory]
            [bb-agent.session :as session]
            [bb-agent.skill :as skill]
            [bb-agent.tool :as tool]
            [bb-agent.tools :as tools]
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

(defn- loop-guard-message
  [[tool-name args]]
  (str "⚠️ Loop guard: `" tool-name "` repeated with identical arguments "
       "(" args ") — stopping this turn instead of burning more rounds. "
       "Narrow or rephrase the request."))

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
  config.edn may override retry knobs via a `:retry` map."
  [cfg provider request]
  (let [opts (-> (retry/defaults)
                 (merge (:retry cfg))
                 (assoc :breaker @provider-breaker
                        :breaker-key provider))
        result (retry/with-retries opts #(provider/complete provider request))]
    (when (:breaker result) (reset! provider-breaker (:breaker result)))
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

(defn- fallback-steps [cfg base-request]
  (let [primary {:provider (:provider base-request)
                 :model (:model base-request)
                 :provider/config (:provider/config base-request)}]
    (into [primary]
          (map (fn [fb]
                 (let [p (keyword (:provider fb))]
                   {:provider p
                    :model (:model fb)
                    :provider/config (get-in cfg [:providers p])})))
          (:provider/fallbacks cfg))))

(defn- complete-chain
  "Primary provider first (retries + breaker intact), then
  :provider/fallbacks as data — each step its own provider, model,
  and provider config. Nothing configured: exactly the old
  single-provider path, byte for byte."
  [cfg base-request]
  (let [steps (fallback-steps cfg base-request)]
    (if (= 1 (count steps))
      (complete-retrying cfg (:provider base-request) base-request)
      (fallback/try-chain steps
                          #(complete-retrying cfg (:provider %)
                                              (request-for base-request %))))))

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
   Bounded by :history-budget-chars (default 24000): over budget, old
   middle turns are compacted by bb-agent.compression — head kept, the
   most recent ~25% kept verbatim, tool payloads pruned, the middle
   summarized through the provider chain."
  [cfg session-id]
  (when session-id
    (let [pairs (->> (session/load-turns session-id)
                     (keep (fn [{:keys [user/input assistant/final]}]
                             (when (and (seq input) (seq final))
                               [{:role :user :content input}
                                {:role :assistant :content final}])))
                     (apply concat)
                     vec)]
      (when (seq pairs)
        (let [budget (or (:history-budget-chars cfg) 24000)]
          (if (<= (compression/count-chars pairs) budget)
            pairs
            (compression/compress pairs
                                  {:protect-first 0
                                   :tail-char-budget (max 200 (quot budget 4))
                                   :call-llm-fn (fn [prompt]
                                                  (:content
                                                   (complete-chain
                                                    cfg
                                                    {:provider (:provider cfg)
                                                     :model (:model cfg)
                                                     :messages [{:role :user :content prompt}]
                                                     :provider/config (get-in cfg [:providers (:provider cfg)])})))})))))))

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
    (usage/append-event! (usage/event {:session-id id
                                       :provider (or (:provider turn) (:provider cfg))
                                       :model (:model cfg)
                                       :prompt prompt
                                       :final final-content
                                       :usage usage
                                       :fallback-tried (not-empty (:fallback/tried turn))
                                       :fallback-served (:fallback/served-by turn)
                                       :ok (:turn/ok completed true)}))
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

(defn run-turn! [{:keys [provider model session/id] :as cfg} prompt]
  (let [cfg (model/effective-config cfg)
        cfg (vision-chain-cfg cfg)
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
        skills-ctx (skill/skills-summary)]
    (loop [messages (initial-messages prompt memory-matches semantic-ctx brain-ctx skills-ctx
                                      (history-messages cfg id))
           turn {:tool/requests []
                 :tool/results []}
           rounds-left max-rounds
           seen-calls {}
           final-nudge-used? false
           reasoning-streak 0
           phantom-streak 0]
      (when (neg? rounds-left)
        (throw (ex-info "Exceeded tool rounds" {:rounds max-rounds
                                                :final-nudge-used? final-nudge-used?})))
      (let [consumed (max 0 (- max-rounds rounds-left))
            nudge-msg (when (and nudge-interval
                                 (pos? consumed)
                                 (zero? (mod consumed nudge-interval))
                                 (not final-nudge-used?))
                        (checkpoint-nudge-message consumed max-rounds))
            messages (cond-> messages nudge-msg (conj {:role "user" :content nudge-msg}))
            request {:provider provider
                     :model model
                     :messages messages
                     :memory/matches memory-matches
                     :provider/config (get-in cfg [:providers provider])}
            request (cond-> request (seq user-images) (assoc :images user-images))
            response (complete-chain cfg request)
            tool-requests (:tool/requests response)
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
          ;; Identical call Nth time: stop gracefully, execute nothing.
          (finish-turn id cfg prompt memory-matches semantic-ctx
                       (assoc turn :turn/ok false)
                       (loop-guard-message repeated)
                       (:usage response))
          (let [;; One event before each call and one after its outcome — the
                ;; channel flips ⚙️→✅/❌ live inside a single flow message.
                tool-results (when (seq tool-requests)
                               (mapv (fn [req]
                                       (let [k [(:tool/name req) (canonical-args (:tool/args req))]]
                                         (if (and (pos? (get seen-calls k 0))
                                                  (>= (get seen-calls k 0) (dec nudge-threshold)))
                                           ;; Repeat below the kill line: skip
                                           ;; execution, feed the warning as the
                                           ;; tool result. pos? guard: only a call
                                           ;; that already EXECUTED can be nudged —
                                           ;; a first occurrence always runs.
                                           (do (safe-emit cfg {:status :tool/call
                                                               :tool (:tool/name req)
                                                               :args (:tool/args req)})
                                               (safe-emit cfg {:status :tool/done
                                                               :tool (:tool/name req)
                                                               :args {:ok? false
                                                                      :loop-guard :nudged}})
                                               {:tool/name (:tool/name req)
                                                :status :error
                                                :executed? false
                                                :loop-guard :nudged
                                                :error/message (loop-guard-nudge-message
                                                                (:tool/name req)
                                                                (inc (get seen-calls k 0))
                                                                threshold)})
                                           (do (safe-emit cfg {:status :tool/call
                                                               :tool (:tool/name req)
                                                               :args (:tool/args req)})
                                               (let [result (tool/handle-tool-request req cfg)]
                                                 (safe-emit cfg {:status :tool/done
                                                                 :tool (:tool/name req)
                                                                 :args {:ok? (= :ok (:status result))}})
                                                 result)))))
                                     tool-requests))
                turn* (cond-> (append-tool-round turn response (or tool-results []))
                        (:fallback/tried response)
                        (assoc :fallback/tried (:fallback/tried response))
                        (:fallback/served-by response)
                        (assoc :provider (:fallback/served-by response)
                               :fallback/served-by (:fallback/served-by response)))
                seen-calls* (reduce (fn [m req]
                                      (update m [(:tool/name req) (canonical-args (:tool/args req))]
                                              (fnil inc 0)))
                                    seen-calls (or tool-requests []))]
            (if (seq tool-requests)
              (if (every? #(= :denied (:status %)) tool-results)
                (finish-turn id cfg prompt memory-matches semantic-ctx turn* (tool-results-summary tool-results) (:usage response))
                (let [rounds-next (dec rounds-left)
                      extend? (and (neg? rounds-next)
                                   (pos? nudge-final-rounds)
                                   (not final-nudge-used?))]
                  (recur (cond-> (continue-messages messages response tool-results)
                           extend? (conj {:role "user"
                                          :content (final-nudge-message nudge-final-rounds)}))
                         turn*
                         (if extend? nudge-final-rounds rounds-next)
                         seen-calls*
                         (or extend? final-nudge-used?)
                         0
                         0)))
              (if-let [content (:content response)]
                (let [phantom (phantom-tool-text content)
                      pthreshold (or (:phantom-nudge-threshold cfg)
                                     default-phantom-nudge-threshold)
                      pstreak (inc phantom-streak)]
                  (if (and phantom (< pstreak pthreshold))
                    ;; A tool call emitted as text: nudge, don't finish — the
                    ;; model re-emits through the tool channel. Bounded: after
                    ;; the threshold the text is accepted as the final answer.
                    (recur (conj messages {:role "user"
                                           :content (phantom-nudge-message phantom pstreak pthreshold)})
                           turn*
                           (dec rounds-left)
                           seen-calls*
                           final-nudge-used?
                           0
                           pstreak)
                    (finish-turn id cfg prompt memory-matches semantic-ctx
                                 (cond-> turn*
                                   (pos? phantom-streak)
                                   (assoc :turn/phantom-nudged phantom-streak))
                                 content (:usage response))))
                (let [streak (inc reasoning-streak)
                      rthreshold (or (:reasoning-nudge-threshold cfg)
                                     default-reasoning-nudge-threshold)]
                  (if (< streak rthreshold)
                    (recur (conj messages {:role "user"
                                           :content (reasoning-nudge-message streak rthreshold)})
                           turn*
                           (dec rounds-left)
                           seen-calls*
                           final-nudge-used?
                           streak
                           phantom-streak)
                    (finish-turn id cfg prompt memory-matches semantic-ctx (assoc turn* :turn/ok false) (reasoning-death-message streak) (:usage response)))))))))))
)