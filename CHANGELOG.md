# Changelog

## Unreleased

### Added — gap-analysis build (2026-11-11)

- **Native `web_search` tool** (bk-a325; `5633faa`, repaired `10ecda3`): keyless internet search joins the 9-tool registry. Brave's HTML SERP is the backend — direct organic URLs (no redirect unwrapping), titles/snippets/domains parsed with the existing no-DOM approach, and a **query-relevance gate** that returns an honest zero instead of unrelated hits. Bing was implemented first and dropped on live quality: it served WhatsApp forum posts for a Babashka query, and some redirect destinations decoded truncated. Live probes now demand ≥2 relevant results: 5/5 for "Babashka scripting documentation" and 5/5 for "Clojure spec guide", all URLs intact. Unit suite 319 tests / 1,481 assertions, 0 failures; targeted kondo 0 errors.
- **A2A server on the web deck** (bk-a325; `2c839f1`): `GET /.well-known/agent.json` and `agent-card.json` publish the card; `POST /a2a/v1` (and `/a2a`) speaks JSON-RPC 2.0 `message/send` + `tasks/get`, mapped onto the existing durable `web_turn` job lane — no second engine, queue, or persistence. Bearer-gated by a mode-600 `state/a2a-token` (hygiene-allowlisted, owner-DM delivery only); unauthenticated sends get 401. OpenCrabs' real `a2a_send` discovers the card; an authenticated live roundtrip queued `/usage`, polled `submitted → completed`, and returned the real reply artifact. `bb test:e2e:web`: 32 tests / 337 assertions, 0 failures.
- **Tool-outcome timing + report** (bk-a325; `eb56e7d`): the existing `usage.edn` funnel — already the single per-call tool ledger — now records wall-ms and executed? per event (never arguments), and `scripts/tool_ledger.bb` prints per-tool call/ok/error/denied/avg/max over the real ledger. A live `git_status` probe recorded `16ms, executed=true` with no argument keys. No second ledger file was added; the RSI digest schedule (`rsi-daily`, 09:30 EAT) already consumes the funnel.

### Added — gap-analysis follow-ups (2026-11-12)

- **Bounded MCP client** (bk-c6c7; `e6300b1`): `bb-agent.mcp-client` speaks the MCP Streamable HTTP transport — initialize, session/protocol headers, the initialized notification, JSON and SSE response decoding, strict response-id matching, `tools/list`, `tools/call`. Errors are redacted, never raw bodies. Live against BrowserOS neo 0.0.93: 27 tools discovered, a read-only `tabs list` completed, session id and tab payload withheld from all output. 6 focused tests / 30 assertions; probe: `bb scripts/probe_mcp.bb`.
- **`browser_read` tool** (bk-5c6a; `c8c8612`): the registry's 10th tool is a single-purpose BrowserOS page reader — of BrowserOS' 27 advertised tools it uses exactly two (`tabs`, `read`). Each call connects fresh, lists tabs (a liveness gate whose payload never reaches output), opens one owned tab to an HTTPS-only URL (no userinfo, no fragments; `http:`, `file:`, `javascript:` refused before any connection), reads it as text, closes the tab, and returns at most 20,000 chars framed as untrusted content. Fail-closed throughout: an MCP tool-level error still closes the tab and never leaks the failed read's payload; an unparseable open result refuses to act rather than guess a tab. Deliberately NOT auto-safe — a real browser can carry authenticated sessions, so an auto-safe turn denies it and the tool requires explicit approval or a policy rule. No click, type, evaluate, credentials, or payment surface exists on the tool. Full suite 337 tests / 1,557 assertions, 0 failures; `bb scripts/probe_browser.bb` all-green live (auto-safe denial, non-HTTPS rejection, real example.com read, zero tab leak).

### Added — document processing + live A2A borrow (2026-11-12)

