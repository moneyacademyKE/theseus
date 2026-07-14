# Tasklist: Babashka Rewrite

Status values: `todo`, `doing`, `blocked`, `done`.

## Loop Protocol

Every loop follows red/green TDD:

1. Pick the smallest unchecked task.
2. Write or update an e2e test that fails.
3. Implement the smallest code path that passes.
4. Run the phase e2e gate.
5. Update docs, decisions, and task status.
6. Stop widening scope until the current gate is green.

## Phase 0: Planning And Guardrails

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P0-001 | done | Create Babashka rewrite product spec. | `bb script/verify-babashka-rewrite-docs.bb` |
| P0-002 | done | Create phased roadmap. | `bb script/verify-babashka-rewrite-docs.bb` |
| P0-003 | done | Create tasklist and loop protocol. | `bb script/verify-babashka-rewrite-docs.bb` |
| P0-004 | done | Record ADR for Babashka-only rewrite. | `bb script/verify-babashka-rewrite-docs.bb` |
| P0-005 | done | Add `bb.edn` rewrite tasks. | `bb tasks` |

## Phase 1: Minimal CLI Agent

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P1-001 | done | Add failing e2e test for `bb agent "say pong"`. | `bb test:e2e:cli-agent` |
| P1-002 | done | Add config loader with temp-home support for tests. | `bb test:e2e:cli-agent` |
| P1-003 | done | Add fake provider for deterministic e2e tests. | `bb test:e2e:cli-agent` |
| P1-004 | done | Add first HTTP provider adapter. | `bb test:e2e:cli-agent` |
| P1-005 | done | Persist session turns as EDN. | `bb test:e2e:cli-agent` |
| P1-006 | done | Render final answers and clear errors. | `bb test:e2e:cli-agent` |

## Phase 2: Tool Protocol And Safe Execution

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P2-001 | done | Add failing e2e test for denied shell tool call. | `bb test:e2e:tools` |
| P2-002 | done | Define tool request/result envelope. | `bb test:e2e:tools` |
| P2-003 | done | Implement approval policy. | `bb test:e2e:tools` |
| P2-004 | done | Implement `shell` tool with cwd and timeout. | `bb test:e2e:tools` |
| P2-005 | done | Implement `read_file` and `write_file` tools. | `bb test:e2e:tools` |
| P2-006 | done | Implement `search` and `git_status` tools. | `bb test:e2e:tools` |
| P2-007 | done | Feed tool result back into agent loop. | `bb test:e2e:tools` |

## Phase 3: Memory And Context

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P3-001 | done | Add failing e2e test for memory add/search. | `bb test:e2e:memory` |
| P3-002 | done | Implement EDN memory store. | `bb test:e2e:memory` |
| P3-003 | done | Implement lexical search scoring. | `bb test:e2e:memory` |
| P3-004 | done | Attach matching memories to turn context. | `bb test:e2e:memory` |
| P3-005 | done | Add migration seam for future SQLite memory. | `bb test:e2e:memory` |

## Phase 4: Scheduler And Daemon

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P4-001 | done | Add failing e2e test for a one-shot scheduled workflow. | `bb test:e2e:scheduler` |
| P4-002 | done | Add schedule data format. | `bb test:e2e:scheduler` |
| P4-003 | done | Add `schedule add/list/remove/run`. | `bb test:e2e:scheduler` |
| P4-004 | done | Add bounded daemon loop with graceful test exit. | `bb test:e2e:scheduler` |
| P4-005 | done | Add structured schedule logs. | `bb test:e2e:scheduler` |

## Phase 5: Telegram Channel

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P5-001 | done | Add fake Telegram polling e2e test. | `bb test:e2e:telegram` |
| P5-002 | done | Implement Telegram config and token loading. | `bb test:e2e:telegram` |
| P5-003 | done | Implement polling adapter. | `bb test:e2e:telegram` |
| P5-004 | done | Map Telegram chat to session ID. | `bb test:e2e:telegram` |
| P5-005 | done | Send rich-rendered final replies. | `bb test:e2e:telegram` |
| P5-006 | done | Persist Telegram offsets and seen update IDs. | `bb test:e2e:telegram` |
| P5-007 | done | Resolve pending approvals from Telegram replies. | `bb test:e2e:telegram` |

