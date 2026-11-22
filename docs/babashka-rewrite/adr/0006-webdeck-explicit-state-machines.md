# ADR 0006 — Web Deck Explicit State Machines & UX/DX/AX Hardening

- **Date:** 2026-11-22
- **Status:** Accepted
- **Audit report:** `docs/audits/webdeck-state-machines-2026-11-22.md`

## Context

The Web Deck (`web/index.html`, `web/js/*.js`) had 24 state machines across its interactive cockpit, SSE transport, FTS search, media capture, goal-detail drawer, PiP window, layout toolbar, and schedule CRUD — yet only one (`connState` → `html[data-conn]`) was explicit. The remaining 23 lived as scattered booleans, unguarded async fetches, uncancelled `setTimeout`s, and duplicated HTTP helpers. This caused live UX bugs (`Enter` bypassing edit-last mode, `media.js` `ReferenceError` on stop, `columns.js` `card()` missing `return c`, PiP showing `61h 1m` for 61 minutes, 1s SSE ticks wiping the `⌘K` filter and re-enabling in-flight approval buttons, `showModal()` crashing on already-open dialogs, cleartext `prompt()` for password rotation), DX friction, and AX opacity.

## Decision

1. **Introduce a zero-dependency pure FSM kernel (`web/js/fsm.js`)** whitelisted in `src/bb_agent/web.clj` providing `createMachine`, `createGate` (monotonic async generation guard), `registerMachine`, `projectAttr`, and a frozen read-only `window.__THESEUS_DECK__` introspection surface (`snapshot()`, `history()`, `machines()`).
2. **Make all 14 client state machines explicit and DOM-projected (`data-*`)** while fixing every verified bug across `state.js`, `api.js`, `cockpit.js`, `transcript.js`, `search.js`, `media.js`, `columns.js`, `pip.js`, `layout.js`, `schedules.js`, `main.js`, and `web/index.html`.
3. **Unify HTTP auth/401 handling** by removing duplicate private `post`/`put` helpers in `cockpit.js` and `columns.js`, syncing every 401 to `auth=locked` + `conn=login required`, and projecting `role` (`owner` | `ro`) onto `html[data-role]` so read-only viewers see disabled write controls instead of surprise 403s.
4. **Pin invariants in Node unit tests** (`test/web/fsm.test.mjs` + `test/web/shell.test.mjs`) with zero new dependencies.

## Consequences

- **Positive:** Eliminates 12+ live UI bugs and race conditions; agents and developers can inspect all 14 state machines and their 60-step transition history via `window.__THESEUS_DECK__` and `data-*` attributes without DOM scraping; zero external dependencies added; 100% backward compatible with existing routes and DOM IDs.
- **Tradeoff:** `createMachine` records and rejects illegal transitions without throwing, preferring graceful degradation over hard crashes on unexpected server payloads.
