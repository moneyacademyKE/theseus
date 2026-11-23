# ADR 0011 — 17-Point Runtime Robustness Hardening Wave

- **Date:** 2026-11-24
- **Status:** Accepted
- **Gap Analysis:** [`docs/audits/robustness-17-gap-analysis-2026-11-24.md`](../../audits/robustness-17-gap-analysis-2026-11-24.md)

## Context

A read-first audit across Theseus's core runtime seams (`provider`, `core`, `tool/file`, `usage`, `schedule`, `web_data`, `memory`, `store`, `mcp_client`, `plan`, `config`, and `datalog`) surfaced 17 concrete crash/corruption vectors: unguarded `edn/read-string` and bare `spit` calls on durable ledgers, missing SQLite `PRAGMA busy_timeout`, NPEs on nil token/cost/provider fields, unguarded `subs` on short timestamps, uncaught `JsonParseException` in model tool arguments, unisolated parallel read `future`s, and uncaught predicate type errors in Datalog queries.

## Decision

Harden all 17 seams with zero new dependencies:
1. **`atomic_io.clj` + `tool/file.clj`:** add `write-atomic-file!` for crash-safe workspace overwrites and return `::invalid` on unparseable string `start_line`/`end_line` bounds (+ `(> from-i to-i)` guard).
2. **`provider.clj` + `core.clj`:** catch malformed model tool-call JSON into `{:_parse_error ...}`; isolate each parallel read `future` with `try/catch`.
3. **`config.clj`, `usage.clj`, `schedule.clj`:** switch to `aio/read-edn-safe` + `aio/write-private-file!`, fall back corrupt `config.edn` to `config.last-good.edn` (then `default-config`), and guard nil `provider` in `usage/price-entry`.
4. **`memory.clj` + `store.clj`:** set `PRAGMA busy_timeout = 5000` on SQLite connections, safely parse EDN columns in `memory/normalize-row`, and guard explicit `nil` token/cost keys in `store/event-row`.
5. **`web_data.clj`, `mcp_client.clj`, `plan.clj`, `datalog.clj`:** guard short/nil `:created/at` in `usage-series`, skip non-JSON SSE keepalives in `decode-messages`, catch process spawn errors in `plan/run-verify-cmd`, and isolate predicate type errors in `datalog/eval-pred`.

## Consequences

- Every core daemon (`telegram`, `web`, `schedule-tick`) and interactive turn degrades gracefully under corrupt files, transient SQLite contention, malformed LLM JSON, and heterogeneous data, pinned by `test/bb_agent/robustness_17_test.clj`.
