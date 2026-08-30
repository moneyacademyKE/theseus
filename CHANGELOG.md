# Changelog

## Unreleased

### Added

- Policy predicates (`bb-agent.policy` + `bb-agent.tool` wiring): `<home>/brain/rules.clj` — approval gates as sci-evaluated code instead of prose. First matching predicate `(fn [tool args])` returns `:allow`/`:deny` and replaces the approval classifier verdict; everything else — disabled, no file, unparseable, throwing, or runaway pred (500ms deref bound on both file eval and pred execution) — fails to baseline, so a broken rules file can make the agent neither more permissive nor more restrictive than configured. Gated by `:policy {:enabled true}` (default off). Sandbox contract pinned by test: sci default context, no io/interop/threads (this sci build ignores `:timeout-ms` — verified empirically — hence the deref futures).

### Verification

- `bb test:e2e:all` passes (106 tests, 479 assertions, 0 failures; prior: 97/455), now 20 suites including `test:e2e:policy` (9/24, two subprocess e2e through the fake provider).
- Zero new dependencies; policy.clj 62 LOC, tool.clj 50 LOC.

## v0.5.0 - 2026-08-30

### Added

- Doctor hardening + last-good config swap (`bb-agent.doctor` + `bb-agent.config`): the doctor now survives a corrupt `config.edn` (it previously crashed with empty output on exactly the condition it exists to diagnose) and reports `:config-parse`, `:home-writable`, `:memory-store`, `:semantic-store`, `:usage-store` alongside the original checks; `bb config apply <file>` validates a candidate against the provider-config rules before an atomic temp+rename swap that keeps the previous config at `config.last-good.edn`; `bb config restore-last-good` recovers. Ported from OpenCrabs' doctor/last-good patterns.
- Provider reachability probe + top-level `bb doctor` (plan-worker delta): the fake provider is probed in-process (offline, deterministic); http base-urls are probed with 1.5s connect / 3s request timeouts and an unreachable endpoint degrades to `[WARN]`, never an error exit.
- Provider attribution stats (`bb-agent.usage`): usage events carry `:fallback/tried` + `:fallback/served`; `bb usage report` gains `:fallback {hits, rate, by-served}` — the honesty fix from v0.4.0 becomes measurable.
- Brain files (`bb-agent.brain`): `<home>/brain/*.md` loaded sorted under filename headers into system context — identity and conventions live as editable markdown; the presence of files is the gate, no config flag. Memory = what happened, brain = who the agent is.

### Verification

- `bb test:e2e:all` passes (97 tests, 455 assertions, 0 failures; prior release: 82/391), now 19 suites including `test:e2e:doctor` (8/38), `test:e2e:stats`, `test:e2e:brain`.
- Zero new dependencies (`babashka.http-client` is built into Babashka); doctor.clj 149 LOC, brain.clj 25 LOC.

## v0.4.0 - 2026-08-29

### Added

- Cron gating for schedules (`bb-agent.schedule` + `bb-agent.cron`): schedules accept a 5-field cron expression and fire only within their `(last-run, now]` catch-up window; runs log `:at` stamps; run clock injectable for DST-safe testing.
- Provider fallback chain as data (`bb-agent.fallback`): ordered second opinions walked until one answers, every departure classified (`bb-agent.error-classifier`) and recorded in `:fallback/tried`, per-step retries and breakers unchanged; turns and usage events record the provider that actually answered (`:fallback/served-by`).
- Curated memory tier (`bb-agent.memory`): entries carry `:memory/kind` (`:raw` default, `:curated` via `bb memory curate <id>`), curated entries rank before raw ones regardless of score; idempotent sqlite migration adds the `kind` column.

### Verification

- `bb test:e2e:all` passes (82 tests, 391 assertions, 0 failures; prior release: 73/360), now 16 suites including `test:e2e:cron-schedule` and `test:e2e:fallback`.

## v0.3.0 - 2026-08-23

### Added

