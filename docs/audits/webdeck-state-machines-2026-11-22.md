# Web Deck Implicit State-Machine Audit & Rich Hickey Gap Analysis

- **Date:** 2026-11-22
- **Scope:** `web/index.html`, `web/js/*.js` (15 modules), `src/bb_agent/web*.clj` (server contract), `test/web/*.test.mjs`, `docs/webdeck.md`
- **Baseline commit:** `befe040` on `main`
- **Objective:** Inventory every implicit and explicit state machine in the Web Deck, identify live bugs and complected transitions, and execute the 15 highest-leverage decisions that elevate **UX** (User Experience), **DX** (Developer Experience), and **AX** (Agent Experience) simultaneously — with zero breaking changes and zero new dependencies.

---

## 1. Executive Summary (Rich Hickey Lens)

The Web Deck is impressively lean: vanilla ES modules, zero build step, vendored UMD markdown/chart libraries, strict CSP, and a single pure HTTP handler (`bb-agent.web/app`). Yet beneath that clean surface, **only 1 of 24 state machines** (`connState` → `html[data-conn]`) is explicit and observable. The other 23 live as scattered mutable booleans, closure-local variables, uncancelled `setTimeout`s, and ad-hoc DOM attribute mutations.

In Rich Hickey terms, **state, identity, transport, and DOM projection are complected**:
- **UX** suffers from silent races and live bugs: pressing `Enter` while editing the last message bypasses edit mode; voice-note stop throws a `ReferenceError` on an undeclared `r`; PiP displays `61h 1m` for 61 minutes; 1s SSE ticks wipe the `⌘K` deck filter and re-enable in-flight approval buttons; password rotation pops a cleartext `window.prompt()`; refreshing an open goal-detail modal throws `InvalidStateError` on `showModal()`.
- **DX** suffers from duplicated HTTP wrappers (`cockpit.js` and `columns.js` re-implement `post()`/`put()` without `401` handling), untestable closure state, and blind `setTimeout(400)`/`setTimeout(1200)` synchronization in search.
- **AX** suffers from opacity: an autonomous agent operating or verifying the deck cannot reliably determine whether the deck is `owner` or `ro`, whether a transcript is still loading, whether an approval decision is in flight, or whether the composer is in `editing` mode without brittle text-scraping.

---

## 2. Complete Inventory of Implicit & Explicit State Machines (24 Total)

