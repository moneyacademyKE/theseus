# ADR 0004: Hierarchical Context Compaction & Persistent Checkpoints

## Status

Accepted.

## Context

Inspection of `koladilip/hierarchical-context-ai-agent` against Theseus's compaction seam (`src/bb_agent/compression.clj`, `src/bb_agent/core.clj`) revealed six concrete, zero-dependency improvements:

1. `compression/compress` always passed `previous-summary = nil` to `summarize-middle`, re-summarizing the entire middle of any over-budget session on **every turn**.
2. Prose-only summaries lost exact paths, decisions, and pending items across repeated compactions.
3. Compacted blocks carried no turn-range provenance and no helper to drill back into verbatim turns on disk.
4. `prune-old-tool-results` erased older tool messages to `"[old tool output cleared]"`, losing which tool ran and its status/preview while leaving `:tool/results` attached.
5. `core.clj` hardcoded a `24000`-char history budget regardless of model window or system-prompt overhead, and `compression.clj` divided by `3.5` chars/token while `brain.clj` divided by `4`.
6. Compaction tests checked message structure, not structured fact retention across multi-tier foldovers.

## Decision

Upgrade `bb-agent.compression`, `bb-agent.session`, `bb-agent.core`, and `bb-agent.state-hygiene` using pure data transformations plus an atomic EDN checkpoint sidecar (`state/session-compaction/<id>.edn` via `bb-agent.atomic-io`):

- **Persistent rolling checkpoints (`compress-session-turns!`):** Persist `{:last-summarized-turn N :tiers [...] :memory {...}}` (capped at 3 rolling tiers; `fold-tiers` re-compresses merged older tiers via `call-llm-fn` whenever they exceed `2400` chars). Unchanged history reuses the checkpoint with zero summarizer LLM calls; only delta turns past `:last-summarized-turn` are summarized, passing prior tier text as `previous-summary`. Checkpoints clear on `session/reset!` and discard automatically if a session shrinks beneath `:last-summarized-turn`.
- **Structured memory ledger:** `summarize-middle` requests an EDN map `{:summary ... :facts [...] :decisions [...] :files [...] :pending [...]}` with plain-prose fallback on parse failure (`parse-summary-response`). `merge-structured-memory` deduplicates and retains the **newest** bounded tail (12 items/field).
- **Turn-range provenance + callable verbatim drill-down:** Each compacted tier stamps `[Turns A-B]` matching 0-based indices on disk and advertises `read_session_turns(from, to)` — an auto-safe, session-scoped tool backed by `session/load-turn-range` (max 10 turns / 20k chars, `:session/id` injected from `cfg`).
- **Idempotent 1-line deterministic tool breadcrumbs & live mid-turn pruning:** `prune-old-tool-results` idempotently condenses old tool messages to `[tool pruned: <name> <status> (<N> chars): <preview>]`, drops `:tool/results`, and runs live inside `core.clj`'s multi-round tool loop whenever messages exceed `live-budget`.
- **In-turn read-only tool cache:** `core.clj` caches `:ok` results of `read_file`/`search`/`git_status`/`document_read`/`read_session_turns` within a turn (below `loop-guard` nudge threshold) and clears the cache on any mutating tool.
- **Model-aware dynamic budget + unified token estimator:** Unify `chars-per-token = 4` across `brain.clj` and `compression.clj`; compute `history-budget-chars` from a pure model-window table (25% share capped at `96000`) minus actual injected brain/memory/semantic/skills overhead, floored at `8000`, with explicit `:history-budget-chars` always taking precedence.
- **Pure retention verification:** Add `retention-report` (set-diff over structured memory fields) for deterministic compaction testing.

## Rich Hickey Analysis

| Concern | Chosen Approach | Complexity | Benefit | Alternative Rejected |
|---|---|---|---|---|
| Re-summarization tax | Atomic EDN sidecar in `state/session-compaction/` | Low | Zero LLM calls when watermark is unchanged; delta-only summaries on growth | Re-summarizing full middle every turn or adding a DB table |
| Fact preservation | EDN map reply with plain-prose fallback + newest-tail merge | Low | Preserves paths/decisions/pending without crashing on non-EDN models | Requiring strict JSON mode or freezing oldest 5 items |
| Tool pruning | Deterministic 1-line breadcrumb + `dissoc :tool/results` | Low | Reclaims payload bytes while preserving causal tool trace at zero LLM cost | Blind `"[old tool output cleared]"` erasure |
| Budget sizing | Pure model table minus actual overhead chars | Low | Scales cleanly with large-window models while respecting system prompt size | Hardcoded `24000` chars everywhere |

## Consequences

- `state/session-compaction` is allowlisted in `bb-agent.state-hygiene`.
- Existing callers passing explicit `:history-budget-chars` keep exact override behavior.
- Rejected from reference repo: S3 vector scan, broken `user-memories`/`user-preferences` store, LLM self-rating, and AWS/CDK scaffolding.
