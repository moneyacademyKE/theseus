# Theseus webdeck: current capability limits

Date: 2026-11-10, EAT. Source revision: `2856ef0`. Task: `bk-6912`.

## Verdict

The deck shares the turn engine with Telegram, but does not yet have full
transport or lifecycle parity. The previous description of full parity was
stronger than the implementation warrants.

This is a source-based capability review, not a new authenticated browser
acceptance run or a comprehensive security audit. Local HTTP probes returned
200 for the shell and 401 for an unauthenticated `/api/state`; the current
launchd tunnel runs cloudflared in quick-tunnel `--url` mode. No credentials
were read or emitted. Source files were not modified by this review.

## Capabilities already present

Text turns, shared session files, native commands and skill dispatch, file
uploads, image input routing, audio-file transcription routing, web-job approval
buttons, SSE deck updates with polling fallback, full-text session search,
sanitized Markdown, code highlighting, usage charts, and feature-detected
Picture-in-Picture are implemented. Search and push are not missing features.
Provider availability and actual image/audio processing were not re-tested.

## Remaining limitations

1. **Generated files do not have a browser delivery path.** The HTTP routes
   expose uploads, but not authenticated generated-file downloads. Markdown can
   show remote media, but a local report path is not automatically served.
   `telegram_send_file` requires injected Telegram session context, which the
   web turn does not supply. Upload support is not output-delivery parity.
   Evidence: `web.clj` route/static whitelist; `web_turn.clj` run-job!;
   `tool.clj` context injection; `tool/telegram.clj` send-file.

2. **Goals still depend on Telegram delivery.** Web `/goal` and build-intent
   dispatch require `[:web :goal-chat-id]`; progress and deliverables go there.
   The Goals column shows a name, liveness, and one bounded log line, not a
   detailed goal workspace or pause/resume/cancel controls. LLM tool-based
   launch also lacks the Telegram send-context injection on the web lane.
   Evidence: `web_turn.clj` goal-request-reply; `web_data.clj` goal-rows;
   `web/js/columns.js` renderGoals; `tool/goal.clj`.

3. **Job recovery is incomplete.** Jobs and upload descriptors are process-local
   atoms. A restart loses queued/running job bookkeeping and upload IDs, though
   already-written session turns and uploaded bytes remain on disk. The browser
   tracks only one job ID in memory, does not rediscover it after refresh, and
   can replace its watch by sending again. There is no web cancel/retry route.
   Evidence: `web_turn.clj` jobs/uploads/executor; `web/js/cockpit.js` state,
   send, watchJob, and onState; complete `web.clj` route table.

4. **One blocked web job blocks later web messages.** All browser turns share
   one executor. A slow provider or approval wait holds that lane. Approval
   buttons use a separate HTTP route and can unblock it; a typed `approve`
   message enters the same queue behind the waiting job and cannot do so until
   the wait ends. The approval endpoint only finds approvals attached to web
   jobs, not arbitrary pending Telegram requests.
   Evidence: `web_turn.clj` executor, run-job!, web-approver, submit-turn!, decide!.

5. **Conversation synchronization is partial.** Selecting the same session
   reuses its durable history; it does not mirror web messages into Telegram.
   The selected transcript reloads at boot, on session selection, and on the
   watched job finishing, not when an external Telegram turn changes it.
   Web turns also set `:session/shared? false` and do not use Telegram's
   sender/topic/reply/edit/reaction-context pipeline.
   Evidence: `web/js/cockpit.js` loadTranscript call sites and onState;
   `web_turn.clj` run-job!; `telegram_intake.clj` message/edit/reaction spine.

6. **History display is capped.** The transcript route returns the last 60
   turns with each user input cut to 6,000 characters and each final cut to
   50,000. There is no older-page route. Search indexes full session text but
   opens the same tail-only view, so an old matching passage can be absent
   from the opened transcript. Search returns at most 30 sessions by default.
   Evidence: `web.clj:367`; `web_data.clj:183-186`; `web_search.clj` search;
   `web/js/search.js` openResult.

7. **Media interaction is file-based.** The composer accepts chosen files;
   it has no microphone recorder, camera capture UI, or voice-reply playback
   pipeline. Individual uploads are capped at 20 MiB. Images and audio still
   depend on the configured vision/STT facilities. Current provider servability
   is not established by this review.
   Evidence: complete `web/index.html` and `web/js/cockpit.js`;
   `web_turn.clj` max-upload-bytes/upload-kind; `telegram_intake.clj`
   attachment-context.

8. **Live status is not streamed answer text.** SSE pushes deck snapshots and
   tool-call/done events; the assistant final appears when run-turn! returns.
   The web emitter ignores other status types, including retry announcements,
   and stores at most 200 flow events per job. No browser background push
   notification or offline message outbox is implemented.
   Evidence: `web_turn.clj` web-emit/max-events; `web.clj` deck-snapshot;
   `web/js/api.js`, `main.js`, and `cockpit.js`; static whitelist.

9. **Administration is thin.** Goals and schedules are status columns, not
   dedicated management consoles. `/model` works through the shared command
   dispatcher, but there is no separate settings interface or per-user roles.
   Everyone with the master password has the same scope. Login sessions are
   in memory with seven-day expiry; a server restart requires login again.
   Password setting is host-script based; there is no logout/revoke UI.
   Evidence: complete `web.clj`, `web_auth.clj`, `web/index.html`, and client
   modules; `telegram_intake.clj` handle-chat-command.

10. **Hosting is still machine-dependent.** The live launchd tunnel uses
    `cloudflared tunnel --url http://127.0.0.1:8787`, so its quick-tunnel hostname
    can change on restart. Reachability depends on the Mac, its network, and
    both services. Cloudflare Access is documented as a setup procedure; its
    current dashboard deployment was not verified.
    Evidence: live `launchctl print` for `com.theseus.tunnel`;
    `ops/launchd/com.theseus.tunnel.plist`; `ops/cloudflare-access.md`.

## Smallest useful next work

Prioritize recoverable jobs and approval responsiveness, then a transport-neutral
artifact/delivery interface, then browser-native goal progress. Keep the one turn
engine; repair the missing lifecycle and delivery seams rather than cloning the
Telegram implementation into a second engine. That is the Hickey distinction:
shared computation is not the same thing as equivalent interaction.