| # | Machine | Location | Current Representation | States (Implicit / Explicit) | Verified Defects / Gaps |
|---|---|---|---|---|---|
| 1 | **Connection / Transport** | `state.js`, `api.js` | `connState` + `html[data-conn]` (only explicit one) | `connecting`, `live`, `live · poll`, `reconnecting`, `offline`, `login required` | `markOk()` sets LED directly; `startPoll()` claims `live · poll` before first poll succeeds and waits 5s; HTTP 401 opens login without setting `conn` to `login required`. |
| 2 | **Auth / Session & Role** | `api.js`, `main.js`, `web_auth.clj` | `localStorage['theseus.token']` + `#login` dialog | `locked`, `authenticating`, `owner`, `ro` | Role (`owner` vs `ro`) is never stored or projected; `:ro` users see enabled write buttons that 403; duplicate `post`/`put` in `cockpit.js`/`columns.js` drop 401 handling. |
| 3 | **SSE / Poll Fallback** | `api.js:74-141` | `es`, `pollTimer`, `errStreak`, `retryMs` | `sse-connecting`, `sse-open`, `sse-backoff`, `polling` | Exponential backoff resets inconsistently; polling interval never fires immediately on fallback. |
| 4 | **Cockpit Composer** | `cockpit.js:354-415` | `input.dataset.editing`, `state.attachments`, `sendBtn.disabled` | `idle`, `editing`, `sending`, `error` | **Live bug:** `Enter` calls `send({})` directly, bypassing `submitFromComposer` and dropping `edit: true`! Attachments cleared before `await post` and lost on error. |
| 5 | **Job Lifecycle** | `cockpit.js:249-330`, `web_turn.clj` | `state.jobId`, `state.busySession`, `#cockpit-job.hidden` | `idle`, `queued`, `running`, `waiting-approval`, `done`, `failed`, `interrupted` | 5s hide `setTimeout` is never cancelled if a new job starts; no `data-job-state` projection. |
| 6 | **Approval Decision** | `cockpit.js:209-247`, `web_turn.clj` | Local `b.disabled = true` on click | `none`, `pending`, `deciding`, `resolved` | **Live bug:** 1s SSE push rebuilds `#cockpit-job` (`innerHTML = ''`) and re-enables buttons while `POST /api/approval` is still in flight. |
| 7 | **Transcript Loader & Paging** | `transcript.js:40-91`, `paging.js` | `page.{session,page,hasMore,loading,lastUserInput}` | `idle`, `loading`, `loaded`, `loading-older`, `empty`, `error` | **Live bugs:** No request sequence/abort guard (rapid session switch races); `loadOlder` (`prepend=true`) overwrites `lastUserInput` with an ancient message; uses `innerHTML = ''`. |
| 8 | **FTS Search & Jump** | `search.js:1-135` | `#cockpit-searchbar.hidden`, `debounceTimer` | `closed`, `open`, `searching`, `results`, `empty`, `error` | **Live bugs:** Hardcoded `setTimeout(400)` + `setTimeout(1200)` to highlight transcript; `closeSearch()` immediately wipes highlights; no stale-query sequence guard. |
| 9 | **Voice Note Recording** | `media.js:52-111` | `rec`, `chunks`, `#cockpit-mic[aria-pressed]` | `idle`, `recording`, `uploading`, `error` | **Live crash:** Line 68 returns `() => { if (r && r.state === 'recording') r.stop(); }` (`r` is undeclared `ReferenceError`!); no `try/catch` in `onstop`; no `uploading` state; unused `get` import. |
| 10 | **Camera / File Upload** | `cockpit.js:336-352`, `media.js:18-49` | `state.attachments[]`, `renderAttachments()` | `idle`, `uploading`, `attached`, `error` | Upload failures only show text, no explicit state; attachments lost on subsequent send failure. |
| 11 | **Goal Detail Modal** | `columns.js:200-257` | `#goal-detail.open`, `setTimeout(..., 800)` | `closed`, `loading`, `open`, `acting`, `error` | **Live crash:** 800ms refresh calls `dialog.showModal()` while dialog is already open (`InvalidStateError`); no stale-fetch guard; timer not cleared on close. |
| 12 | **Picture-in-Picture (PiP)** | `pip.js:1-124` | `pipWin`, `pipTimer`, `documentPictureInPicture` | `unavailable`, `closed`, `open` | **Live bug:** `Math.floor(total / 60) + 'h'` displays `61h 1m` for 61 minutes; scrapes `#api-state.textContent` and `.led.className` from DOM instead of reading `state.js`. |
| 13 | **Deck Filter (`⌘K`)** | `layout.js:58-66` | `input.value`, `el.hidden` on `.card/.row/.t-row` | `inactive`, `filtering` | **Live bug:** Next 1s SSE snapshot re-renders columns and completely wipes the active filter! |
| 14 | **Telemetry Inspector** | `layout.js:65-69` | `#deck-inspector` dialog | `closed`, `open` | **Live bug:** `dblclick` listeners wired once at boot before SSE renders any cards, so dynamic cards never open the inspector. |
| 15 | **Password Rotation** | `main.js:116-127` | `window.prompt()` × 2 + `window.alert()` | `idle`, `prompting`, `rotating`, `done`, `error` | Cleartext password in `prompt()`, synchronous UI thread freeze, un-automatable by agents/a11y. |
| 16 | **Schedule CRUD** | `schedules.js:1-60`, `columns.js:189-198` | `#sched-form`, `#sched-note`, `del` button | `idle`, `creating`, `deleting`, `error` | No pending-state disable on create/delete; `columns.js` delete bypasses `api.js` 401 handling. |
| 17 | **Column Layout / Density / Pin** | `layout.js:1-62` | `localStorage` (`ORDER_KEY`, `LAYOUT_KEY`, `PIN_KEY`, `UI_KEY`, `STREAM_KEY`) | `compact`/`comfortable`, pinned/unpinned, stream `live`/`paused` | `reset` button forgets to clear `UI_KEY`. |
| 18 | **Stream Pause / Resume** | `layout.js`, `main.js:100-108` | `localStorage['theseus.stream']`, `pendingSnap` | `live`, `paused` | Implicitly coupled to `main.js` closure; not observable via `data-*` or inspector. |
| 19 | **Session Selector Sync** | `cockpit.js:103-146`, `paging.js` | `state.session`, `state.sessions`, `reloadTarget()` | `empty`, `selected`, `synced`, `cross-channel-updated` | Clean pure `reloadTarget()`, but coupled to `loadTranscript` without race guard. |
| 20 | **Usage Sparkline Render** | `usage.js:1-76` | `uPlot` instance, `ResizeObserver` | `empty`, `rendered`, `fallback` | Clean, degrades gracefully when `uPlot` absent. |
| 21 | **Markdown Sanitizer Hook** | `md.js:6-56` | `purifyHookInstalled` boolean | `uninitialized`, `ready`, `plain-fallback` | Clean fail-closed fallback to `textContent`. |
| 22 | **Server SSE Broadcaster** | `web.clj:98-222` | `broadcaster` atom, `sse-clients` atom, `fp-sent` | `stopped`, `running`, client `live`/`unauthorized` | Clean; already re-verifies session liveness every pulse. |
| 23 | **Server Turn Lane & Recovery** | `web_turn.clj:30-120` | `jobs` atom, `uploads` atom, `web-lane.edn` | `queued`, `running`, `waiting-approval`, `done`, `failed`, `interrupted` | Clean and durable; `docs/webdeck.md` still falsely claims it is in-memory! |
| 24 | **Server Auth & Rate Limiter** | `web_auth.clj` | `sessions` atom, `web-sessions.edn`, mtime watchers | `unset`, `active`, `rate-limited`, `rotated` | Clean and durable; `docs/webdeck.md` still falsely claims sessions are in-memory! |

