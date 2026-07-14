# Roadmap: Babashka Rewrite

## Phase 0: Planning And Guardrails

Goal: make scope, decisions, and e2e gates explicit.

Deliverables:

- product spec for high and medium viability components
- roadmap with phase gates
- tasklist/backlog
- ADR for Babashka-only rewrite
- document verification script

E2E gate:

```sh
bb script/verify-babashka-rewrite-docs.bb
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Planning location | `docs/babashka-rewrite` | Keeps rewrite isolated from current Rust docs. |
| Rewrite style | semantic rewrite | Avoids accidental Rust structure and fake transpilation. |
| Scope | high/medium only | Maximizes utility while controlling complexity. |

## Phase 1: Minimal CLI Agent

Goal: one prompt in, one final answer out, one persisted turn.

Phase 1 uses a deterministic fake provider for local tests and an OpenAI-compatible HTTP adapter tested through a local stub server. Real provider credentials are not required for e2e tests.

Deliverables:

- `bb.edn` task entrypoints
- `src/bb_agent/core.clj` turn loop
- `src/bb_agent/provider.clj` provider protocol over data
- `src/bb_agent/config.clj` local config loader
- `test/e2e/cli_agent_test.clj`

E2E gate:

```sh
bb test:e2e:cli-agent
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| First provider | fake provider plus one HTTP provider | Makes tests deterministic before network calls. |
| Config format | EDN first | Native to Babashka, simple, inspectable. |
| Session store | EDN files | Avoid database complexity until needed. |

## Phase 2: Tool Protocol And Safe Execution

Goal: the model can request tools and the runtime can approve, execute, and feed results back.

Deliverables:

- JSON/EDN tool envelope
- built-in `shell`, `read_file`, `write_file`, `search`, and `git_status` tools
- approval policy: `:ask`, `:never`, `:auto-safe`, `:auto-all`
- e2e tests for approval denial and successful tool execution

E2E gate:

```sh
bb test:e2e:tools
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Tool interface | data envelopes | Keeps providers, tools, and channels decoupled. |
| Shell execution | deny by default | Safety is essential complexity. |
| Tool registry | map of names to functions | Simpler than plugin lifecycle until external tools demand it. |

## Phase 3: Memory And Context

Goal: add, search, and attach simple memory without a database.

Deliverables:

- `bb memory add`
- `bb memory search`
- memory attachment to turn input
- lightweight scoring with text matching
- e2e tests for persisted memory retrieval

E2E gate:

```sh
bb test:e2e:memory
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Initial storage | EDN file | Lowest moving parts. |
| Search | simple lexical match | Avoid vector database before proven need. |
| Upgrade path | SQLite behind same functions | Keeps future change localized. |

## Phase 4: Scheduler And Daemon

Goal: run named workflows on a schedule using the same core turn loop.

Deliverables:

- `bb daemon start`
- `bb schedule add/list/remove/run`
- file-backed schedule definitions
- structured logs
- e2e test with a short test schedule

E2E gate:

```sh
bb test:e2e:scheduler
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Scheduler runtime | bounded test loop, unbounded local loop | Keeps e2e deterministic while supporting real daemon behavior. |
| Schedule format | data files | Easy to inspect, diff, and test. |
| Execution target | core turn loop | Prevents divergent daemon behavior. |

## Phase 5: One Chat Channel

Goal: prove that an external channel can reuse the same turn loop.

Deliverables:

- Telegram polling adapter
- channel session mapping
- rich-rendered replies
- offset persistence and duplicate avoidance
- approval replies for pending tools
- e2e-style adapter test with fake HTTP responses

E2E gate:

```sh
bb test:e2e:telegram
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| First channel | Telegram | HTTP polling is simpler than websocket-heavy channels. |
| Reply format | small rich AST rendered to Telegram text/HTML | Adds structure without importing full Rust renderer complexity. |
| Adapter rule | no direct model calls | All channels go through the core turn loop. |

## Phase 6: Medium-Viability Extensions

Goal: add only proven medium components.

Deliverables:

- real SQLite memory through `sqlite3`
- Slack adapter
- external browser CLI hook with deterministic Babashka fallback
- Babashka-native text document reader before richer external parsing
- simple terminal status/menu UI

