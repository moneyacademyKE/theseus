# ADR 0002: Codebase Audit Hardening and Honest Gates

## Status

Accepted.

## Context

An end-to-end audit of Theseus on `main` (`b95ecfe`, `v1.3.1`) uncovered six systemic classes of compounding failures despite apparently green checks:

1. **Web & tool security gaps:** Path traversal on `/api/goal-detail`, `/api/goal-control`, and artifact serving; missing secret-file boundaries (`config.edn`, `.web-pass*.edn`, `web-sessions.edn`, `a2a-*`, `~/.ssh/*`, `~/.aws/credentials`, `~/.netrc`, `~/.gnupg/**`) in `read_file`, `search`, `document_read`, and `web_send_file`; wrapper-bypassable shell allow/deny checks; plain-text bearer session storage and stale `:ro` sessions surviving rotation; body reads before auth; unrevoked SSE streams; raw HTML `.innerHTML` status injection in the cockpit UI; and unescaped newlines breaking `redact-secrets` patterns.
2. **Dishonest test & lint gates:** `.clj-kondo/config.edn` suppressed 18 linters with `:level :off`, hiding 3 nested-`deftest`s with zero execution and real schema bugs (`skill_research_github.clj`); `bb lint` used `--fail-level error`; `test:e2e:tool-advertisement` dropped exit codes; `test:e2e:all` omitted `test:goal`, `anthropic`, `subagent`, `rsi-learn`, `telegram-reactions`, and JS unit tests while running `telegram-edits` twice; CI lacked `poppler-utils` and `bb lint`; and 20 `test/e2e/` mock servers bound wildcard `0.0.0.0` via TOCTOU `ServerSocket(0)` (`free-port`), colliding silently with loopback `127.0.0.1` listeners on macOS.
3. **Silent runtime & wire defects:** Unquoted FTS5 queries failing on hyphens/operators (`goal-bridge`, `OR`, `NOT`); non-JSON 5xx HTML proxy pages crashing JSON parsing before `error_classifier` could mark them `:infra` retryable; missing `:system` extraction, `:tools` advertisement, and `:tool/id` round-tripping on `:anthropic-compatible`; keyword-vs-string `:role` drift on loop nudges; unbounded HTTP calls and fragile nil-poll handling in `a2a_client`; unvalidated `timeout-ms` in `a2a_tool`; stale `state_hygiene` allowlist; and duplicate `bb-excel` packaging in `script/build-binary.sh`.
4. **Unbounded brain context bloat:** `brain.clj` concatenated append-only `improvements.md` and `wins.md` whole on every turn (~946k chars / ~236k tokens live), leaving <1% of system prompt budget for identity and knowledge.
5. **Concurrency, SQLite & state races:** Unlocked read-modify-write races in `approval.clj`; shell-out `sqlite3 -separator \t` corruption on newlines/tabs/quotes in `memory.clj`; un-transacted `index-usage!` rebuilds and multi-statement SQL acceptance in `store.clj`; whole-atom `reset!` clobbering sibling provider breakers in `core.clj`; and unbounded `@uploads` + disk files in `web_turn.clj`.
6. **Doc drift:** Stale canonical clone path, LOC/dependency reconciliation, inverted CHANGELOG versions, and constitution references in `README.md` and `tool.clj`.

## Decision

Harden all six layers without changing Theseus's core Babashka-plus-EDN data architecture:

- **Fail-closed security boundaries:** Enforce canonical symlink-resolved `protected-secret-path?` in file/document/artifact tools; harden shell never-auto classification across basename/path/wrappers; hash session tokens with SHA-256 at rest (`state/web-sessions.edn`), track `.web-pass-ro.edn` mtime across processes, validate auth before body drain, and re-check live tokens on every SSE heartbeat.
- **Zero-suppression static & test gates:** Strip all `:level :off` kondo overrides, enforce `--fail-level warning` in `bb lint` and CI, unify all Clojure + goal + JS test suites under `bb test`, and bind all test HTTP servers exclusively to `{:port 0 :ip "127.0.0.1"}` using kernel-assigned `:local-port`.
- **Wire & protocol parity:** Quote sanitized FTS5 tokens individually; parse provider responses best-effort so HTTP status checks (`>= 400`) run before JSON decoding; lift `:anthropic-compatible` to full `system`/`tools`/`tool_use`/`tool_result` parity while preserving OpenAI `"call-N"` semantics.
- **Decomplect append-only ledgers from identity:** Exclude `improvements.md` and `wins.md` from `load-brain`; surface them via `ledger-index` in `load-brain-context` so agents read on demand while per-turn cost stays flat.
- **Serialize & parameterize shared state:** Serialize `state/approvals.edn` via JVM lock + companion `approvals.edn.lock` `FileChannel` + born-`0600` atomic rename; run `memory.clj` and `store.clj` through `pod.babashka.go-sqlite3` transactions with strict read-only SQL checks; merge `provider-breaker` per provider key via `swap!`; and bound `@uploads` at 100 unreferenced items with disk cleanup.
- **Explicit project overrides:** Retain the `>= 500 LOC` exemption granted for this project in `AGENTS.md` and document existing dependencies (`bb-excel`, `go-sqlite3`, `buddy`) under the zero-new-deps rule.

## Rich Hickey Analysis

| Concern | Chosen Approach | Complexity | Benefit | Alternative Rejected |
|---|---|---|---|---|
| Brain context bloat | Separate `ledger-index` from identity `load-brain` | Low | Cuts live injection ~120x without losing discoverability or editing history | Truncating `improvements.md` on disk (destroys durable history) |
| Approval concurrency | Companion `.lock` `FileChannel` + JVM lock + atomic `0600` rename | Low | Eliminates lost updates across threads/processes without POSIX `fcntl` close-drop bug | Replacing EDN with SQLite (unnecessary migration of simple small map) |
| SQLite memory | Migrate to `pod.babashka.go-sqlite3` (`get-connection` + `BEGIN IMMEDIATE`) | Low | Binary-safe round-trips, parameterized queries, reuses existing CI-cached pod | Shelling out with JSON/base64 wrappers around `sqlite3` CLI |
| Loopback test binds | Kernel `{:port 0 :ip "127.0.0.1"}` + `(:local-port (meta s))` | Low | Eliminates macOS `0.0.0.0` vs `127.0.0.1` collisions & `ServerSocket(0)` TOCTOU | Retrying around `free-port` (masks race and misses `127.0.0.1` binds) |
| Anthropic wire parity | Pure `anthropic-tools` + `anthropic-wire-payload` helpers beside OpenAI wire | Low | Full tool-use loop parity with data-in/data-out unit testability | Inline mutation inside `complete` method |

## Consequences

- `bb lint` runs strictly (`--fail-level warning`) in both local dev and CI.
- `bb test` exercises 100% of registered Clojure unit, e2e, goal, JS, and rewrite doc gates.
- Existing runtime deployments using `:memory/backend :sqlite` require the `pod-babashka-go-sqlite3` pod (already required for `bb usage:query` and web search).
