# ADR 0012 — Wave 2: 17-Point Runtime Robustness Hardening

- **Date:** 2026-11-24
- **Status:** Accepted
- **Gap Analysis:** [`docs/audits/robustness-wave2-17-gap-analysis-2026-11-24.md`](../../audits/robustness-wave2-17-gap-analysis-2026-11-24.md)

## Context

A second read-first sweep across namespaces untouched by Wave 1 (ADR `0011`) identified 17 additional crash and corruption vectors: `*/0` divide-by-zero in `cron.clj`, bare `edn/read-string`/`spit` in `semantic_memory.clj` and `outbox.clj`, single-bad-skill abort in `skill.clj`, non-atomic `edit_file` and binary-slurping `read_file` in `tool/file.clj`, unguarded `slurp` in `batch_checklist.clj`, non-sequential session EDN in `web_search.clj`, unguarded `THESEUS_WEB_PORT` parsing and raw 500 error leak in `web.clj`, `Math/pow` overflow and zero-attempt edge cases in `retry.clj`, throwing `notify`/`on-expire` callbacks in `approval.clj`, string `:confidence` / non-seq bucket crashes in `memory_dream.clj`, nil summarizer return in `compression.clj`, nil step / nil `ex-message` in `fallback.clj`, and non-map `web-sessions.edn` boot crash in `web_auth.clj`.

## Decision

Harden all 17 seams with zero new dependencies, pinned by `test/bb_agent/robustness_wave2_test.clj`:
- **Daemons & Web (`cron.clj`, `semantic_memory.clj`, `outbox.clj`, `web_search.clj`, `web.clj`, `web_auth.clj`):** guard `(pos? n)` on `*/n`, switch `session-summaries.edn` and `outbox/mark-attempt!` to `atomic-io`, guard non-sequential/non-map EDN in `web_search` and `web_auth/load-sessions!`, add bounded `web/parse-port`, and redact `api-routes` 500 error body.
- **Files, Skills & Checklists (`tool/file.clj`, `skill.clj`, `batch_checklist.clj`):** atomic `edit_file`, binary NUL-byte refusal in `read_file`, per-skill `try/catch` isolation in `discover-skills`, and IO error guard in `load-checklist`.
- **LLM Resilience (`retry.clj`, `approval.clj`, `memory_dream.clj`, `compression.clj`, `fallback.clj`):** cap backoff exponent + clamp non-negative sleep + clamp `max-attempts >= 1`, wrap approval callbacks in `try/catch`, safely coerce DREAM confidence & buckets, coerce nil compaction summaries, and filter non-map fallback steps with class-name fallback reason.

## Consequences

- Eliminates 17 additional crash/corruption paths across the daemon fleet and interactive agent loop with zero regressions across all existing suites.