- Cross-session semantic memory (`bb-agent.semantic-memory`), ported from hermes-beam's `semantic_search` + `cross_session_search`: per-session summary records (deterministic transcript summarizer, injectable), BM25/IDF ranking with age decay as the default scorer (injectable `:score-fn`), automatic re-index after each completed turn behind a `:semantic-memory {:enabled true}` config gate, historical-context injection into turn prompts, and `bb memory index-session <id>` / `bb memory semantic-search <query>` CLI commands.

### Verification

- `bb test:e2e:all` passes (73 tests, 360 assertions, 0 failures; prior release: 61/325).
- CI green on `c46fa70` (run 32620312885), first run, no fixes needed.
- Module 179 LOC, zero new dependencies; opt-in via config — no behavior change by default.

## v0.2.0 - 2026-08-23

### Added

- Retry/backoff executor (`bb-agent.retry`): pure opts+thunk loop composing the error classifier and circuit breaker — capped attempts, jittered exponential backoff, clock/sleep/rand injectable. Wired into `core/run-turn!` via `complete-retrying` with a shared per-provider breaker; config `:retry` map overrides knobs. Ported shape from hermes-beam's provider retry loop.
- Pure cron matcher (`bb-agent.cron`) over `java.time` ZonedDateTime: hermes-beam's expression semantics (wildcards, lists, ranges, steps, Sunday 0/7) plus `next-match` / `matches-in-window` for DST-gap, ambiguous-hour, catch-up, and drift-anchoring behavior.
- Subagent ledger (`bb-agent.subagent`): hermes-beam's 529-LOC OTP supervisor flattened to pure record transitions (:pending -> :claimed -> :completed/:failed), id-or-record handles, capacity as data (4-arity claim + `can-claim?`), file-backed shell at `state/subagents.edn`.
- `bb benchmark`: exit-0 offline benchmark harness (4 scenarios x 3 iterations, deterministic fake provider + flaky loopback server), shape ported from AlcaponeCoder's benchmark.clj.
- `bb test:e2e:retry`, `bb test:e2e:cron-edge`, `bb test:e2e:subagent` suites, all wired into `test:e2e:all`.

### Verification

- `bb test:e2e:all` passes (61 tests, 325 assertions, 0 failures).
- `bb benchmark` passes (all 4 scenarios green; retry-absorb ~385ms mean vs ~44ms plain-turn baseline).
- Prior baseline for this branch: 46 tests, 259 assertions.

### Context compaction / resilience (earlier in this branch)

- Context compaction module (`bb-agent.compression`) ported from AlcaponeCoder, adapted to theseus message shapes; zero-dep with an injected summarizer function.
- Circuit breaker as a pure value (`bb-agent.circuit-breaker`) ported from hermes-beam's actor; identical closed/open/half-open semantics with time as an argument.
- Error classifier (`bb-agent.error-classifier`) ported from hermes-beam; pattern tables and precedence preserved as data, plus `retryable?` policy.
- `bb test:e2e:compression` and `bb test:e2e:resilience` suites, wired into `test:e2e:all`.
- GitHub Actions workflow running the full e2e suite on Babashka (adapted from hermes-beam's tests.yml).

## v0.1.0 - 2026-07-14

Initial standalone Theseus release.

### Added

- Babashka CLI agent with deterministic fake provider.
- OpenAI-compatible and Anthropic-compatible HTTP provider adapters.
- Provider tool-call parsing and tool-result continuation loop.
- Safe tool execution for shell, file read/write, search, git status, browser CLI, and document read.
- Explicit approval policies plus `bb agent --ask` interactive approval UX.
- Durable pending approval state and Telegram approval replies.
- Session turn persistence and session metadata for cwd, provider/model, and timestamps.
- `bb session list/current/set-cwd`.
- EDN memory backend plus SQLite memory seam.
- Scheduler and daemon commands.
- Telegram polling adapter with offset and duplicate durability.
- Slack polling adapter.
- Usage event persistence with provider/model/token/cost estimates.
- `bb usage report`.
- Small rich AST with terminal, Telegram, and Slack renderers.
- Config doctor checks.
- Terminal status UI.
- Full e2e test suite via `bb test:e2e:all`.

### Verification

- `bb test:e2e:all` passed before release export.