## Phase 6: Medium-Viability Extensions

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P6-001 | done | Add real SQLite memory backend through `sqlite3`. | `bb test:e2e:all` |
| P6-002 | done | Add Slack polling adapter after Telegram adapter is stable. | `bb test:e2e:all` |
| P6-003 | done | Add external browser CLI hook with Babashka fallback. | `bb test:e2e:all` |
| P6-004 | done | Add Babashka-native text document reader. | `bb test:e2e:all` |
| P6-005 | done | Add simple terminal status UI. | `bb test:e2e:all` |

Remaining Phase 6 hardening:

- Configure `OPENCRABS_BROWSER_CLI` to use a real browser automation command; fallback remains deterministic for tests.
- Run the daemon with `--max-runs` in tests and without it for an unbounded local loop.
- Keep provider-native tool calls approval-required by default; never auto-approve remote model tool requests.
- Keep `bb test:e2e:all` wired into CI.

## Phase 7: Session Model Switching

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P7-001 | done | Add session-scoped provider/model selection data contract. | `bb test:e2e:model-switching` |
| P7-002 | done | Add `bb model set/current`. | `bb test:e2e:model-switching` |
| P7-003 | done | Apply session model selection to agent turns. | `bb test:e2e:model-switching` |
| P7-004 | done | Add Anthropic-compatible provider adapter with tool-use parsing. | `bb test:e2e:anthropic` |

## Phase 8: Config Validation

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P8-001 | done | Add config schema validation surface. | `bb test:e2e:config` |
| P8-002 | done | Add `bb config doctor`. | `bb test:e2e:config` |
| P8-003 | done | Report session-model drift and provider key warnings. | `bb test:e2e:config` |

## Phase 9: Interactive Approval UX

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P9-001 | done | Add `bb agent --ask` interactive approval prompt. | `bb test:e2e:cli-agent` |
| P9-002 | done | Persist pending approval records and decisions. | `bb test:e2e:telegram` |
| P9-003 | done | Support approve once, deny once, and approve rest. | `bb test:e2e:cli-agent` |
| P9-004 | done | Keep provider tool calls denied without explicit approval. | `bb test:e2e:tools` |

## Phase 10: Session Metadata

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P10-001 | done | Persist cwd, provider/model, created/updated timestamps. | `bb test:e2e:cli-agent` |
| P10-002 | done | Add `bb session list/current/set-cwd`. | `bb test:e2e:cli-agent` |
| P10-003 | done | Make scheduler and tools use session metadata. | `bb test:e2e:scheduler` |

## Phase 11: Usage Tracking

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P11-001 | done | Add usage event contract and append-only persistence. | `bb test:e2e:cli-agent` |
| P11-002 | done | Extract OpenAI-compatible and Anthropic-compatible usage. | `bb test:e2e:cli-agent` |
| P11-003 | done | Normalize model names and estimate costs from pricing data. | `bb test:e2e:cli-agent` |
| P11-004 | done | Add `bb usage report`. | `bb test:e2e:cli-agent` |

## Phase 12: Rich Rendering

| ID | Status | Task | E2E Gate |
|---|---|---|---|
| P12-001 | done | Add small internal rich AST. | `bb test:e2e:all` |
| P12-002 | done | Render terminal, Telegram, and Slack output. | `bb test:e2e:telegram` |
| P12-003 | done | Route channel final replies through rich renderers. | `bb test:e2e:phase6` |

## Backlog Rules

- New tasks must cite a product outcome or failing e2e test.
- New medium-viability tasks stay blocked until a high-viability phase is green.
- Low-viability feature requests go to `Out Of Scope` unless the product spec changes.
- Every completed phase updates the roadmap and ADR log if a decision changed.
