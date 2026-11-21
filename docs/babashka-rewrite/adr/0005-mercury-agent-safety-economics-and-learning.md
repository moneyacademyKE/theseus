# ADR 0005: Mercury-Agent Safety, Token Economics, Turn Integrity, and Self-Learning

## Status

Accepted.

## Context

Inspection of `cosmicstack-labs/mercury-agent` against Theseus (`35a7427`) identified 15 concrete, zero-dependency, pure-functional capabilities missing from Theseus's security, provider economics, turn-integrity, and memory layers:

1. **Security & taint boundary:** Static per-call approval ignored whether a turn had read untrusted external content; subagents did not inherit parent taint; no zero-LLM prompt-injection spotlighting or outbound URL/shell exfiltration detection existed; fallback to smaller models kept the same trust posture; and custom runtime secrets from `config.edn` were not registered for scrubbing at the `tool.clj` return funnel and `provider.clj` error bodies.
2. **Provider & token economics:** `:anthropic-compatible` parsed and priced cache tokens but never emitted `cache_control: {:type "ephemeral"}`; optional tool schemas could not be deferred behind `discover_tools`; and interactive turns had no soft daily-budget Token Saver Mode.
3. **Turn integrity & reliability:** Both provider adapters dropped `finish_reason` / `stop_reason`; prose-only fake completions on edit prompts passed as `:turn/ok true`; productive silent turns died with `reasoning-death-message`; interactive turns lacked a two-stage stall watchdog; and ungraceful mid-cycle shutdowns left no crash-flag survival record.
4. **Memory & self-learning:** `memory.clj` stored flat rows with no claim deduplication or contradiction handling, and no zero-LLM behavioural turn signals fed RSI telemetry.

## Decision

Implement all 15 capabilities across four sequential Red/Green TDD commits (`48260af`, `bfefb69`, `699318f`, and Wave D) with zero new dependencies:

- **Security & redaction (`bb-agent.redact`, `bb-agent.security`):** Centralize regex + runtime-registered literal secret scrubbing at `tool.clj`, `provider.clj`, and `session.clj`. Track pure EDN turn exposure (`:sources`, `:external?`, `:suspicious?`, `:signals`, `:seen-urls`) across `core.clj` and `subagent.clj` (`:subagent/exposure`), with zero-LLM injection spotlighting (`:security/spotlight`, byte-exact content preserved), Shannon-entropy + seen-URL + shell-upload exfil detection, and fail-closed model-strength tiering.
- **Economics (`bb-agent.provider`, `bb-agent.tools`, `bb-agent.saver-mode`):** Emit Anthropic ephemeral `cache_control` breakpoints on `:system` and the newest 2 messages of multi-turn payloads; add auto-safe `discover_tools` when `:tools/defer-optional true`; and add soft daily-budget degradation (`:off`/`:on`/`:auto` at 75% budget) scaling rounds, history, and max tokens.
- **Turn integrity (`bb-agent.turn-integrity`, `bb-agent.crash-flag`):** Capture `:finish/reason` and inject sectioned-write recovery prompts on `:length`/`max_tokens` truncation; add execute-mode narration guard (bounded 1 nudge); synthesize factual bulleted receipts on productive silent turns; wrap `run-turn!` in a two-stage daemon stall watchdog (`:turn/still-working`, `:turn/stalled`); and persist born-`0600` `state/.crash-flag.edn` on mid-cycle shutdown, consumed once on boot.
- **Memory & learning (`bb-agent.beliefs`, `bb-agent.turn-signals`):** Upsert memories via pure light-stem + synonym + negation polarity matching (`:candidate` → `:held` → `:confirmed`, non-destructive `:contested` on contradictions excluded from `search-memories`), and record compact zero-LLM turn friction signals (`:turn/signals`) on `usage.edn` turn events.

## Consequences

- `bb-agent.security-test`, `bb-agent.economics-test`, `bb-agent.turn-integrity-test`, and `bb-agent.learning-test` are registered in `bb.edn` (`test:bb-agent`), keeping `bb test-roster` at `100%` (`125/125`, `0 never-run`).
- `memory.sqlite` gains idempotent `status` and `evidence_count` columns via `ALTER TABLE`.
- `state/.crash-flag.edn` rides dotfile hygiene exclusion in `state_hygiene.clj`.
