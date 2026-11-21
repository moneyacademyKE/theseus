# ADR 0003: Audit Wave 2 — Shared Atomic I/O, Security Boundaries, and Runtime Hardening

## Status

Accepted.

## Context

A follow-up audit at `14d5fcb` identified 15 remaining security, concurrency, durability, and resilience gaps across the codebase:

1. **Duplicated private-file writes & unprotected mutations:** Four namespaces (`approval.clj`, `session.clj`, `web_auth.clj`, `web_a2a.clj`) duplicated the same ~25-line Java NIO born-`0600` atomic write helper, while `config.clj` still had a TOCTOU `0644` window before `chmod 600`, `outbox.clj` wrote directly via `spit` (letting concurrent scans dead-letter live records as `::poison`) and collided on `dead/` moves, and `telegram_notes.clj` / `subagent.clj` did unlocked `slurp`/`spit` read-modify-writes.
2. **PID lock races:** `telegram_lifecycle.clj` used non-atomic `fs/exists?`/`slurp`/`spit` on `state/poller.pid` without shutdown cleanup, and `goal/lock.clj` allowed concurrent stale-steal overwrites.
3. **Remaining security seams:** `write_file` bypassed `protected-secret-path?` (and `~/.ssh/authorized_keys`/`~/.ssh/config`); `rtk.clj` called bare `clojure.core/read-string` on `rtk-filters.edn` (allowing `#=(...)` code execution); `notify_listen.clj` drained unbounded request bodies before HMAC verification; `web_a2a.clj` reflected untrusted `Host`/`X-Forwarded-Proto` headers into the agent card and re-read `a2a-token` on every request; `browser_tool.clj` accepted loopback/private-LAN HTTPS URLs; and `a2a_client.clj` followed redirects by default and lacked a response-body cap.
4. **Runtime/SQLite/error-handling hazards:** `telegram_delivery.clj` and `bot_commands.clj` issued un-timed HTTP POSTs; `web_search.clj` ran un-transacted per-row SQLite statements that deadlocked `pod-babashka-go-sqlite3` under concurrent `/api/search` refreshes; `session.clj` `list-metadata` crashed on any stray/corrupt file; `telegram_lifecycle.clj` and `web_http.clj` leaked unredacted exceptions or crashed on non-JSON 5xx HTML; `net_search.clj` threw uncaught exceptions on out-of-range numeric HTML entities; and `hot_reload.clj` hardcoded `<home>/theseus/src`, missed `<home>/skills`, scanned `.bak-*`/dotfiles, and lacked save-burst debouncing.

## Decision

Resolve all 15 findings in three sequential Red/Green TDD commits (`c729c4b`, `5bb49f7`, `e98b5f6`) with zero new dependencies:

- **Single atomic-I/O seam (`bb-agent.atomic-io`):** Centralize `ensure-private-file!`, `write-private-file!` (born-`0600` temp + atomic move + cleanup), `with-file-lock` (`ReentrantLock` + companion `.lock` `FileChannel/.lock`), `read-edn-safe`, `atomic-mutate-edn!`, and `update-edn!`. Migrate `approval.clj`, `session.clj`, `web_auth.clj`, `web_a2a.clj`, `config.clj`, `outbox.clj`, `telegram_notes.clj`, `subagent.clj`, `telegram_lifecycle.clj`, and `goal/lock.clj` onto it.
- **Fail-closed security boundaries:** Add `protected-write-path?` and enforce it in `write_file!`; reject `#=` and bind `*read-eval* false` in `rtk.clj`; bound `notify_listen.clj` bodies at 64KB (`413`) and reject non-POST (`405`) before HMAC verification; validate `Host`/`X-Forwarded-Proto` and mtime-cache `a2a-token` in `web_a2a.clj`; reject loopback/link-local/RFC1918/CGNAT hosts in `browser_tool.clj` using native-image-safe IP checks; and disable redirects (`:follow-redirects :never`) + enforce 2MB response cap in `a2a_client.clj`.
- **Bounded, transactional runtime:** Add `15s` timeouts to `telegram_delivery.clj` and `bot_commands.clj` (migrated to `babashka.http-client`); serialize and transact `web_search.clj` `refresh!`/`search` via `index-lock` + single `go-sqlite3` connection under `BEGIN IMMEDIATE`/`COMMIT`/`ROLLBACK`; filter `*.edn` + guard `edn/read-string` in `session.clj` metadata readers; guard non-JSON Telegram responses, redact error logs/500 responses, and guard `Character/isValidCodePoint` in `net_search.clj`; and resolve `THESEUS_REPO`/`<home>/theseus`/cwd + `<home>/skills` with `.bak-*`/dotfile filtering and burst debouncing in `hot_reload.clj`.

## Rich Hickey Analysis

| Concern | Chosen Approach | Complexity | Benefit | Alternative Rejected |
|---|---|---|---|---|
| Private atomic I/O | One `bb-agent.atomic-io` module shared by 10 callers | Low | Eliminates 4 copies of NIO boilerplate + fixes `config`/`outbox`/`notes`/`subagent` races in one place | Per-module bespoke `spit`/chmod helpers |
| `rtk-filters.edn` RCE | Reject `#=` + `*read-eval* false` + rule-map coercion | Low | Closes live code execution while preserving existing `#"..."` and string pattern configs | Breaking existing `#"..."` files with strict EDN-only reader |
| `web_search.clj` deadlock | JVM `index-lock` + single connection `BEGIN IMMEDIATE` | Low | Eliminates concurrent `go-sqlite3` pod deadlock and partial index drift | Per-statement auto-commit with retry sleeps |
| `browser_tool.clj` SSRF | Pure lexical IP/host check + `InetAddress/getHostAddress` | Low | Blocks private/loopback targets cleanly in Babashka's GraalVM native image | Calling unreflected `Inet4Address.isAnyLocalAddress` (crashes native image) |

## Consequences

- All durable EDN/secret state writes share one born-`0600` atomic primitive (`bb-agent.atomic-io`).
- `state/subagents.edn.lock` is allowlisted in `bb-agent.state-hygiene`; `state/.poller.pid.lock` rides dotfile hygiene exclusion.
- `bb-agent.atomic-io-test` is registered in `bb.edn` (`test:bb-agent`), keeping `bb test-roster` at `100%` coverage (`0 never-run`).