- **PDF attachment ingest is real** (bk-31e2; `d4937bc`): poppler 26.11 installed (`/opt/homebrew/bin/pdftotext`), and `telegram_extract` gained `resolve-bin` — PATH lookup first, then brew prefixes — because launchd services run a minimal PATH where bare-name lookups silently missed the installed reader, and every PDF attachment extracted to a silent nil. A committed fixture + e2e test pin the marker-sentence extraction end-to-end.
- **XLSX attachment ingest** (bk-31e2; `2a687e7`): bb-excel 0.1.1 (babashka-native, no POI) becomes the repo's first Maven dep; sheets render as `## <name>` headers with one `A: value | B: value` line per row, bounded 20k chars like every extract path. The fixture test caught a real bug on the way in: `{:keys [name sheet]}` destructuring shadowed `clojure.core/name`, so the row renderer threw and the catch-all silently returned nil — the exact silent-nil disease this work exists to kill. Fixed with renamed locals.
- **`a2a_task` tool — the borrow lane** (bk-31e2; `6146d0e`): registry tool #11 sends JSON-RPC `message/send` + `tasks/get` to any A2A agent and waits for the reply. Approval-gated (not auto-safe); the stored OpenCrabs bridge credential attaches **only to localhost targets** — the model chooses the URL, so a remote host can never receive this machine's token (pinned by a test). Token provisioned file-to-file (`state/a2a-opencrabs-token`, mode 600, outside the repo, hygiene-allowlisted in `4e27c48`) — never an argument, never printed. **Live receipts**: discovery ok, unauth send 401, ping roundtrip `completed` → `pong`, and a full borrow — a task asking for a one-page PDF came back with the path, the file on disk, and its marker sentence extractable by this repo's own pdftotext lane (`bb scripts/probe_a2a_client.bb borrow`, exit 0). The live probe caught what fake-server tests could not: OpenCrabs' server requires `message.role` (A2A v0.3) — pinned in a wire-shape test.
- **`documents` skill** (bk-31e2): `skills/documents/SKILL.md` pins the whole matrix — ingest (pdf/docx/xlsx/builtins), generation (markdown/hiccup/textutil/BrowserOS-pdf/bb-excel), the `a2a_task` borrow pattern, and the named dead ends (clj-pdf family dies on `java.awt.Graphics2D` in bb's native image). 44 skills registered, runtime and CLI.


### Changed — optional capabilities are default-off (2026-11-12)

- **Capability gating** (bk-ac0b; `7173035`): owner directive — "these new capabilities are low priority. they should be optional." `web_search`, `browser_read`, and `a2a_task` now ship **dormant**: `tools/active-definitions` filters them out of every provider prompt unless `:tools/enable-optional ["web_search" ...]` opts them in (strings or keywords both work; unknown names are ignored — enabling is additive, never a source of new tools), and the dispatch gate refuses a call to a not-opted-in tool with a `:dormant` denial whose message names the config recourse — an unadvertised tool can no longer execute just because a model hallucinated its name. The live registry returns to the pre-capability 8 tools (verified live after service restart: `read_file, write_file, shell, search, git_status, telegram_send_file, web_send_file, launch_goal`, config key nil); handlers stay merged, so enabling is one config line and zero code. Unit 350/1620/0; advertisement lane 4/27/0 (default excludes, opt-in includes, dispatch denies); targeted kondo 0 errors. Passive ingest paths (pdf/xlsx on attachment arrival) cost nothing at rest and are unchanged; the deck's A2A route stays (localhost-only, mode-600 token). Enabling any of them later is one line in `~/theseus/config.edn`.


### Fixed

- **AI stock brief candidate screen** (`8230172`, bk-097f; issue #37 remains open): Binance spot pairs must also have a `bStocks` tag in its asset registry, and new refill candidates must be a stock/ADR in one of TradingView's Electronic Technology, Technology Services or Communications sectors. The fresh-cache version invalidates older unverified listings; unavailable or malformed registry data fails closed rather than accepting ticker-shaped crypto assets. This is a broad sector screen, not proof of an AI focus; retail/consumer tech such as Amazon and Tesla are excluded. The existing queue, fallback recycling, and an already-running brief are separate paths that still need verification. `bb test:bb-agent`: 303 tests / 1415 assertions, 0 failures; live registry + sector probe: 100 bStocks candidates, 24 passed, BN/DG absent.


### Added

- **Web deck — full Telegram parity** (bk-3eeb): the 2026-11-10 audit's every gap closed (contract: `docs/webdeck-parity.md`; witness: `docs/webdeck-parity-witness.edn`). **Artifacts**: the `web_send_file` tool registers a file the turn produced; `GET /api/artifact/<id>` serves it authenticated (Bearer or `?token=` on that route only, because download links cannot set headers), revalidating the path against the delivery roots and refusing symlink swaps at serve time. **Goals**: `GET /api/goal-detail?id=` (verdict, control state, bounded run-log tail) and `POST /api/goal-control` (pause/stop write the same control files `bb goal pause` writes into the goal's run workdir; resume/cancel ride the goal bridge with the goal's registered chat/topic). **Conversation transport**: transcripts paginate from the tail (`?page=` + page/total/has-more envelope, fields untruncated) with a "load older" button; the cockpit reloads the open transcript when the session file grows from ANY channel (SSE session-list mtime, the busy session excluded); microphone (MediaRecorder, feature-detected, 10-minute cap) and camera (`capture="environment"`) ride the same upload+turn lane file attachments use; the last user input can be edited and re-run — the engine drops the old turn before the re-run (one replacement, never a duplicate; a blank edit is refused before any history is touched). **Access**: session tokens persist to `state/web-sessions.edn` (a deck restart keeps the browser logged in, expiry still honest); `POST /api/logout` revokes this token; `POST /api/password` rotates the master password from the browser (kills every session, hands the rotating browser a fresh owner token); an optional read-only password (second record `.web-pass-ro.edn`) logs in with a `:ro` role that passes every read and 403s every write through one `guarded-owner` gate. **Schedules**: `POST /api/schedule` (validates id/prompt/cron against the same matcher the scheduler daemon runs) and `DELETE /api/schedule/<id>` manage the shared registry from the schedules column. Code split while under the ceiling: `bb-agent.web-http` (transport plumbing), `bb-agent.web-routes` (management routes), `web/js/transcript.js` / `media.js` / `paging.js` / `schedules.js`; `node --test test/web/*.test.mjs` pins the pure decisions and the shell affordances. `state-hygiene` allowlists `web-lane.edn` + `web-sessions.edn`. Two rows remain PARTIAL with named owner actions (witness): the vision provider is disabled at the gateway account, and a stable URL needs `~/.cloudflared` credentials + a domain choice — the quick tunnel stands verified meanwhile.
- **Web deck — explicit state machines** (bk-18e6): an audit mapped the ~20 implicit state machines running the deck (full table in `docs/webdeck.md` §State machines). The one that was scattered across files — the **connection lifecycle** (status text in api.js, led + title at other call sites, error strings leaking in from cockpit.js, and text/led set separately so they could disagree) — is now one explicit FSM: `web/js/state.js`, states as data (7 labels → canonical `data-conn`, led, title projections), `setConn()` the only write, illegal transitions logged as `[deck-fsm]` and applied anyway (the map is documentation, not a gate). `html[data-conn]` is the machine-readable projection; agents and tests read it instead of parsing status text; the amber `warn` led separates "trying" (reconnecting/offline) from "locked" (red). Two audit finds fixed: the poll-fallback path left stale state text on a 401 (now `login required`), and the client never watchdogged the server's 15s heartbeat — a half-open pipe (no FIN: partition, sleep, tunnel death) would show `live` forever; three missed pings now degrade the stream to poll. Cockpit errors render in the alert element they belong to, not the connection line. Naming policy: a new state gets a row (here or in `state.js`) before it gets rendered anywhere.
- **Web deck — SSE push channel**: `GET /api/events` streams the whole deck as Server-Sent Events — one push replaces all nine client pollers (seven columns, the cockpit job ticker, the session refresh). The broadcaster re-reads the same readers the JSON routes use and delivers to every channel whose **per-channel fingerprint** moved — a client that joins late (or reconnects) gets the current snapshot on the next tick, never a blank wait for change; a named `ping` event every 15s keeps the cloudflared tunnel from closing a silent pipe AND gives the browser a heartbeat it can watchdog; `retry: 2000` pins the reconnect delay. If EventSource cannot hold a pipe for ~20s, the client degrades to a 5s poll of `GET /api/state` (the same snapshot as one route) and says so — `live · poll`. EventSource cannot set headers, so the stream accepts the session token as `?token=` on that one route only; a dead token gets an `unauthorized` event and a close, so the login overlay takes over instead of a blind retry loop. Deck e2e tests ride a real socket (`bb-agent.web-deck-test`, registered in `bb test:bb-agent`).
- **Web deck — safe markdown rendering**: the RSI digest and the cockpit transcripts render as markdown instead of walls of asterisks — vendored marked + DOMPurify (UMD, local, no CDN, no build step) behind a tiny `web/js/md.js`; the sanitized result is the only innerHTML-with-data on the deck, and a missing lib degrades to plain text, never to unsanitized HTML. Verified in a real browser: login, live push, rendered digest, and a digest file swap arriving unprompted.
- **Web deck — usage sparkline**: `/api/usage` buckets the usage ledger into 30 zero-filled daily rows (turns, tokens, honest cost — unpriced is 0.0, never hidden), and the system column draws tokens/day with vendored uPlot (51KB, MIT, immutable-cached). Refreshes when the turns payload changes; no uPlot, no chart.
- **Web deck — static cache policy**: vendored libs are `Cache-Control: immutable` for a year, keyed by the `?v=` the shell pins; our own modules revalidate on every load.
- **Web deck — strict CSP + noscript**: `default-src 'self'` and friends; the deck loads zero external resources, inline style attributes are blocked (defense-in-depth behind DOMPurify), and a no-JS visitor sees the truth instead of a blank page.
- **Web deck — the UI/UX arc** (bk-c821, 21 commits `d77334c`–`7267eeb`): the deck's read surface rebuilt for humans. The stylesheet split by concern (deck/login/cockpit), small type raised and faint contrast fixed, a calmer update flash that respects `prefers-reduced-motion`, columns stacking on narrow screens, connection state announced and mirrored in the document title, the login overlay a real `<dialog>` with honest lockout feedback, honest column counts and empty states, semantic EAT timestamps everywhere, the tools funnel as a real table and system facts as a definition list, sessions opening from their column via a labelled picker (model + age), the transcript as a live log with speaker labels, compose that grows/waits/fails out loud, attachment chips as real buttons, job cards with elapsed time and step count, approvals that focus and lock while deciding, the usage sparkline with a summary and resize handling, and markdown links that open safely (`noopener`) with tables that scroll. Verified live in a real browser: login → push stream → a digest file written on disk arrives unprompted, rendered with headings, tables, and safe links.
- **Web deck — the round-2 tech arc** (`f389703`–`f4eb5cf`, 9 commits): six scan-2 picks, each browser-verified on a sandboxed deck. A **render budget** for the unbounded surfaces — `content-visibility: auto` + `contain-intrinsic-size` on transcript bubbles and the digest, plus a `scrollIntoView` settle with `overflow-anchor: none` so opening a long session lands at the bottom and stays there (80 bubbles in the DOM, 5 painted). The **login dialog is a native `<dialog>`** — `showModal()` owns the focus trap, Esc handling, and inert background; the hand-rolled JS trap is gone (net −7 LOC, plus the one `margin: auto` restore the reset stylesheet owed it). **Instant paint**: the last snapshot is cached client-side and painted at boot before the stream connects (corrupt cache discarded, never trusted), corrected within the first push tick — proven by freezing the server mid-boot and watching the deck paint with a dead EventSource. **Code fences get syntax colors** through vendored highlight.js + the Clojure grammar, token colors from the deck's own palette. **Session search** — the real hole, 615 sessions and zero way to find anything: `web_search.clj` keeps an FTS5 index over every transcript through the go-sqlite3 pod, refreshed incrementally by file mtime; `GET /api/search` returns ranked rows with snippet markers; the client overlay runs on Enter, marks hits with the Custom Highlights API (ranges over text nodes, never innerHTML), and a result click opens the session. And a **Document Picture-in-Picture mini-cockpit** — an always-on-top led/job/clock window (`web/js/pip.js`), stylesheets copied in as LINKs, CSP-clean, feature-detected, verified live through its open/mirror/close lifecycle. The e2e shell test now asserts the native dialog's markup — its two stale `role="dialog"`/`aria-modal` assertions rode three commits red until this arc's close-out gate caught them (the per-concern commit gates ran the unit suite only; the e2e web suite is the one watching shell markup).
- **Docs**: `docs/webdeck.md` (the deck's architecture on one page) and `ops/cloudflare-access.md` (edge identity check on the existing tunnel, ~10 minutes, free under 50 users).
- **Tooling**: `bb release-assets` builds the standalone binary and attaches it + its sha256 to the GitHub release (verified live on v1.2.0); `bb lint` now covers the test tree too (six private-call errors fixed with var-quotes); the e2e web suite (14 tests, a real turn through the engine) is wired into `test:e2e:web` and `test:e2e:all` — it existed but nothing ran it.
- **CI fixed for real**: every run since Nov 7 was red on environment, not code — unpinned giant-asset downloads and `curl -sL` without `-f` saved error pages as zips and exited 0. Babashka is pinned to the dev version (1.13.222) with install retries, downloads are `curl -fsSL --retry 4`, and `web/**` changes now trigger the suite. The go-sqlite3 pod rides the actions cache after its first successful download (`c95fe6b`) — the release CDN's 504 bursts (two hours of red, Nov 9) no longer reach the suite, and `bb lint` passes one target per `--lint` invocation (`c3de5c0`).
- **Host-portable rsi-tools e2e**: the funnel test executed a real `git_status` with a hardcoded owner-home cwd — `:ok` on the dev machine, `:error` on the CI runner (run 42602535756). It now runs in a throwaway `git init` repo: same execution path, zero host assumptions.
- **Host-portable knowledge guards e2e**: same disease, third patient: `guards` with no positional dir falls back to the DEFAULT brain dir, `(config/home)/brain/knowledge` — exists on the dev box, absent on the CI runner (run 42607367666), so local green hid runner red. The argv-parsing test now rebinds `config/home` to a throwaway home carrying the fixture pages: the invocation under test is the parsing, not the default's existence.

### Fixed

- **Web deck — dead boot, two stacked breaks** (bk-28a2; incident 2026-11-10 — the owner loaded the deck through the tunnel and got the static "connecting…" shell with no login prompt). Break 1, the operative one: tasks 9–12 added `media.js`, `paging.js`, `schedules.js`, `transcript.js` but the static whitelist in `web.clj` never learned them — the browser's module fetches 404'd, which Chrome reports as the generic "Failed to fetch dynamically imported module", and every gate stayed green because curl checks only ever hit `main.js`/`api.js` (whitelisted since day one) and `node --check` never fetches anything. Break 2, hiding behind it: `media.js` imported `put` from `api.js`, which never exported it — a hard SyntaxError at module-graph resolution, before any code runs. Fixed in `e66e5c9` and this commit; the deck then verified **end-to-end in a real browser** through the tunnel: all 14 modules resolve, the clock ticks EAT, the login overlay opens, and a wrong password renders the honest error. Two new gates pin the seams nobody was watching: `scripts/check_web_graph.bb` (every import in `web/js` must resolve to a real export; proven against the broken tree before the fix landed; comments stripped so prose can't pose as code) wired into `test:e2e:web` as `module-graph-resolves-test`, and `every-disk-module-is-served-test` (every `.js`/`.css` on disk under `web/` must serve 200 through the app — the old whitelist test compared the table against itself, so drift was invisible). Web e2e suite: 27 tests / 300 assertions.

## v1.2.0 - 2026-11-09

### Added

- **Web deck — Theseus over HTTP** (`597c579`, `6135479`, `a44b615`, `900f2ac`, `da4b9f0`): a read-only JSON deck API over live state, a TweetDeck-style column UI, launchd services for the deck and its Cloudflare tunnel, master-password login replacing the gui token, and the interactive cockpit — Telegram parity over HTTP.
- **Preflight content guard** (`d89c5f4`): pattern table as data, findings masked (a guard that echoes the credential it caught leaks it), wired at EVERY door a file leaves through — `telegram-upload/send-file!`, `scripts/send_document.bb`, `goal_watch.bb upload!`. A credential in a text deliverable is refused before any HTTP call, and the refusal never echoes the secret.
- **Knowledge ns** (`8e589b4`): six scout recommendations as one ns — staleness marks, wins ledger, protection tiers, pre-write snapshots, curator dry-run, no-regression promotion gate.
- **RSI learn scout + identity gate** (`a49b324`, `120f0e6`): daily GitHub self-learning digest, propose-gated. Identity is the repo id, never the casing of its name — the seen ledger becomes rows `{:name :id}`, and fetch/propose print the id-blind denominator beside the gate (measured live: 177 of 221 ledger rows could not settle a rename).
- **Coordinates audit** (`c4506b0`): do the `file:LINE` cites in `brain/*.md` resolve? DANGLING / OUT-OF-RANGE / UNSUPPORTED rows are named, never blocked; external cites are counted as unverified, not broken. Report-only.
- **Goal dispatch liveness** (`7bc9b83`): `spawn-launch!`/`spawn-resume!` stamp the authoring pid they spawn; `bb goal-liveness` audits it and the ops screen (`bb stats`) carries the liveness line whose buckets sum to the denominator.
- **Schedule command lane** (`ca7e935`, `36e6d05`): `:schedule/command` entries execute directly — no LLM between cron and stdout — and failures post terse notices instead of raw crash boxes.
- **RSI tool-outcome funnel** (`2ffa248`): one recording site in `tool.clj`, a digest tool table, tool-failure opportunities; `:fake` provider turns stay out of the live ledger.
- **`/goal watch <name>`** (`835cb11`): attach live progress to any running goal.
- **Onboarding** (`9dead4c`): `config.example.edn` template + Telegram setup walkthrough, doctor as Quick Start step 2.
- **Decoupled from OpenCrabs** (`34ba974`): `THESEUS_HOME` + an owned launchd fleet.
- **CLI utilities** (`f8e3c2f`, `3ad24f5`): `dispatch_goal.bb` (goal-spec dispatch as a CLI, replacing the nested-quote one-liner) and `send_document.bb` (Bot API document delivery as a CLI).

### Fixed

- **Unpriced is not zero** (`7bc9b83`): 773 events over 34.5M tokens printed `$0.0000 est` because no catalog entry priced them — usage now splits priced from unpriced, withholds the dollar figure entirely when nothing is priced, and the digest says `cost unpriced` and names the providers.
- **String booleans stop inverting flags** (`4b92122`): `write_file {:append? "false"}` APPENDED, because every non-nil string is truthy — the model asked to overwrite and got the old bytes kept. The argument heal runs at the one seam every tool request passes through, derived from the provider-advertised schemas, idempotent, and leaves unhealable values for validation to reject honestly.
- **write_file protection tiers** (`ec78763`): classify and snapshot BEFORE the bytes move — a `:forbidden` path is refused with nothing written, a `:strict` (brain/) file keeps its pre-write copy.
- **Brief lane hardening** (`623ee27`, `ddcdc45`, `84c99fa`, `2a1379f`, `e522155`, `d70ecfa`, `f635703`): the generation budget rides the wire with the lock released on death, a 600s provider timeout, the dossier file written before it is sent, three generation passes each logged as it completes, the lane rides the daemon default model, and the exhausted queue refills from the scanner-validated Binance universe.
- **Goal runner** (`31b9a1a`, `8fea105`, `9367e82`): the logs dir is created before run state resets (fresh workspaces died at launch), a fresh launch clears stale run state (old iter ledgers outranked the new run's world), and declared deliverables ship on ALREADY SATISFIED too.
- **Doctor** (`ebc0306`): flags config keys nothing reads.
- **Release tooling** (`27745f0`): cuts annotated tags — `--follow-tags` silently skips lightweight ones.

### Changed

- Deep-dossier briefs: fundamentals in the prompt, `.md` documents as the delivery (`1f4f139`); reruns inject prior coverage — new takes only, never a rewrite (`bc3bb07`).
- Authoring total-budget ceiling raised 45min → 2h and made optional (`0b1c54b`, `fd92959`).
- Skill-economy baseline measured: 43 indexed, 1 ever read — no prune until a claims-era fleet exists (`196142b`).
- RSI review receipts and mandate relaunch documented (`e69a74c`); README brought current (`377bcdc`, `2cabac7`); new suites wired into their e2e tasks (`e3e8270`, `870c6b8`).

## v1.1.0 - 2026-09-12

### Added

- **`:approval/mode` config flag** (`3c8c86b`): `:auto-all` substitutes for the default `:ask` policy — shell and writes execute with no human checkpoint. The constitution (`brain/rules.clj`) vetoes *before* the mode applies, so rm/sudo/secrets/force-push stay denied for everyone. Absent key = previous behavior, one-line revert.
- **Flow progress compression** (`d44d4f0`): consecutive same-tool runs in the live progress message collapse into `tool ×N` run lines — a 200-round turn renders as a couple dozen lines instead of dying on Telegram's 4096-char limit. Failures and the in-flight call always keep their full line; structural truncation survives only as the final backstop for pathological distinct-tool turns.
- **Poller instance lock** (`7cc17cc`): `state/poller.pid` — a live foreign pid refuses the boot before any side effect; a stale pid is taken over. Double-poller 409 `getUpdates` wars are now structurally impossible, not manually cleaned.
- **Scheduled turns run under the constant approver** (`3c39914`): the cron lane has no human, so schedules ride the same approval contract as goal authoring instead of dying hourly on "needs your approval". First-ever schedule test suite.
- **Retry announces before backoff** (`081c540`): a retrying provider tells you it's alive instead of reading as a wedge.

### Fixed

- **A constitution veto is not an approval request** (`a311630`): the turn loop now asks "is a human actually pending?" (denial source `:approval`) instead of "was anything denied" — a veto reports truthfully as a veto instead of a ghost checkpoint nobody could act on. Same commit: `authoring.edn` no longer records `:result :authored` when the validator rejected — the receipt agrees with the filesystem.
- **Session bloat cap** (`23ddb9d`): tool-result string fields cap at 8KB *at persistence* with a visible truncation marker; the model still sees full output in-flight. The RSI-topic 1.4MB/day regrowth is over.
- **Poll failure backoff** (`3138b06`): consecutive failures back off geometrically (2s→32s cap) and log sparsely — 1st, every 30th, one recovery line. A dead network costs ~4 lines/hour instead of the observed 2,400-line storm.
- **Secrets fence narrowed** (`46ca7b0` pins): `deny-secrets` requires path context (a slash or file extension) — prose about secrets passes, `~/.aws/secrets` still dies. The fence no longer eats commit messages discussing the fence.
- **Goal authoring budget coherence** (`e5f3aae`, `05d9862`): the request budget is clamped to what the idle clock can see; authoring errors land on a measured path.
- **Detached spawns own their redirects** (`181f396`): "detached" children no longer die with a closed stdout pipe mid-run.
- **Watcher pins `config/home` from the args file** (`3eaab51`), like the launcher and resume scripts — no ambient-env reads in children.

### Changed

- RSI signal counts only verified rescues; fallback model threaded (`c7db50b`, `57a06d5`).
- Scheduler state and per-session models join the `state/` allowlist (`c5a52f8`).
- README and docs/INDEX updated to the v1.1.0 state of the app.

## v1.0.0 - 2026-09-09

### Added

- **V1 — detached authoring + live progress** (`fafb577`, `54bc1a5`, `2282b9e`): `/goal` and build-verb launches scaffold and ack in milliseconds; authoring runs in a detached process with an edited-in-place progress message in the owning topic, a pid-stamped per-topic authoring lock, and a two-clock stall detector (8-min idle fuse on tool-call emits, 45-min ceiling). `:goal/author-model` overrides the model for authoring only; every authoring run persists `authoring.edn` (model, rounds, duration) so latency claims are measured, not vibes. A child process reads config from the dispatching home (`:home` in the args file), never ambient env.
- **V2 — artifacts out** (`b314e1a`): `project.edn` declares `:deliverables`; on fulfillment the watcher uploads them to the owning topic via multipart `sendDocument` (max 3, ≤45 MB each, missing files skipped honestly).
- **V3 — goal control from chat** (`3f98952`): `/goal cancel` appends an honest HALT verdict to run.log before killing the runner (the watcher announces ⛔ with attribution, never "gave up waiting"); `/goal status [name]` reads the runner's own words; workspaces stay resumable.
- **V4 — skill economy** (`5dc2233`): `bb skill-economy` renders a claims-vs-reads ledger over goal workspaces (`:skills-used` claims against tool-level SKILL.md read receipts) and names prune candidates — skills claimed but never once read.
- **V5 — durable goal queue + concurrency cap** (`72142c0`): a second `/goal` in a busy topic queues (`state/goal-queue.edn`, durable FIFO) instead of being refused; `:goal/max-concurrent` (default 2) caps simultaneous goals. Drains at goal end, authoring end, and poller boot — always by spawning the launcher, never authoring inline.
- **V7 — daily owner digest** (`3606ee8`): at/after 08:00 local, once per calendar day, stats render into the owner's DM through the durable outbox. The poller cycle is the clock; no cron infrastructure to forget.
- **`bb release`** (bk-5e88): changelog-driven release cutting — promotes `## Unreleased`, bumps `version.clj`, commits and tags; `--dry-run` prints the plan.

### Changed

- Authoring no longer runs on the poll loop (the dogfood's silent 44-minute park); the child launcher, watcher, and resume scripts honor `:base-url` (self-hosted gateways and test fake servers ride the same ladder as production Telegram).
- Stale-pid handling consolidated: the registry prunes dead entries on read; the authoring lock treats unreadable pids as free.

## v0.9.0 - 2026-09-09

### Added

- Production-hardening week, roadmap R1–R5 (product-readiness audit 2026-09-09):
  - **Durable outbox** (`bb-agent.outbox`): every outbound message persists under `state/outbox/` before the send attempt; only a confirmed send deletes it. A drain replays survivors every poll cycle, dead-letters after 5 attempts (reason-tagged). Kill-mid-send no longer loses the message (P0).
  - **Graceful shutdown**: SIGTERM flips a lifecycle flag and gives an in-flight cycle up to 10s to finish — kills the kickstart 409 `getUpdates` conflict race at its root (P1).
  - **Boot health**: doctor-lite runs at poller boot, 11 checks printed; any error shouts once to the log and the owner DM through the outbox (bk-1825).
  - **Log cap** (`bb-agent.log-cap`): boot-time in-place truncation over 10 MB, whole-line tail preserved; launchd's O_APPEND fd stays on the inode (bk-e6ff).
  - **`bb stats`** (`bb-agent.stats`): one-screen ops summary — usage events/tokens, outbox depth + dead letters, goal verdict counts — read from ledgers that already exist. No metrics stack (bk-203f).
  - **State hygiene gate** (`bb-agent.state-hygiene`): `state/` holds only the durable allowlist; scratch exiled to a sibling `scratch/`. Gate pins it in every e2e run (bk-a764).
  - **Authoring stall detection**: goal author/resume runs under a progress-based fuse — 8-min idle timeout keyed on tool-call emits, 45-min ceiling backstop — instead of either parking forever on a wedged provider or guillotining honest long authoring at a fixed wall-clock budget (bk-963f, recalibrated post-dogfood b44994d).
  - **`:already-satisfied` verdict**: a goal whose predicate already holds at iteration 0 reports 📦 pre-existing evidence instead of ✅ claiming this run's work (bk-e827).
  - **Approval buttons gate** (`:approval-buttons`, default on) (bk-9640).
  - **Offset semantics suite**: poll-cursor fail-loud on corruption (replay-storm prevention), reply-ledger self-heal on non-map corruption, id coercion — two latent defects found by the pins (bk-1c3b).
  - **Boot-load pins**: fresh bb processes must analyze the poller and goal-runner require chains; red-verified by fault injection (bk-1fe8).
  - **Dogfood tooling**: `scripts/collect_goal_evidence.clj` separates skill path-refs (tool-level SKILL.md reads) from bare index mentions; `scripts/relaunch_goal.clj` relaunches a stalled goal without re-authoring (dogfood bk-ea9e).
- Telegram delivery integrity (`bb-agent.telegram-delivery`): all finals and approval replies now validate both HTTP and Bot API success, retry only 429/`retry_after` failures for at most 3 attempts, cap each inline wait at 30 seconds, preserve chat/topic/reply routing on every attempt, fall back once from a rejected HTML chunk to plain text through the same checked path, stop later chunks after terminal failure, and surface structured failure context without message content or credentials (issue #25; `docs/TELEGRAM_DELIVERY_INTEGRITY_PARITY.md`).
- Authorized inbound Telegram attachments (`bb-agent.telegram-attachment`): documents and supported media persist as inert bytes under `channel_attachments/telegram/<chat-id>/topic-<thread-id>/` before the agent turn. Authorization runs before `getFile` or download; filenames and Telegram paths are sanitized; size is bounded by `:attachment-max-bytes` (20 MiB default). No STT, parsing, execution, or automatic trust is implied.

### Verification

- `bb test:e2e:all`: 31 test namespaces / 171 tests / 765 assertions, 0 failures/errors; rewrite docs verified.
- Focused Telegram adapter/delivery/attachment suite: 11 tests / 62 assertions; group/topic 10/55; rich 5/8; guard 4/7 — all green.
- Zero new dependencies. Changed source modules: `telegram.clj` 160 LOC, `telegram-delivery.clj` 179 LOC, `telegram-attachment.clj` 154 LOC.

- Telegram gateway tier 2 (`bb-agent.telegram-group` + adapter/guard wiring): sender-scoped group ACLs, per-group `:open`/`:respond-to` policy, mention/reply activation, bot-loop suppression, genuine forum-topic session keys (`chat-id + topic-id`), topic-preserving final/approval replies, sender/reply context, command-suffix normalization, and a shared-session privacy gate that blocks private/global memory attachment and semantic indexing in groups. Verified live against the target shape: **Sly Theseus** (`-1003995594829`), forum-enabled with `@eileenslybot` as admin. Group suite 10/55; full gate 26 suites, 147 tests / 613 assertions, zero failures/errors (issue #18; `docs/TELEGRAM_GROUP_PARITY.md`).
- Telegram gateway tier 1 (`bb-agent.telegram-render`, `bb-agent.telegram-guard`): md→HTML rendering with 4096-char splitting, fail-closed owner allowlist (`:allowed-chat-ids`), HTML `parse_mode` on gateway replies, continuous `bb telegram poll` loop, and a deny-path e2e asserting denied chats get no reply and no session. Suites: telegram-rich 5/8, telegram-guard 4/7, gateway 4/22, all in `test:e2e:all` (GAP_ANALYSIS §23).

### Changed

- `telegram.clj` (695 LOC, last ceiling violation) split into `bb-agent.telegram-intake` (update consumption, 436 LOC) and `bb-agent.telegram-lifecycle` (polling, durability ledger, shutdown, boot, 278 LOC). Zero behavior change; black-box e2e untouched (bk-bf8b).
- `goal_bridge.clj` split into `bb-agent.goal.registry` / `goal.outcomes` / `goal.progress` (bk-c60f).
- A config with no `:telegram` section now fails `bb doctor` (exit 1) — telegram-first is the product (bk-1825).

## v0.8.0 - 2026-08-30

### Added

- Tool advertisement (`bb-agent.tools`): openai-compatible requests now carry a `:tools` array advertising the registry — `read_file`, `write_file`, `search`, `shell`, `git_status`, with JSON-schema parameters. Real models could never call tools they were never told about; the fake provider's hardcoded emission masked this for seven releases. One-line provider wiring; e2e pins the request body and the tool_calls parse path. Found by theseus' first real tasks on a genuine endpoint (GAP_ANALYSIS §22).

## v0.7.0 - 2026-08-30

### Added

- RTK output filters (`bb-agent.rtk` + `bb-agent.tool` seam): ported from OpenCrabs' Rust Token Killer — the rules, not the proxy. Shell and git_status output is compacted before it reaches the provider (ANSI stripped, noise lines dropped, long output capped with a `[rtk: N→M lines]` marker) so tokens buy answers instead of table formatting. Rules are data: built-in defaults (`ps`, `lsof`, `git-log`, `dig`) plus `<home>/rtk-filters.edn` user rules; malformed user files fall back to defaults. Gated by `:rtk {:enabled true}` (default off); results carry `:rtk` savings stats. Zero dependencies — no external binary.
- RSI v1 (`bb-agent.rsi` + `bb rsi digest|analyze|propose`): ported from OpenCrabs' self-improvement loop, minus the autonomy. `digest` aggregates the usage ledger per provider (turns/ok/fail/fallback-hits); `analyze` derives opportunities — provider failure rates, fallback pressure — behind a 50-event minimum gate; `propose!` appends deduplicated suggestions to `brain/improvements.md` for owner review. The v1 autonomous apply-LLM is deliberately refused: no LLM writes brain files unattended until the proposal ledger earns it.
- Turn outcomes: turns record `:ok` (false on the tool-error path) so the usage ledger can learn from failures — the substrate RSI analyzes.

### Changed

- `bb rsi digest` prints the digest table; `brain/improvements.md` dedup compares bare suggestion lines.

## v0.6.0 - 2026-08-30

### Added

- Policy predicates (`bb-agent.policy` + `bb-agent.tool` wiring): `<home>/brain/rules.clj` — approval gates as sci-evaluated code instead of prose. First matching predicate `(fn [tool args])` returns `:allow`/`:deny` and replaces the approval classifier verdict; everything else — disabled, no file, unparseable, throwing, or runaway pred (500ms deref bound on both file eval and pred execution) — fails to baseline, so a broken rules file can make the agent neither more permissive nor more restrictive than configured. Gated by `:policy {:enabled true}` (default off). Sandbox contract pinned by test: sci default context, no io/interop/threads (this sci build ignores `:timeout-ms` — verified empirically — hence the deref futures).

### Verification

- `bb test:e2e:all` passes (106 tests, 479 assertions, 0 failures; prior: 97/455), now 20 suites including `test:e2e:policy` (9/24, two subprocess e2e through the fake provider).
- Zero new dependencies; policy.clj 62 LOC, tool.clj 50 LOC.

## v0.5.0 - 2026-08-30

### Added

- Doctor hardening + last-good config swap (`bb-agent.doctor` + `bb-agent.config`): the doctor now survives a corrupt `config.edn` (it previously crashed with empty output on exactly the condition it exists to diagnose) and reports `:config-parse`, `:home-writable`, `:memory-store`, `:semantic-store`, `:usage-store` alongside the original checks; `bb config apply <file>` validates a candidate against the provider-config rules before an atomic temp+rename swap that keeps the previous config at `config.last-good.edn`; `bb config restore-last-good` recovers. Ported from OpenCrabs' doctor/last-good patterns.
- Provider reachability probe + top-level `bb doctor` (plan-worker delta): the fake provider is probed in-process (offline, deterministic); http base-urls are probed with 1.5s connect / 3s request timeouts and an unreachable endpoint degrades to `[WARN]`, never an error exit.
- Provider attribution stats (`bb-agent.usage`): usage events carry `:fallback/tried` + `:fallback/served`; `bb usage report` gains `:fallback {hits, rate, by-served}` — the honesty fix from v0.4.0 becomes measurable.
- Brain files (`bb-agent.brain`): `<home>/brain/*.md` loaded sorted under filename headers into system context — identity and conventions live as editable markdown; the presence of files is the gate, no config flag. Memory = what happened, brain = who the agent is.

### Verification

- `bb test:e2e:all` passes (97 tests, 455 assertions, 0 failures; prior release: 82/391), now 19 suites including `test:e2e:doctor` (8/38), `test:e2e:stats`, `test:e2e:brain`.
- Zero new dependencies (`babashka.http-client` is built into Babashka); doctor.clj 149 LOC, brain.clj 25 LOC.

## v0.4.0 - 2026-08-29

### Added

- Cron gating for schedules (`bb-agent.schedule` + `bb-agent.cron`): schedules accept a 5-field cron expression and fire only within their `(last-run, now]` catch-up window; runs log `:at` stamps; run clock injectable for DST-safe testing.
- Provider fallback chain as data (`bb-agent.fallback`): ordered second opinions walked until one answers, every departure classified (`bb-agent.error-classifier`) and recorded in `:fallback/tried`, per-step retries and breakers unchanged; turns and usage events record the provider that actually answered (`:fallback/served-by`).
- Curated memory tier (`bb-agent.memory`): entries carry `:memory/kind` (`:raw` default, `:curated` via `bb memory curate <id>`), curated entries rank before raw ones regardless of score; idempotent sqlite migration adds the `kind` column.

### Verification

- `bb test:e2e:all` passes (82 tests, 391 assertions, 0 failures; prior release: 73/360), now 16 suites including `test:e2e:cron-schedule` and `test:e2e:fallback`.

## v0.3.0 - 2026-08-23

### Added

- Cross-session semantic memory (`bb-agent.semantic-memory`), ported from hermes-beam's `semantic_search` + `cross_session_search`: per-session summary records (deterministic transcript summarizer, injectable), BM25/IDF ranking with age decay as the default scorer (injectable `:score-fn`), automatic re-index after each completed turn behind a `:semantic-memory {:enabled true}` config gate, historical-context injection into turn prompts, and `bb memory index-session <id>` / `bb memory semantic-search <query>` CLI commands.

### Verification

- `bb test:e2e:all` passes (73 tests, 360 assertions, 0 failures; prior release: 61/325).
- CI green on `c46fa70` (run 32620312885), first run, no fixes needed.
- Module 179 LOC, zero new dependencies; opt-in via config — no behavior change by default.

## v0.2.0 - 2026-08-23

### Added

- Retry/backoff executor (`bb-agent.retry`): pure opts+thunk loop composing the error classifier and circuit breaker — capped attempts, jittered exponential backoff, clock/sleep/rand injectable. Wired into `core/run-turn!` via `complete-retrying` with a shared per-provider breaker; config `:retry` map overrides knobs. Ported shape from hermes-beam's provider retry loop.
- Pure cron matcher (`bb-agent.cron`) over `java.time` ZonedDateTime: hermes-beam's expression semantics (wildcards, lists, ranges, steps, Sunday 0/7) plus `next-match` / `matches-in-window` for DST-gap, ambiguous-hour, catch-up, and drift-anchoring behavior.
- Subagent ledger (`bb-agent.subagent`): hermes-beam's 529-LOC OTP supervisor flattened to pure record transitions (:pending -> :claimed -> :completed/:failed), id-or-record handles, capacity as data (4-arity claim + `can-claim?`), file-backed shell at `state/subagents.edn`.
- `bb benchmark`: exit-0 offline benchmark harness (4 scenarios x 3 iterations, deterministic fake provider + flaky loopback server), shape ported from AlcaponeCoder's benchmark.clj.
- `bb test:e2e:retry`, `bb test:e2e:cron-edge`, `bb test:e2e:subagent` suites, all wired into `test:e2e:all`.

### Verification

- `bb test:e2e:all` passes (61 tests, 325 assertions, 0 failures).
- `bb benchmark` passes (all 4 scenarios green; retry-absorb ~385ms mean vs ~44ms plain-turn baseline).
- Prior baseline for this branch: 46 tests, 259 assertions.

### Context compaction / resilience (earlier in this branch)

- Context compaction module (`bb-agent.compression`) ported from AlcaponeCoder, adapted to theseus message shapes; zero-dep with an injected summarizer function.
- Circuit breaker as a pure value (`bb-agent.circuit-breaker`) ported from hermes-beam's actor; identical closed/open/half-open semantics with time as an argument.
- Error classifier (`bb-agent.error-classifier`) ported from hermes-beam; pattern tables and precedence preserved as data, plus `retryable?` policy.
- `bb test:e2e:compression` and `bb test:e2e:resilience` suites, wired into `test:e2e:all`.
- GitHub Actions workflow running the full e2e suite on Babashka (adapted from hermes-beam's tests.yml).

## v0.1.0 - 2026-07-14

Initial standalone Theseus release.

### Added

- Babashka CLI agent with deterministic fake provider.
- OpenAI-compatible and Anthropic-compatible HTTP provider adapters.
- Provider tool-call parsing and tool-result continuation loop.
- Safe tool execution for shell, file read/write, search, git status, browser CLI, and document read.
- Explicit approval policies plus `bb agent --ask` interactive approval UX.
- Durable pending approval state and Telegram approval replies.
- Session turn persistence and session metadata for cwd, provider/model, and timestamps.
- `bb session list/current/set-cwd`.
- EDN memory backend plus SQLite memory seam.
- Scheduler and daemon commands.
- Telegram polling adapter with offset and duplicate durability.
- Slack polling adapter.
- Usage event persistence with provider/model/token/cost estimates.
- `bb usage report`.
- Small rich AST with terminal, Telegram, and Slack renderers.
- Config doctor checks.
- Terminal status UI.
- Full e2e test suite via `bb test:e2e:all`.

### Verification

- `bb test:e2e:all` passed before release export.
