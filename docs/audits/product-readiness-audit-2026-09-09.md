# Theseus Product-Readiness Audit + Robustness Roadmap

**Date:** 2026-09-09 · **Auditor:** OpenCrabs (bigbadassbot) · **Scope:** full `~/theseus/theseus` tree, live poller, Bankai mesh, docs
**Frame:** Rich Hickey robustness — *the system must tell the truth, keep facts on disk, fail loudly, and add no machinery the problem didn't ask for.*

---

## 1. State of the Code (measured, not vibed)

| Metric | Value | Verdict |
|---|---|---|
| Source | 9,657 LOC across 50 namespaces | right-sized |
| Tests | 10,585 LOC, 42 e2e suites + unit suites, `test:e2e:all` green | test > source. Healthy |
| Debt markers (TODO/FIXME/HACK) | **0** | clean |
| `catch Throwable` sweeps | **0** | clean |
| Modules over 500-LOC ceiling | 1 (`telegram.clj`, 695) | one violation |
| Modules without a dedicated test | 3 (`provider_fake`, `telegram_state`, `tool/common`) | acceptable (fake + glue) |
| Hardcoded paths | 1 (`~/.opencrabs-bb` default in `config.clj`) | by design, overridable |
| Unstructured `println` logging | 82 sites | works; no levels, no JSON |
| CI | `.github/workflows/tests.yml`, path-scoped, cancel-in-progress | exists, green |
| kondo | wired as e2e suite | enforced in gate |

## 2. What's Already Strong (don't touch)

The last 48 hours fixed the things that actually kill products:

- **Durable outbox** (`3055559`) — outbound intent persists before send, drains every cycle, dead-letters after 5. Proven live: message delivered *after* the poller that wrote it was killed.
- **Graceful SIGTERM** (`0dd0d3c`) — bounded drain, log receipt, zero 409 conflicts after.
- **Boot health check** (`5e31bde`) — doctor-lite at startup, loud 🚨 to owner DM on any error. Silent limping is dead.
- **Honest goal verdicts** (`bdf12f5`) — `:already-satisfied` ends the "fulfilled in 1ms by validating yesterday's tree" lie.
- **Authoring wall-clock budget** (`dfde481`) — a wedged provider can no longer park the poller or a resume forever.
- **Boot load-chain pins** (`fae26ff`) — the transient-stale-tree crash-loop class now dies in CI.
- **Topic-scoped registry** (`2f98228`) — parallel goals proven from ledger timestamps, zero marker leakage.

This is a real foundation. The remaining work is not fire-fighting; it's finishing.

## 3. Findings — ordered by blast radius

### F1 · `state/` is a junk drawer (correctness risk, medium)
`~/theseus/state/` mixes **durable state** (`sessions/`, `outbox/`, `usage.edn`, offsets) with **scratch** (probe scripts, `phantom-watch2.out`, `smoke-46v.json`, `.bak-verify` files, `ocr.swift`, stray logs — ~30 items). A cleanup script, a backup, or a human `rm` aimed at scratch can take out sessions. Durable facts and scratch in one directory is a complecting of lifetimes.

**Fix:** `state/` = durable only. `scratch/` (gitignored, documented as disposable) takes everything else. One move commit, one `ls` gate test that fails if scratch filenames appear in `state/`.

### F2 · `telegram.clj` at 695 LOC (maintainability, low-medium)
The poller entry carries four concerns: update intake, command routing, turn execution, and lifecycle (boot/shutdown/drain). It violates the hard ceiling this repo enforces everywhere else — after `goal_bridge` was split (`0a12e57`) this is the last one standing.

**Fix:** same surgery as `goal_bridge`: extract `telegram/intake` (getUpdates, dedup, attachments) and `telegram/lifecycle` (poll-loop, shutdown hook, drains). Target: `telegram.clj` ≤ 300 as orchestration only. Zero behavior change; suites pin it.

### F3 · No versioned releases (operations, medium)
CHANGELOG exists; tags don't. `git log` is the only release mechanism. When the live bot misbehaves, "what version is running" is answered by `ps` + hope. Rollback = archaeology.

**Fix:** tag `v0.9.0` now (this tree earned it). Boot logs the sha/tag. Rollback becomes `git checkout vX && kickstart`. Cost: one alias and one log line.

### F4 · Single-machine, single-bot, no failover (availability, accepted risk)
launchd KeepAlive + outbox + boot recovery covers process death. It does not cover machine death or a wedged bot token session. Both are documented knowns; for a personal-fleet product this is an *acceptable* risk, stated here so it stays a decision and not an accident.

### F5 · Observability is grep-shaped (operations, low)
82 `println` sites, no levels, no metrics. It works because the system is small and the operator is technical. A `usage.edn` + poll log already answer "what happened"; nothing answers "how's it trending" without reading.

**Fix (cheap, Hickey-approved):** one `bb stats` task summarizing usage ledger + outbox depth + dead-letters + goal verdicts from the files that already exist. No metrics infra, no daemon. Data you have, read coherently.

### F6 · `provider_fake.clj`, `telegram_state.clj` untested (low)
The fake is test infrastructure (self-certifying by use); `telegram_state` is offset bookkeeping with real corruption cost if wrong. **Fix:** one small suite pinning offset read/write/advance semantics, including the corrupt-file path.

## 4. Explicitly NOT doing (the robustness path is also subtraction)

- **No structured-logging framework.** Println + log files + grep is honest and sufficient at this scale. Machinery the problem didn't ask for.
- **No retry framework.** Retry/circuit-breaker layers exist where evidence demanded them (provider, delivery). Generalizing them now would be speculation.
- **No multi-bot/multi-tenant abstraction.** One bot, one owner, one fleet. The seam (config-driven chat registry) already exists if the world changes.
- **No dashboard.** `bb stats` + Telegram topic receipts are the interface. A web UI would be a second product wearing a mustache.

## 5. Roadmap to "production-ready" — four steps, each shippable

| # | Item | Bead | Gate (empirical) |
|---|---|---|---|
| R1 | State/scratch separation + `ls`-gate test | new | `ls state/` matches allowlist; suites green |
| R2 | `telegram.clj` split → ≤300 LOC | new | kondo + full e2e green, zero diff in behavior suites |
| R3 | Tag `v0.9.0`, boot logs version | new | poll log shows tag at boot |
| R4 | `bb stats` from existing ledgers | new | runs against real `state/`, exit 0 |
| R5 | `telegram_state` offset suite | new | corrupt-offset test green |

**Order rationale:** R1 first because it's the only item where delay compounds risk (every day adds scratch). R2 before R4/R5 because stats reads state and the split touches the same file the lifecycle work lives in — do the surgery before building next to it. R3 is nearly free and makes every future incident cheaper.

**Definition of production-ready for this product:** a stranger can reboot the machine, walk away for a week, and Theseus loses nothing, lies about nothing, and pages the owner exactly when it should. Items R1–R5 close the gap between here and there. Everything else is taste.

---

*Hickey certification: this roadmap adds two namespaces, one task, one suite, one tag — and refuses four pieces of machinery. The system's remaining weaknesses are all lifetime-complecting (scratch vs durable, intake vs lifecycle, version vs hope), and each fix is a separation, not an addition.*
