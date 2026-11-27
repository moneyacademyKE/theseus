# ADR 0018: Tencent/LoopForge Delivery & Governance Ticks (Waves 1–3)

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Rich Hickey Gap Analysis of `Tencent/LoopForge` (`internal/engine/` + `internal/domain/`) identified 10 high-leverage delivery, diagnostics, and governance mechanics complementary to the OpenCrabs single-turn ticks (ADRs 0014–0016). Heavy platform scaffolding (Docker-in-Docker, MySQL/GORM, multi-tenant enterprise adapters) was explicitly rejected.

## Decisions

### Wave 1 — Diagnostics, Knowledge ROI, Conflict & Secret Hygiene
1. **`bb-agent.turn-diagnostics`** (wired into `telegram_intake.clj`): Evidence-only `{:outcome :summary :evidence :unknowns :next-steps}` failure/termination assessment; never invents provider root causes or leaks prompts/args.
2. **`bb-agent.knowledge-usage`** (wired into `brain_hints.clj`): Append-only EDN-lines ledger (`state/knowledge-usage.ednl`) tracking `:search-hit`, `:read`, and `:cited` per knowledge page by surface.
3. **`bb-agent.conflict-blocks`**: Bounded parser for `<<<<<<<`/`=======`/`>>>>>>>` git conflict markers (≤40 lines & 2,000 chars per side).
4. **`bb-agent.secret-mask`**: `"******"` masking of sensitive env vars/headers with merge-preserving round-trip on config updates.

### Wave 2 — Workflow Governance & Safe RSI Trials
5. **`bb-agent.rework-control`**: Carries concrete error into `:feedback` on retry, parks on `:waiting-approval` when `MaxReworks` is exhausted instead of discarding upstream output, enforces generation/attempt write fences, and rejects stale inline-card actions.
6. **`bb-agent.rsi-canary`**: Deterministic `SHA-256` bucketing (`[0, 99]`) for RSI canary trials with subagent arm inheritance and fail-closed replay validation (refuses repo/credential/approval workflows).

### Wave 3 — Live Control & Scheduler Resilience
7. **Mid-turn steer (`:turn/drain-steer` in `bb-agent.core`)**: Redirects an in-flight multi-tool turn between tool rounds without cancelling or losing context (`:turn/steered? true`).
8. **`bb-agent.queue-brake`**: Per-queue depth brake capping concurrent/queued cron runs during upstream outages.
9. **`bb-agent.gate-scope`**: Detects git-dependent verification commands and validates gate modes (`:command`/`:manual`/`:mr`) and `:execution-scope` (`:repositories` vs `:run`) before running.

## Verification
- Zero new dependencies; all 8 new modules are pure Babashka under 75 LOC each.
- Verified via 7 dedicated test suites, `bb lint` (0 errors, 0 warnings), `bb test-roster` (`never-run: 0`), and full `bb test:bb-agent`.
