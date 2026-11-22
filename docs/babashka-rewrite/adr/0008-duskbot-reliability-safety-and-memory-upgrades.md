# ADR 0008 — DuskBot-Inspired Reliability, Safety Floor, Pacing & Memory Upgrades

- **Date:** 2026-11-22
- **Status:** Accepted

## Context

Scouting `DuskBotAI/duskbot` against Theseus (`531f14b`) surfaced 11 compact, pure-functional reliability and safety primitives (plus PII-free failure-signature normalization) that Theseus lacked:
1. Tool-boundary next-step recovery hints & shell timeout hints.
2. Deterministic post-turn stop gates (follow-up promises, open checklists, bare-ack writes, sycophantic 0-tool agreements, trailing announced steps).
3. Authorship-verified memory taint gate (Anti-SpAIware / MINJA).
4. Linear-time shell lexer safety floor (distinguishing quoted/heredoc data from commands, failing closed on unsynced syntax).
5. Two-stage sandboxed schedule probes (macOS Seatbelt SBPL + stripped env) + pacing gate (boot hold, daily cap, deterministic-jitter backoff).
6. Outbound action-claim vs. tool-receipt auditor + 0600 monthly JSONL receipt trail + PII-free failure-signature normalization.
7. Fresh-context zero-tool post-deliverable critic pass.
8. Idle-gap warm compaction scheduler.
9. Monotonic crash-resumable batch checklist.
10. Cross-conversation DREAM consolidation with deterministic user-quote verification.
11. Shadow-git per-turn workspace checkpoints (bare shadow `.git` without touching the user's repo).

## Decision

Implement all 11 capabilities across three sequential zero-dependency Red/Green TDD commits (`82d349b`, `2a2eb00`, Tier 3), allowlisting `"receipts"`, `"checklists"`, and `"checkpoints"` in `state_hygiene.clj`.

## Consequences

- **Positive:** Closes persistent memory poisoning, brittle shell regex false positives, deterministic tool retry loops, unbacked prose claims, and non-git undo gaps in ~1,200 LOC of pure, isolated Clojure modules with zero new dependencies.
- **Tradeoff:** Optional hooks (`deliverable-check`, `warm-compaction`, `memory-dream`, `checkpoints`) ship as composable pure/shell primitives rather than forced always-on background daemons so interactive latency stays predictable.
