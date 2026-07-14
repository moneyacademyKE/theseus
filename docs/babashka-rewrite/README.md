# Babashka Rewrite

This directory tracks the plan for a Babashka-only rewrite of OpenCrabs.

The rewrite follows the Rich Hickey path:

- separate essential complexity from incidental complexity
- keep decisions explicit and data-oriented
- prefer small complete loops over large speculative ports
- prove each phase with end-to-end tests before widening scope

## Current status

- **Phase 0:** complete
- **Phase 1:** complete
- **Phase 2:** complete
- **Phase 3:** complete
- **Phase 4:** complete
- **Phase 5:** complete
- **Phase 6:** complete
- **Phase 7:** complete
- **Phase 8:** complete
- **Phase 9:** complete
- **Phase 10:** complete
- **Phase 11:** complete
- **Phase 12:** complete

Recent port hardening now has:

- `bb agent --ask` with interactive approve once, deny once, and approve rest of turn
- persisted pending approval state under `state/approvals.edn`
- Telegram `/approve`, `/deny`, and `/approve-rest` replies that resolve pending tool requests
- session metadata under `state/session-metadata` with cwd, provider/model, and timestamps
- `bb session list/current/set-cwd`
- usage events under `state/usage.edn` with provider/model/token/cost estimate fields
- `bb usage report`
- Telegram offset and seen-update durability under `state/telegram-offset.edn` and `state/telegram-seen.edn`
- a small rich AST with terminal, Telegram, and Slack renderers
- full Babashka e2e coverage through `bb test:e2e:all`

Phase 6 now has:

- SQLite memory backend through `sqlite3`
- Slack polling adapter
- external browser CLI hook with deterministic fallback
- Babashka-native text document reader
- simple terminal status UI

Phase 5 now has:

- Telegram config loading with token and optional base URL override
- polling-based update fetch
- deterministic chat-to-session mapping via `telegram-<chat-id>`
- rich-rendered replies sent through Telegram `sendMessage`
- durable offset and duplicate tracking
- approval reply handling for pending tool requests
- e2e coverage with fake HTTP for polling, reply delivery, and session persistence

Phase 4 now has:

- `bb schedule add/list/remove/run`
- file-backed EDN schedule definitions
- a daemon entrypoint with a clean one-shot test mode
- structured schedule run logs persisted under state
- e2e coverage for schedule CRUD, one-shot execution, daemon execution, and session persistence

Phase 3 now has:

- `bb memory add` and `bb memory search`
- EDN-backed memory storage
- lexical scoring for memory search
- matching-memory attachment to turn context
- persisted `:memory/matches` and backend metadata on turns
- e2e coverage for memory persistence, retrieval, and turn attachment

Phase 2 now has:

- tool request/result envelopes
- approval policy handling
- approved execution for `shell`, `read_file`, `write_file`, `search`, and `git_status`
- shell `cwd` and timeout support
- agent-loop continuation that feeds tool results back into the provider until a final assistant answer is produced
- e2e coverage for denied and approved tool flows

Artifacts:

- [Product spec](PRODUCT_SPEC.md)
- [Roadmap](ROADMAP.md)
- [Tasklist](TASKLIST.md)
- [ADR 0001](adr/0001-babashka-only-rewrite.md)
- [Verification script](../../script/verify-babashka-rewrite-docs.bb)

Run document verification with:

```sh
bb script/verify-babashka-rewrite-docs.bb
```
