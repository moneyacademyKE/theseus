# Changelog

## Unreleased

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
