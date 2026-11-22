# ADR 0007 — OpenCrabs v0.6.0 Workflow Parity: Surgical I/O, Mechanical Gates, Parallel Reads & Live Subagents

- **Date:** 2026-11-22
- **Status:** Accepted
- **Gap analysis:** `docs/gap-analysis-theseus-vs-opencrabs-2026-11-22.md`

## Context

Benchmarking Theseus (`9e88d99`, 22k LOC) against OpenCrabs `v0.6.0` (`8666ca2d9`, 426k prod LOC) on workflow and tool mechanics revealed six concrete gaps in interactive coding and delegation:
1. `write_file` only supported full-file overwrite/append (no surgical `edit_file`), `read_file` had no line ranges, and `search` only supported literal substrings.
2. Skills in `SKILL.md` were passive prompt summaries rather than mechanically enforced at edit time.
3. Read-only tool calls in a single assistant round executed strictly sequentially.
4. Anthropic prompt caching marked `:system` and the last 2 messages, leaving `:tools` uncached.
5. `subagent.clj` was a 108-LOC uncalled ledger with no live executor or worktree isolation.
6. Interactive coding turns had no mechanical plan gate or shell-verified step completion.

## Decision

Close all six gaps across three zero-dependency Red/Green TDD waves (~800 LOC total):
1. **Wave 1 (Surgical Code I/O):** Add `edit_file` (exact search-replace with unique-match guard, whitespace-trimmed line-block fallback, and 1-based inclusive line-range replace, enforcing `protected-write-path?` and `knowledge/guard-write!`), optional `start_line`/`end_line` on `read_file`, and optional `regex` + `glob` on `search`. Register `edit_file` in `security/control-tools` and `turn-integrity/mutating-tools`.
2. **Wave 2 (Mechanical `skill_gate`, Parallel Read-Only Batching, Anthropic `:tools` Cache):** Parse `globs:` frontmatter in `skill.clj` and block the first matching `write_file`/`edit_file` once per turn (`:denial/source :skill-gate`) with the full skill body injected; run eligible multi-call read-only batches concurrently via `future`s in `core.clj` while folding cache/exposure deterministically in request order; mark the last tool schema with `:cache_control {:type "ephemeral"}` in `provider.clj`.
3. **Wave 3 (Live `spawn_subagent` & Slim `plan` Gate):** Extend `subagent.clj` with `run-subagent!` (capacity-checked, optional temporary detached `git worktree` cleaned in `finally`, inherited parent `:security/exposure` taint, cycle-free runner resolution) and add `plan.clj` (`state/plans/<session-id>.edn`, allowlisted in `state_hygiene.clj`) with shell-verified `complete` (`verify_cmd`) and an opt-in mechanical `:plan/gate-after-tool-calls` gate in `tool.clj`.

## Consequences

- **Positive:** Interactive coding, skill enforcement, read throughput, Anthropic prompt-cache hit rates, and delegated subagent execution reach practical parity with OpenCrabs `v0.6.0` while keeping Theseus ~23k LOC, zero new dependencies, and 100% backward compatible.
- **Tradeoff:** Streaming HTTP and 8-language phantom-narration heuristics remain intentionally deferred to avoid OpenCrabs's 10k+ LOC turn-loop complexity tax.
