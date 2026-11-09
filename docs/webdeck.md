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

## State machines

The deck runs **twenty-odd state machines**. One is explicit code; the
rest are named here. Policy: **a new state gets named before it gets
rendered** — a visual state that is not in this map (or in `state.js`)
is a bug, not a feature.

### The explicit one: connection lifecycle — `web/js/state.js`

The only machine that was scattered across files (status text in
api.js, led + title elsewhere, error strings leaking in from
cockpit.js). Now one table, one write:

| Label | `data-conn` | Led | Meaning |
|---|---|---|---|
| `login required` | `locked` | red | no valid session; dialog owns the screen |
| `connecting…` | `connecting` | dim | handshake in flight |
| `cached` | `cached` | dim | painted from snapshot cache, awaiting first push |
| `live · push` | `live` | green | stream open |
| `live · poll` | `live` | green | stream abandoned, 5s poll of `/api/state` |
| `reconnecting…` | `reconnecting` | amber | dropped, EventSource retrying |
| `offline` | `offline` | amber | poll failing too — nothing reachable |

`setConn(label)` is the only write; `html[data-conn]` is the
machine-readable projection (tests and agents read it instead of
parsing text); status text, led, and document title all derive from
the same row, so they cannot disagree. Out-of-map transitions log
`[deck-fsm]` and apply anyway — the map is documentation, not a gate.
The client also watchdogs the server's 15s heartbeat: three missed
pings and the stream degrades to poll, because a half-open pipe (no
FIN — partition, sleep, tunnel death) never fires `onerror` on its
own. Poll-mode 401s land in `login required`, not stale text.

### The documented ones

| Machine | Where | States |
|---|---|---|
| login form | `main.js` | idle → unlocking… → success (reload) / rate-limited (timed) / not-set / bad-password |
| login dialog re-arm | `main.js` | Esc-closed → any click re-opens once (native `<dialog>`) |
| auth session | `api.js` + `web_auth.clj` | out → authenticating → in → expired (in-memory store: a server bounce logs everyone out) |
| login rate-limit | `web_auth.clj` | counting → locked-until (reset on success) |
| session view | `cockpit.js` | picker → loading → loaded / empty / unavailable |
| compose | `cockpit.js` | idle → sending (disabled) → queued / send-failed (text restored) |
| attachments | `cockpit.js` | idle → uploading (chips) → removable; per-file failure surfaces in the alert |
| approval | `cockpit.js` | new (focused) → deciding (disabled) → resolved / failed |
| job watch | `cockpit.js` + `web_turn.clj` | none → queued → running → done / failed → aged-out (server grace 30s, box hides after 5s) |
| scroll settle | `cockpit.js` | 5 rAF frames + 300ms tail → settled |
| search | `search.js` | closed → open → searching → results / empty / failed → highlighted → closed |
| PiP window | `pip.js` | unsupported (hidden) → closed → open (mirrored) → closed |
| column flash | `columns.js` | changed → flash class → removed |
| SSE channel | `web.clj` | joined (nil fp) → streaming (fp) → closed; late joiners get the next tick's snapshot |
| broadcaster | `web.clj` | stopped → running (1s pulse, 15s named ping) |
| turn engine | `web_turn.clj` | enqueue → running → done / failed (jobs/uploads in-memory) |
| search index | `web_search.clj` | per-file: unindexed / stale → indexed (mtime-driven) |

Invariants that are *not* machines: the markdown pipeline always
sanitizes-or-degrades (`md.js` — never raw innerHTML), and vendors are
immutable-cached while modules are `no-cache`.

### Agent contract (AX)

- `document.documentElement.dataset.conn` — the connection state,
  always current, zero parsing.
- `#api-state` carries `role="status"` + `aria-live="polite"`; the led
  carries an `aria-label`. Screen readers announce transitions.
- SSE events are typed: `state`, `ping`, `unauthorized` (terminal).
- Read surface: `/api/state`, `/api/search?q=`, `/api/usage`.
- Writes go through `setConn` only. Adding a state = adding one row to
  `STATES` in `state.js` — never a direct `textContent` write.

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
