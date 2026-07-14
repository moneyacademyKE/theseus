# Changelog

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