---

## 3. Feature-Set Comparison & Complexity vs. Utility Matrix

### 3.1 Architecture Comparison: Ad-Hoc Booleans vs. Tiny Pure FSM Kernel vs. Heavy State Library

| Dimension | Current (Ad-Hoc Booleans / Closures) | **Recommended: Zero-Dep `web/js/fsm.js` (~90 LOC)** | Heavy Library (XState / Redux) |
|---|---|---|---|
| **New Dependencies** | 0 | **0** (honors `AGENTS.md` Rule 19) | 1+ (violates Rule 19 + CSP/no-build) |
| **Transition Validation** | None — illegal states happen silently | **Explicit transition table**; illegal moves rejected & logged | Full statecharts |
| **AX Observability** | 1/24 (`data-conn`) | **All core machines projected to `data-*` + `window.__THESEUS_DECK__`** | DevTools extension required |
| **Node Unit Testability** | Poor (coupled to DOM closures) | **High** (`node --test test/web/fsm.test.mjs`) | High, with bundler overhead |
| **Runtime Footprint** | 0 KB | **~2.5 KB raw ES module** | 30–100+ KB |
| **Cognitive Load** | High (hidden timers, implicit flags) | **Low** (one tiny pattern, plain JS objects) | Medium-High (DSL boilerplate) |

### 3.2 Complexity vs. Utility Matrix for the 15 Decisions

