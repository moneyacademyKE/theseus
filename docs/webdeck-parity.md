# Theseus webdeck ↔ Telegram parity contract

Owner directive (Crab topic 4721, 2026-11-10): **fix all, loop till full
parity.** This file is the contract: what "parity" means, row by row, the
target state for each gap found in the 2026-11-10 audit
(`docs/audits/webdeck-limitations-2026-11-10.md`), and how each row is
verified. The witness (`docs/webdeck-parity-witness.edn`) records the
final verdicts; a row is CLOSED only with a passing check, PARTIAL with a
named blocker, never by assertion.

One rule governs the whole campaign: **one turn engine** (`core/run-turn!`,
one session file, one approvals ledger, one command dispatcher). Parity
means the web transport finishes the seams the Telegram transport already
has — it never means a second engine.

## Rows

| # | Capability | Telegram today | Web target state | Verified by |
|---|---|---|---|---|
| 1 | Compose a turn | message → run-turn! → reply | DONE (shipped `da4b9f0`) | e2e fake-provider job pin |
| 2 | Live tool flow | flow edits + typing | DONE — SSE job events | e2e + live drill |
| 3 | Approvals (buttons) | inline buttons → approvals.edn | DONE — web buttons | e2e decision pins |
| 4 | Approvals (typed) | "approve" handled BEFORE turn queue | typed approval resolves on the request thread, even while a job waits | e2e: bypass pin with a blocked lane |
| 5 | Retry liveness | flow shows retry attempts | `:retry/attempt` lands as a flow event in the job | e2e: emit pin |
| 6 | Job durability | n/a (no queue) | jobs + uploads persist to `state/web-lanes.edn`; restart marks interrupted runs honestly; recovery is visible, never a silent loss | e2e: persist + recover-lane pins |
| 7 | Refresh recovery | n/a | browser re-watches its job from localStorage after reload | shell pin + live reload drill |
| 8 | Cancel / retry a job | n/a | queued jobs cancel instantly; running jobs deny the pending approval and mark cancel-requested; failed/interrupted jobs retry as a new job with the same inputs | e2e: cancel + retry pins |
| 9 | Generated-file delivery | `telegram_send_file` tool | `web_send_file` tool registers an artifact on the job; authenticated `/api/artifact/<id>` serves it (path-validated, symlink-checked, size-capped) | e2e: tool pin + traversal/symlink denial pins + live download |
| 10 | Goal progress | watcher edits a progress message in the topic | goal detail view in the deck: run.log tail, verdict, control state; rows already refresh via SSE | e2e: detail reader pin + live drill |
| 11 | Goal control | `/goal resume`, `/goal cancel`, CLI pause/stop/resume | web control buttons: pause / resume / stop / cancel, routed through the same goal-bridge + control-file functions | e2e: control-command pins |
| 12 | History depth | full session file, chunked sends | transcript paginated (`?page=`), untruncated fields, "load older" in the UI | e2e: pagination pin + shell pin |
| 13 | Cross-channel sync | same session file | open transcript auto-reloads when the session file grows from ANY channel (SSE session-list mtime) | e2e reader pin + live drill |
| 14 | Voice input | voice note → STT | DONE for files; mic recorder (MediaRecorder, feature-detected) uploads through the same lane | shell pin + syntax gate |
| 15 | Camera input | photo → vision | camera capture input (`capture="environment"`), rides the same upload lane | shell pin |
| 16 | Edit affordance | edit re-runs the turn, replaces reply | edit-last-input re-submits with replace semantics on the shared session file | e2e: re-run pin |
| 17 | Reactions/typing acks | react-ack, typing indicator | N/A — web's ack IS the live job card + flow; no emoji theater | (by design) |
| 18 | Session mgmt | allowlist per user | durable sessions (`state/web-sessions.edn`, restart keeps login), logout, in-browser password rotation killing old sessions | e2e: persistence + rotation pins |
| 19 | Access roles | per-user allowlist | optional second read-only password: its sessions get GET routes only; write routes require the owner password | e2e: role gate pins |
| 20 | Schedule admin | none in Telegram | deck lists definitions; create (prompt+cron) and delete through `schedule/add-schedule!`/`remove-schedule!` | e2e: CRUD pins |
| 21 | Vision provider | image → vision chain | Gateway probe on 2026-11-10: `zai/glm-4.6v` correctly identified a red PNG; an end-to-end web image turn is still needed before closing parity | `bb scripts/probe_vision.bb` (gateway); web image turn pending |
| 22 | Stable URL | n/a | named tunnel IF `~/.cloudflared` credentials exist; otherwise documented one-owner-action blocker (login + domain choice) | checked at deploy |
| 23 | Outbound voice (TTS) | none | N/A — Theseus answers in text on both transports | (by design) |

## Non-goals (unchanged)

- No second engine, no per-column pollers, no framework, no webfont, no
  new dependencies.
- The master password remains one secret; roles add a second optional
  secret, not a user database.
- Quick-tunnel rotation stays acceptable unless named-tunnel credentials
  already exist on the host.

## Closure rule

A row moves to CLOSED in the witness only with the named check green in
the same session. PARTIAL rows name the exact external action required.
The bead (`bk-3eeb`) closes only when no row is silently open.
