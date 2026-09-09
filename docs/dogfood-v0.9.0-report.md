# v0.9.0 Dogfood Audit — report-studio via Telegram topic 236

**Date:** 2026-09-09 · **Release:** `v0.9.0` (tag `e42696e`) · **Project:** report-studio (Babashka CSV→HTML report generator)

## Verdict: GOAL FULFILLED — methodology followed, one skill genuinely used

The goal ran end-to-end through the real Telegram surface: brief posted in topic 236
(msg 237) → `launch-spec!` authoring → runner → watcher → 🎯 outcome delivered to the
topic (msg 238) → outcome persisted in the topic session (`:source :goal-outcome`).

## 1. Goal methodology — followed at every checkpoint

| Methodology checkpoint | Evidence |
|---|---|
| Methodology injected at authoring | `references/goal-methodology.md` seeded in workspace |
| Author consulted it | `read_file` on the methodology, `:status :ok` (session log) |
| Observers as world predicates | `[:tests-ok :html-ok :builds]` — machine-checkable |
| Integrity constraints | 2 (`tests-ok = "pass"`, `html-ok = "pass"`) |
| Honest goal predicate | `{:op :>=, :ref :builds, :value 1}` — simple, verifiable |
| Act contract (idempotent, <60s, receipts) | `act.sh`: per-attempt receipt line, self-verifying greps, absolute `bb` path |
| Rollback safety | `max-rollbacks 3`, checkpoint tags, baseline git commit |
| Stall safety | `stall-after 4` |
| Runner execution | iter-000 `:act` exit 0, progressed 0→1; iter-001 `:done` FULFILLED |
| Honest attribution (bk-e827) | 0 pre-existing ledgers → `:fulfilled`, not `:already-satisfied` |

The authored config is exactly what the methodology prescribes — and notably the
author chose the SIMPLEST shape that proves the goal (one predicate, two integrity
checks) over stage ceremony. Hickey would approve.

## 2. Skill utilization — real, but narrower than the index suggests

**verification-witness: genuinely used.** The author session shows the agent
hunting for it (`ls ~/.config/makerskills/verification-witness/`), then
`read_file skills/verification-witness/SKILL.md` **twice**, content returned,
`:status :ok`. Its fingerprints are in the artifact: per-attempt receipt logs
(`builds.log`, `test-attempt-N.log`) and integrity observers are precisely the
machine-verifiable evidence that skill prescribes.

**frontend-design, adr, review, openspeq: mentions only (0 path reads).** These
matched as bare strings from the injected skills index — the honest collector now
separates `path-refs` (tool-level reads) from `bare-mentions` (context noise).
Disclosure beats a flattering number.

## 3. The product itself — independently verified

- `bb test` re-run by the auditor (not the agent): **12 tests, 25 assertions, 0 failures**
- `report.html`: DOCTYPE, dark theme, charset, semantic HTML, no unescaped `<script>`
- Goal predicate independently re-confirmed: `builds.log` receipt, greps pass

## 4. Infrastructure bugs the dogfood flushed out

1. **Authoring watchdog miscalibrated (bk-963f follow-up, fixed `b44994d`).** The
   10-minute TOTAL budget was killing honest work — real authoring on the reasoning
   model takes ~44 min. Reshaped to progress-based: 8-min idle fuse (emit-per-tool-call
   as the progress signal) + 45-min ceiling backstop. Four new pins in the suite.
   The `~/Desktop/report-studio` corpse proved it: a "stalled" author had scaffolded
   the real project before the old budget guillotined it.
2. **PATH starvation in ad-hoc launches.** The runner (`bb goal`) requires
   `~/.local/bin` on PATH; the poller's launchd context has it, my ad-hoc
   `launchctl submit` did not → `nohup: bb: No such file or directory`. The authored
   `act.sh` had already defended itself with an absolute bb path — the launcher
   hadn't. Fixed by relaunching through the bridge's own `launch!` with proper env
   (`scripts/relaunch_goal.clj`, reusable for pure re-launch without re-authoring).
3. **`launchctl submit` is KeepAlive-forever.** The one-shot launcher respawned on
   exit, spawning 4 litter workspaces before I killed the job. Never use `submit`
   for one-shots; use a plist with `KeepAlive=false` or a setsid daemonizer.
4. **Desktop readdir hang (open, not iCloud).** `ls` on `~/Desktop` and the project
   dir hangs >30s while direct-path stat/read/create work; no bird/fileproviderd
   running. The goal was unaffected — acts use direct paths throughout — but it is
   a live mystery on this machine worth a fsck/reboot test.

## 5. Verdict

The v0.9.0 goal pipeline did what it was designed to do on a real, complex, fresh
topic: author a sound goal, verify its own act, execute, prove the world, and
report home through the durable channel. One skill was genuinely load-bearing;
the rest of the index was decoration in this run. Two launcher-path bugs and a
watchdog calibration error surfaced — all three fixed or documented above.

*Evidence: workspace `build-report-studio-a-ba-919741`, session
`goal-author-build-report-studio-a-ba-919741.edn`, topic session
`telegram--1003995594829-topic-236.edn`, collector
`scripts/dogfood_evidence.bb`.*
