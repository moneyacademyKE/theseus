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
| Auth | `src/bb_agent/web-auth.clj` | master password → session token, rate-limited; the login overlay is a native `<dialog>` |
| Search | `src/bb_agent/web_search.clj` | FTS5 index over transcripts, `/api/search` |
| Client | `web/js/*.js`, `web/css/deck.css`, `web/index.html` | vanilla ES modules, no build step |
| Vendored | `web/vendor/*` | marked, DOMPurify, uPlot, highlight.js — pinned, immutable-cached |

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
- **Instant paint**: the last snapshot is cached client-side
  (`theseus-deck-snapshot`) and painted at boot before the stream
  connects; the first tick corrects it. A corrupt cache is discarded,
  never trusted.

SSE auth rides `?token=` on this one route only (EventSource cannot set
headers); everything else uses `Authorization: Bearer`.

## Markdown pipeline

Transcripts and the RSI digest are markdown on the wire. The client
renders them through **marked → DOMPurify** (`web/js/md.js`): parse,
sanitize, insert. If either vendored lib fails to load, the surface
degrades to plain text — never a blank bubble. Every other DOM write
in the deck is `textContent`.

Code fences are colored by vendored **highlight.js** plus a separate
Clojure grammar; token colors come from the deck's own palette, not a
vendored theme. No hljs, no colors — never a blank fence.

## Search

`web_search.clj` keeps an FTS5 index (the go-sqlite3 pod) over every
session transcript, refreshed incrementally by file mtime — a file
that did not change is not re-read. `GET /api/search?q=` returns
ranked rows with snippet markers around the match. The client overlay
(`web/js/search.js`) runs on Enter, marks hits with the Custom
Highlights API (ranges over text nodes, never innerHTML), and a result
click opens the session.

## Render budget

Transcript bubbles and the digest carry `content-visibility: auto` +
`contain-intrinsic-size: auto 56px`: off-screen content is skipped by
layout (80 bubbles in the DOM, 5 painted), and the scrollbar stays
stable on remembered heights. Opening a long session settles the
scroll to the bottom via `scrollIntoView` on the last bubble, with
`overflow-anchor: none` on the transcript so scroll anchoring cannot
drag the position back mid-settle.

## Mini cockpit (Picture-in-Picture)

`web/js/pip.js` floats an always-on-top window (Document
Picture-in-Picture) carrying the led, the running job, and the clock —
stylesheets copied in as LINKs (CSP-clean), feature-detected: the
button stays hidden where the API is missing.

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
- `test/bb_agent/web_search_test.clj` — FTS indexing, incremental
  refresh by mtime, rank/snippet shape, over a sandboxed home.
- `bb lint` covers src and test; the deck's JS parses under esbuild.
