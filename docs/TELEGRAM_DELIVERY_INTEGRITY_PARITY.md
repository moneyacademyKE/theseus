# Telegram Delivery-Integrity Gap Analysis and Parity Slice

**Decision date:** 2026-09-01
**Compared revisions:** Theseus `origin/main` at `7bba6bb`; OpenCrabs tagged releases `v0.3.81` and `v0.3.82`.

## Question and decision

Theseus already has the high-value Telegram conversation boundaries: fail-closed authorization, group/member separation, forum-topic sessions, topic-preserving replies, rich HTML conversion, 4096-character chunking, durable update offsets, and explicit approvals. The next parity work should not import OpenCrabs' entire Telegram product.

The smallest high-value slice is **one checked outbound send primitive for every Theseus Telegram reply, with bounded 429 retry and HTML-to-plain fallback**. It protects the answer after the provider and tool loop have already paid to produce it. This is delivery integrity, not presentation parity.

## OpenCrabs evidence

The comparison uses published, immutable evidence rather than an untagged checkout:

- OpenCrabs [`v0.3.82` release notes](https://github.com/adolfousier/opencrabs/releases/tag/v0.3.82) name:
  - `8844a5e5`, **send_markdown_outbox**: one send ladder for proactive Telegram writers;
  - `55797670`, one rate-limit wait helper and `send_html_or_plain` on the shared ladder;
  - `86b29356`, send-correlation telemetry at Telegram chokepoints;
  - `095fb32a`, long rate-limit windows bail instead of parking a send inline;
  - `e9df9713`, all six Telegram arms use thread resolution.
- The same entries are in OpenCrabs [`CHANGELOG.md` for 0.3.82](https://github.com/adolfousier/opencrabs/blob/v0.3.82/CHANGELOG.md). That implementation makes HTML and its plain fallback share a bounded Retry-After ladder. The outbox then owns conversion, chunking, thread routing, retry, fallback, and failure reporting instead of leaving those choices to each caller.
- OpenCrabs [`v0.3.81` release notes](https://github.com/adolfousier/opencrabs/releases/tag/v0.3.81) and its changelog name `6f91fe97`, the **30-second inline rate-limit cap**. This is relevant negative evidence: blindly sleeping a multi-hour Telegram window is not integrity.
- The burn-scar issues explain the priority:
  - [#297](https://github.com/adolfousier/opencrabs/issues/297): a temporary 429 dropped a completed command reply; the contract became "may delay, never drop," with bounded attempts.
  - [#1085](https://github.com/adolfousier/opencrabs/issues/1085): six independent send writers had retry/fallback drift; the remedy was a shared send spine.
  - [#1110](https://github.com/adolfousier/opencrabs/issues/1110): observed flood windows reached 7.9 hours, so waits need a cap or immediate bail.
  - [#1019](https://github.com/adolfousier/opencrabs/issues/1019): swallowed final-send failures were indistinguishable from the agent ignoring the user.

The evidence does **not** say Theseus needs OpenCrabs' Rust channel architecture. It says the invariant belongs at the send chokepoint.

## Current Theseus behavior on `origin/main`

The current path is small and centralized, but unchecked:

1. `src/bb_agent/telegram.clj` has one raw `send-message!` that calls `http/post` with `:throw false` and returns the response without interpreting HTTP status or Telegram's JSON `:ok` field.
2. `send-html!` converts the final answer, splits it, and calls `send-message!` once per chunk. There is no retry, no plain-text fallback, and no stop-on-failure contract for later chunks.
3. Approval requests and approval acknowledgements call that same unchecked primitive. Thus all current reply arms share the gap even though they share the function.
4. `process-message!` completes `core/run-turn!` before delivery. A failed send can therefore consume the update, persist the turn, and advance the durable offset while no answer reaches Telegram.
5. The focused Telegram tests prove successful payload shape, authorization, topic routing, rich conversion, and polling durability. Their fake servers return successful `sendMessage` responses; no test makes `sendMessage` return 429, rejected HTML, or a terminal error.

Existing strengths must remain unchanged: topic and reply fields, first-chunk reply attribution, HTML escaping, chunk boundaries, allowlist behavior, seen-update consumption, and session isolation.

## Chosen parity slice

Implement a single result-aware send ladder under the existing `send-message!` / `send-html!` seam:

1. **Validate the Telegram response.** Treat non-2xx HTTP, malformed bodies, and Bot API `{:ok false}` as failures. Parse `parameters.retry_after` when present.
2. **Retry only 429/Retry-After failures.** Make total attempts bounded and inject sleep for deterministic tests. Honor short requested waits, but cap inline sleep at 30 seconds. A long window must return a structured failure rather than hold the polling loop for hours. Non-429 failures fail immediately.
3. **Fallback from rejected HTML to plain text once.** After a terminal Telegram markup/parse rejection (not a 429, authorization failure, or invalid chat), strip Telegram HTML and run the plain payload through the same bounded ladder. Preserve `chat_id`, `message_thread_id`, and `reply_parameters` on every attempt and fallback.
4. **Stop a chunk sequence at the first terminal failure and surface it.** Do not silently continue and report success after a missing middle chunk. Return or throw a failure that reaches the existing poll-loop error path and includes method, chat/thread identity, attempt count, and Telegram description—but never message content or token.
5. **Use the same checked primitive for finals, approval prompts, and approval acknowledgements.** There is only one Telegram `sendMessage` writer today; keep it that way. Delivery failures must not be mistaken for provider failures or trigger a replay of `core/run-turn!`.

### Verification contract for the implementation task

Focused fake-server tests should pin:

- 429, then success: exactly two sends and one injected wait;
- repeated 429: bounded attempts, with requested sleep clamped to 30 seconds;
- non-429 rejection: no retry;
- HTML rejection followed by plain success: fallback text has no Telegram HTML and routing fields are identical;
- terminal failure: later chunks are not sent and the polling command is non-successful or emits its existing explicit poll error, rather than claiming delivery;
- existing Telegram, rich, guard, and group/topic suites remain green.

This slice is deliberately narrower than OpenCrabs' `send_markdown_outbox`: Theseus has no proactive Telegram tool or cron writer, native-rich API, edit-in-place stream, callback UI, or send database. One checked `sendMessage` seam covers every writer that actually exists.

## Complexity explicitly rejected

| Rejected now | Why it is outside delivery integrity |
|---|---|
| Native rich-message API, Mermaid/media rendering, plan cards, inline keyboards, reactions, flow chrome, and streaming edits | Presentation and interaction features add send arms and rate pressure before the sole send arm is reliable. |
| Persistent outbox, queue/coalescing, background redelivery, or exactly-once claims | Theseus has no delivery ledger or worker lifecycle. A durable queue needs idempotency and acknowledgement design; this slice only promises bounded in-process attempts and honest failure. |
| Full OpenCrabs correlation-telemetry subsystem | Useful at six writers, disproportionate at one. Structured failure context at the chokepoint is enough now; never log content or credentials. |
| Reusing the provider retry/circuit-breaker stack unchanged | Provider retry classifies error strings and uses exponential jitter. Telegram supplies an authoritative `retry_after`; transport policy should remain explicit and deterministic. |
| Retrying every failure or falling back to plain on 429 | 400/auth/chat errors do not heal through repetition. Plain text does not evade a per-chat flood limit. Retry 429; use plain only for terminal HTML rejection. |
| Holding or rewinding the Bot API update offset until delivery succeeds | Reprocessing an inbound update can rerun tools and duplicate side effects. Honest send failure is safer than replaying the agent turn. |
| MTProto userbot, media download/STT, voice, passive group history, auto-registration, and topic administration | These require new transport, storage, or authority boundaries and were already excluded from group/topic parity. |

## Implemented result

The selected delivery spine is implemented on `fix/telegram-delivery-integrity` in commit `e68f3f0870d11cdf7f03bc4a044a4e33a0eda14a`:

- `bb-agent.telegram-delivery` owns HTTP/Bot API validation, three bounded 429 attempts, the 30-second per-wait cap, markup-specific HTML-to-plain fallback, topic/reply preservation, and terminal failure data;
- `bb-agent.telegram-attachment` persists authorized documents and supported media as inert bytes under `channel_attachments/telegram/<chat-id>/topic-<thread-id>/`; authorization precedes `getFile`, paths and names are sanitized, and `:attachment-max-bytes` defaults to 20 MiB;
- `bb-agent.telegram` remains the adapter: it supplies chat/topic/message identity, invokes persistence only for an authorized responding message, and sends finals plus approvals through the checked delivery module;
- no dependency, outbox worker, parser, evaluator, STT path, or replay mechanism was added.

Measured verification on 2026-09-01:

| Gate | Result |
|---|---|
| Focused adapter/delivery/attachment | 10 tests / 60 assertions / 0 failures/errors |
| Group/topic regression | 10 / 55 / 0 |
| Rich rendering | 5 / 8 / 0 |
| Authorization guard | 4 / 7 / 0 |
| Full `bb test:e2e:all` | 31 test namespaces / 170 tests / 763 assertions / 0 failures/errors; rewrite docs verified |
| Source size | adapter 160 LOC; delivery 179; attachment 154 |
| Hygiene | `git diff --check` exit 0; zero new dependencies |

## Remaining parity gaps

This change improves final-mile reliability; it does not make Theseus a clone of OpenCrabs. Remaining separate decisions include callbacks/reactions and inline keyboards, streaming/edit-in-place status, outbound media/tools and scheduled sends, correlation telemetry or a durable outbox, voice/STT interpretation, MTProto userbot behavior, and automatic group/member administration. Add one only when its own authority, persistence, and failure contract is explicit.

## Outcome

Build **reliable final-mile sending before adding more Telegram surfaces**. The branch now copies OpenCrabs' hard-won invariant—one bounded, fallback-capable send ladder—while keeping delivery, authorization, persistence, and interpretation distinct. A transient flood limit delays within a strict budget, malformed HTML gets one safe plain-text path, authorized files persist inertly, and terminal loss is explicit rather than silently recorded as delivery.
