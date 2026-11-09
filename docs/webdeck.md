# The Web Deck

The deck is Theseus' cockpit over HTTP: read-only columns over the same
state files Telegram reads, plus one write lane (the cockpit) that
queues real turns on the same engine. One auth gate, one engine, two
transports.

## Surfaces

| Piece | Files | What it is |
|---|---|---|
| HTTP server | `src/bb_agent/web.clj` | http-kit, `app` is one pure request→response fn |
| Data readers | `src/bb_agent/web_data.clj` | EDN state files → JSON rows (shared by routes and push) |
| Write lane | `src/bb_agent/web_turn.clj` | turn jobs, approvals, uploads |
| Auth | `src/bb_agent/web-auth.clj` | master password → session token, rate-limited |
| Client | `web/js/*.js`, `web/css/deck.css`, `web/index.html` | vanilla ES modules, no build step |
| Vendored | `web/vendor/*` | marked, DOMPurify, uPlot — pinned, immutable-cached |

Run: `bb src/bb_agent/web.clj` (binds 127.0.0.1 only). Reach it through
the cloudflared tunnel (`ops/launchd/com.theseus.tunnel.plist`). First
boot needs a master password: `bb scripts/set_web_password.bb`.

## Transport: push, not poll

One `EventSource` (`GET /api/events`) replaces every client poller:

- The **broadcaster** re-reads the deck every 1s and pushes a `state`
  event to every channel whose **per-channel fingerprint** moved. A
  channel with no fingerprint (just joined, or reconnected) gets the
  current snapshot on the next tick — nobody waits for a change to see
  data, and reconnects converge on the newest state within a second.
- A named `ping` event every 15s keeps the tunnel from closing a silent
  pipe **and** gives the client a heartbeat it can watchdog.
- `retry: 2000` pins the browser's reconnect delay.
- If the pipe stays dead ~20s across EventSource's own retries, the
  client degrades to a 5s poll of `GET /api/state` — the same snapshot,
  one request — and says so (`live · poll`).
- A dead token gets an explicit `unauthorized` event before the close,
  so the login overlay owns the screen instead of a retry loop.

SSE auth rides `?token=` on this one route only (EventSource cannot set
headers); everything else uses `Authorization: Bearer`.

## Markdown pipeline

Transcripts and the RSI digest are markdown on the wire. The client
renders them through **marked → DOMPurify** (`web/js/md.js`): parse,
sanitize, insert. If either vendored lib fails to load, the surface
degrades to plain text — never a blank bubble. Every other DOM write
in the deck is `textContent`.

## Vendoring policy

Third-party JS is vendored as-is into `web/vendor/`, license file
alongside, served from the static whitelist (exact paths only — path
traversal has no grammar to exploit). Vendors are `Cache-Control:
public, max-age=31536000, immutable`, keyed by the `?v=` the shell
pins; bump the query when re-vendoring. Our own modules are `no-cache`.

## Tests

- `test/bb_agent/web_deck_test.clj` — socket-level SSE client (frames
  are the wire format EventSource consumes), route/auth/cache/usage
  contracts, over a sandboxed home.
- `test/e2e/web_test.clj` — the pure `app` fn end to end, including a
  real turn through the engine and every shell asset actually serving
  (the md.js 404 lesson).
- `bb lint` covers src and test; the deck's JS parses under esbuild.