| ID | Decision | UX | DX | AX | Complexity | Risk | Priority |
|---|---|---|---|---|---|---|---|
| **D1** | Pure FSM Kernel (`web/js/fsm.js`) + `window.__THESEUS_DECK__` | Medium | **High** | **Very High** | Low | Low | P0 |
| **D2** | Unified Auth/Role Machine + Eliminate Duplicate `post`/`put` | **Very High** | **Very High** | **Very High** | Low | Low | P0 |
| **D3** | Honest Transport/Poll Machine (`api.js`) | **High** | High | **High** | Low | Low | P0 |
| **D4** | Cockpit Composer/Edit Machine (Fix `Enter` bug + attachment retention) | **Very High** | High | **High** | Low | Low | P0 |
| **D5** | Job & Approval-Decision Machine (Fix SSE double-click race + timer leak) | **Very High** | High | **High** | Low | Low | P0 |
| **D6** | Monotonic Transcript Loader & Paging Machine (Fix race + `lastUserInput` bug) | **Very High** | **High** | **High** | Low | Low | P0 |
| **D7** | Deterministic Search Machine (Event-driven highlight + seq guard) | **High** | **High** | **High** | Low | Low | P0 |
| **D8** | Fix `media.js` `ReferenceError` + Capture/Upload Machine | **Very High** | High | Medium | Low | Low | P0 |
| **D9** | Fix Goal-Detail `showModal()` Crash + Stale Fetch Guard | **Very High** | High | **High** | Low | Low | P0 |
| **D10** | Fix PiP Hours Math (`3600` not `60`) + Remove DOM Scraping | **High** | High | Medium | Low | Low | P0 |
| **D11** | Durable `⌘K` Filter + Delegated Inspector + Full Layout Reset | **Very High** | High | **High** | Low | Low | P0 |
| **D12** | Non-Blocking Password Rotation `<dialog>` (Kill `prompt()`/`alert()`) | **Very High** | Medium | **Very High** | Low | Low | P1 |
| **D13** | Explicit Schedule CRUD Machine + Unified 401 Handling | High | High | High | Low | Low | P1 |
| **D14** | Node Unit Test Suite (`test/web/fsm.test.mjs`) + Shell Pins | Medium | **Very High** | **High** | Low | Low | P0 |
| **D15** | Refresh `docs/webdeck.md` + AX Contract + ADR 0006 | Medium | **High** | **Very High** | Low | Low | P1 |

---

## 4. Tradeoffs & Non-Negotiable Invariants

- **Zero new dependencies** (`AGENTS.md` Rule 19): `web/js/fsm.js` is pure ES2022 with zero external imports.
- **Static asset whitelist** (`src/bb_agent/web.clj`): `/js/fsm.js` MUST be registered in `static-files` so `every-disk-module-is-served-test` and `bb scripts/check_web_graph.bb` stay green.
- **No `innerHTML` regressions**: `test/web/shell.test.mjs` pins zero `.innerHTML` in `layout.js`; we also replace `innerHTML = ''` with `replaceChildren()` in `transcript.js` and `cockpit.js`.
- **Backward compatibility**: Every existing DOM ID, CSS hook (`html[data-conn]`), API endpoint, and `shell.test.mjs` contract pin is preserved verbatim.
- **Tradeoff accepted**: `createMachine` logs illegal transitions to `console.warn` and records them in `history()` rather than throwing, so an unexpected server payload never bricks the live cockpit.

---

## 5. Actionable Implementation Plan (Red/Green TDD)

1. **Wave 1 (Red → Green Core):** Write `test/web/fsm.test.mjs` first (failing), then implement `web/js/fsm.js` + whitelist `/js/fsm.js` in `src/bb_agent/web.clj` + wire `state.js` & `api.js` (D1, D2, D3, D14).
2. **Wave 2 (Cockpit, Transcript, Search, Media):** Implement D4, D5, D6, D7, D8 across `cockpit.js`, `transcript.js`, `search.js`, `media.js`.
3. **Wave 3 (Columns, PiP, Layout, Schedules, Rotate Dialog):** Implement D9, D10, D11, D12, D13 across `columns.js`, `pip.js`, `layout.js`, `schedules.js`, `main.js`, `web/index.html`.
4. **Wave 4 (Verify & Certify):** Run `bb scripts/check_web_graph.bb`, `node --test test/web/*.test.mjs`, `bb test:e2e:web`, `bb lint`, `bb test-roster`, `bb verify:rewrite-docs`, and full `bb test`. Update `docs/webdeck.md`, `CHANGELOG.md`, `docs/GAP_ANALYSIS.md`, `docs/INDEX.md`, and ADR `0006`.