E2E gate:

```sh
bb test:e2e:all
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Add extension? | only after a failing e2e need | Prevents speculative complexity. |
| Native library? | prefer external CLI | Keeps Babashka app small and portable. |
| Channel count | one at a time | Keeps failures attributable. |

## Phase 9: Interactive Approval UX

Goal: make approval-required tools usable without weakening default safety.

Deliverables:

- `bb agent --ask`
- approve once, deny once, approve rest of turn/session
- persisted pending approval records
- Telegram approval replies for pending tools
- provider-originated tool calls denied unless explicitly approved

E2E gate:

```sh
bb test:e2e:cli-agent
bb test:e2e:telegram
bb test:e2e:tools
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Approval state | EDN data file | Durable and inspectable without a database. |
| CLI UX | synchronous prompt | Smallest useful local loop. |
| Channel UX | reply commands | Avoids channel-specific callback protocol complexity. |

## Phase 10: Session Metadata

Goal: keep cwd, provider/model, and timestamps explicit for CLI, scheduler, and channels.

Deliverables:

- metadata files under `state/session-metadata`
- `bb session list`
- `bb session current`
- `bb session set-cwd <session-id> <cwd>`
- scheduler entries capture cwd/provider/model

E2E gate:

```sh
bb test:e2e:cli-agent
bb test:e2e:scheduler
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Metadata location | separate EDN files | Preserves existing turn-log shape. |
| Working directory | session-scoped cwd | Prevents channel/scheduler cwd bleed. |
| Scheduler metadata | capture at add time | Makes scheduled behavior inspectable. |

## Phase 11: Usage Tracking

Goal: persist enough usage data for reports without depending on provider credentials in tests.

Deliverables:

- usage event contract
- OpenAI-compatible usage extraction
- Anthropic-compatible usage extraction
- token estimates when providers omit usage
- model normalization and pricing-backed cost estimates
- `bb usage report`

E2E gate:

```sh
bb test:e2e:cli-agent
bb test:e2e:anthropic
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Ledger | append-only EDN events | Simple and durable for local use. |
| Cost | estimate from local pricing data | Useful without billing API coupling. |
| Missing usage | deterministic token estimate | Keeps reports complete. |

## Phase 12: Rich Rendering

Goal: render structured answers consistently across terminal, Telegram, and Slack.

Deliverables:

- small internal rich AST
- markdown-to-AST for common structures
- terminal renderer
- Telegram HTML-safe renderer
- Slack markdown renderer
- channel send paths use renderers

E2E gate:

```sh
bb test:e2e:telegram
bb test:e2e:phase6
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| AST scope | headings, paragraphs, code, lists, tables, text | Covers high-value structure with low complexity. |
| Renderer ownership | internal namespace | Channels stay decoupled from provider text. |
| Telegram output | HTML-escaped text | Safe with existing `sendMessage`. |

## Phase 7: Session Model Switching

Goal: choose provider/model per session without channel or provider-specific coupling.

Deliverables:

- `bb model set <session-id> <provider> <model>`
- `bb model current <session-id>`
- file-backed session model selection
- agent turns use effective session provider/model

E2E gate:

```sh
bb test:e2e:model-switching
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Model state | separate session-model EDN files | Avoids mixing settings with turn history. |
| Scope | session first | Proves durable data contract before global/channel UI. |
| Adapter rule | core resolves effective config | Channels and schedulers cannot bypass model selection. |

## Phase 8: Config Validation

Goal: validate config early with human-readable diagnostics and drift warnings.

Deliverables:

- `bb config doctor`
- provider key validation
- missing config warnings
- session model drift warnings

E2E gate:

```sh
bb test:e2e:config
```

Rich Hickey path:

| Decision | Choice | Why |
|---|---|---|
| Validation shape | data checks with formatted output | Keeps config behavior explicit and testable. |
| Drift detection | compare config defaults to session model overrides | Surfaces hidden state without coupling to turns. |

## Completion Definition

The current Babashka port is complete through Phase 12 when `bb test:e2e:all` passes, docs are updated, and no channel bypasses the core turn loop.
