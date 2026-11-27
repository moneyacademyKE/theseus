# ADR 0019: Tencent/LoopForge Wave 4 Deep Cuts

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Final deep sweep of `Tencent/LoopForge` (`internal/evolution/`, `run_timing.go`, `human_context.go`, `align.go`, `botinteraction/`, `local/.claude/`) surfaced 9 final high-signal mechanics.

## Decisions

1. **Empirical-Bayes + 7-Day Half-Life RSI Triage & Tiered Preferences (`bb-agent.rsi-triage`, wired in `bb-agent.rsi`):** Beta-Binomial posterior shrunk toward global baseline (`prior-strength = 10`) with 7-day exponential decay, plus `P1 > P2 > ...` optimization priority guidance.
2. **Canary Early-Stop, 3-Point Run Scoring & Post-Release Watch (`bb-agent.rsi-canary`, wired in `bb-agent.rsi`):** Grades terminal runs `2` (clean) / `1` (reworked) / `0` (failed), early-stops failing canaries at 3 runs, and monitors post-release regression against baseline.
3. **True Execution Time vs. Wall Clock (`bb-agent.run-timing`):** Interval-union calculation separating active `:execution-ms` from `:wall-ms` and `:waiting-ms`.
4. **Atomic `human-context.md` Sidecar & Baseline-Align Conflict Pause (`bb-agent.human-context`, wired in `bb-agent.goal.core`):** Atomic ≤8 KB `human-context.md` sidecar injected into `{{human_context}}` templates + base-branch conflict pause predicate.
5. **Two-Horizon State Retention (`bb-agent.retention`):** Enforces `record-days >= body-days` (90d body strip / 180d record drop).
6. **Non-Blocking 1-Slot Progress Publisher (`bb-agent.progress-pub`, wired in `bb-agent.telegram-flow`):** Coalesces stale progress updates off the tool hot path and scrubs secrets from public status lines.
7. **File-Whitelist Scope Gate (`bb-agent.gate-scope/check-files-whitelist`):** Deterministic check ensuring `changed_files ⊆ whitelist`.
8. **4-Filter Knowledge Distillation Rubric:** Added to `brain/knowledge/execution-discipline.md` (reusable, specific, conclusion-first, 1–3 sentences).
