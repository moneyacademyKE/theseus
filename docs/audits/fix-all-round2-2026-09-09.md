# Theseus — "fix all" round 2 — 2026-09-09

## Shipped (4 beads closed)

| Bead | Commit(s) | Change |
|---|---|---|
| bk-1825 boot health | `34f47ef`, `5e31bde` | Poller boot runs doctor/run-checks: 11 checks (config parse, telegram section, home writable, provider reachable, stores parse). Errors → loud log + owner DM via the durable outbox. New contract: a telegram-first install with no `:telegram` section is degraded, exit 1 — fixtures updated to match. |
| bk-e6ff log cap | `f09d912`, `5e31bde` | `log_cap.clj`: at boot, if `state/telegram-poll.log` exceeds 10 MB (config: `:telegram :log-max-bytes`), keep the newest 5 MB at a whole-line boundary with a drop marker. Truncates IN PLACE — a rename would orphan launchd's O_APPEND fd into an unlinked inode and swallow all future output invisibly. Never throws. |
| bk-f48b media-only drop | already landed | Verified: the `(or (seq text) saved)` gate is in place, `telegram_media_only_test.clj` covers captionless photo + voice note, suite green. Closed as done. |
| bk-c60f LOC split | `0a12e57` | goal_bridge.clj 609→434: registry → `goal/registry.clj` (72), outcomes → `goal/outcomes.clj` (54), progress → `goal/progress.clj` (83). No alias shims — all call sites moved. |

## Live receipts

- Kickstart → poller pid 15100, all 11 boot-health checks `[OK]` against the production config.
- Full `bb test:e2e:all` green; unit 63/241; goal 96/482; recovery e2e 7/30.

## Security note (needs owner action)

A recon script of mine printed the infer `:api-key` in plaintext to this session's log (redactor only walked one map level of the config). The key is in chat history — rotate at inferhub when convenient.

## Remaining Theseus queue (not "broken", so not in this round)

- `bk-9640` (P2): approval inline buttons wired to approval.clj
- `bk-1c52` (P2): parity roadmap tail (P3 buttons, P4 ops)
- `bk-9cae` (P4, in progress): flow-block visual parity
