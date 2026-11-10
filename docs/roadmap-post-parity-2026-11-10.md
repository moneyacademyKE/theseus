# Theseus post-parity roadmap — 2026-11-10

## Context

**Where we stand.** The webdeck↔Telegram parity loop is closed: bead `bk-3eeb`, 12/12 plan tasks,
witness at `docs/webdeck-parity-witness.edn`, e2e web suite 29 tests / 306 assertions green,
full composite gates exit 0. The stuck-at-connecting incident is fixed, and the BrowserOS-neo
dogfood pass found and fixed four real defects (login contrast, session-picker overflow, false
`gone` job status, SSE edge transform opt-out). HEAD `f546514`, pushed. Quick tunnel verified
answering 200 through the edge at 18:16 EAT today.

**Problem.** Two parity rows stay PARTIAL, and both are gated on owner actions, not on code:

1. **Stable hosting** — the deck rides a quick tunnel whose URL rotates on restart and whose
   edge buffers the live SSE stream from its first byte (issue #36), so the deck falls back to
   5-second polling. The fix is written (response-buffering rule on a zone we control) but
   `trycloudflare.com` gives us no zone to control.
2. **Vision** — `zai/glm-4.6v` is disabled at the gateway account level, so image turns fail
   at the provider. No amount of deck code fixes a dashboard toggle.

Also open on the repo: issue #16 (OpenAI tool continuations, August, Telegram lane — separate
session's domain, named here for honesty, not planned here).

**Target state.** A stable URL on your domain with true live push, vision working end to end,
secrets that transited chat rotated, and a standing quality loop so the seams the one-shot
dogfood exposed stay watched.

**Intent.** ~10 minutes of owner time where it unlocks the most; I build the rest through
Bankai beads with the usual gates, same fix-through rules as always.

## Owner unlocks (in leverage order)

1. **Stable URL + live push.** Pick the hostname (e.g. `theseus.<your-domain>`) and run
   `cloudflared tunnel login` once on the Mac — or tell me to launch it and approve the auth
   page from any browser logged into your Cloudflare account. Everything after that is mine
   (see step 1 below). Kills URL rotation forever and closes issue #36.
2. **Vision.** Re-enable `zai/glm-4.6v` in the inferhub dashboard. I probe it the same hour,
   flip both PARTIAL rows to DONE, and re-issue the witness.
3. **Secret hygiene.** The master password, the gateway API key, and the bot token have all
   transited this chat. Rotation is cheap insurance: `bb scripts/set_web_password.bb` for the
   deck (old sessions die instantly), key/token rotation at your convenience.

## Implementation steps (mine, after unlocks)

1. **Named tunnel build-out** (bead; depends on unlock 1): create tunnel `theseus`,
   `cloudflared tunnel route dns`, config + `launchd` service `com.theseus.tunnel`, retire the
   quick tunnel from the web service's companion, add the zone response-buffering rule via
   Cloudflare API, then verify SSE first-frame latency through the edge in BrowserOS neo.
   Close #36 with receipts, update README + witness.
2. **Vision closure** (bead; depends on unlock 2): image→text round-trip probe against the
   live gateway, parity matrix rows PARTIAL→DONE, witness re-issued, CHANGELOG entry.
3. **Standing dogfood pass** (independent; proposal — new cron job, your call): weekly
   BrowserOS-neo sweep of the live deck — login, compose, one real turn, goals column,
   mobile-width layout probe. Defects get filed as issues and fixed same-turn under
   fix-through. Reports to topic 4721 only when it finds something; silent on green.
4. **Universe scoping** (independent; one-line change, your call): the Binance refill maps
   crypto symbols onto real tickers (BN → Brookfield). Rescope to the tech theme to kill the
   collisions, or keep them as the documented cost of the wider universe.
5. **Housekeeping** (independent): one mobile-width pass of the deck columns, witness drift
   check against the live route table, README staleness sweep.

## What I am deliberately NOT planning

- **No new deck features** until the two PARTIAL rows close honestly — the parity matrix is
  the contract, and it says two rows are owed, not zero.
- **No re-architecture.** Every step above reuses tunnels, routes, and bridges that already
  exist; the deck's engine-sharing design stays untouched.
- **Other lanes' beads** (alakey, gleamunison, infered, bankai CLI, the Telegram senses
  roadmap `bk-1c52`) belong to their own sessions and stay out of this roadmap.

## Recommendation

Unlock 1 first — it is the only row where the fix is already written and waiting on your
domain. Unlock 2 whenever you are in the dashboard. Secret rotation this week. Step 3 (the
standing dogfood) only on your yes — it is a new cron job on your instance.
