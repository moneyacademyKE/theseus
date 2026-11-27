# ADR 0016: OpenCrabs Behavioral Ticks (Wave C)

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Final wave of the 12 OpenCrabs v0.6.0 behavioral ticks from the Rich Hickey Gap Analysis (`docs/adr/0014` Wave A, `docs/adr/0015` Wave B).

## Decisions

1. **Loop-Break → Fallback-Chain Promotion (`bb-agent.loop-break`, wired in `bb-agent.core`):**
   - `describe-pending-calls` renders dropped tool call(s) (≤240 chars) so a loop guard break is never silent about what did not run.
   - `promote-next-fallback` rotates to the next untried provider in `:provider/fallbacks` when a loop guard fires (since a different model rarely reproduces the same loop), falling through to `chain-exhausted-message` once all providers have been tried.
2. **Multi-Shape Stop-Intent Kill Switch (`bb-agent.stop-intent`):**
   - Normalizes punctuation, slash prefixes, and `@bot` mentions; strips trailing address terms (`please`, `eileen`, `bot`); matches single-word stops ONLY as the whole message (never false-cancelling `"stop the docker container"`); matches multi-word interrupts (`"hold on"`, `"wait a sec"`, `"never mind"`) as whole messages or leading clauses.
3. **Queued-Message Folding Context Note (`bb-agent.queued-fold`):**
   - `injected-context` prefixes `not-delivered-note` ONLY when `had-draft?` is true, preventing folded follow-ups from replying `"as above"` to a draft the user never saw.
4. **Advisory Per-Path Write Lock (`bb-agent.path-lock`, wired in `bb-agent.tool.file`):**
   - Places advisory OS locks under `<home>/locks/paths/<sha256-16>.lock` (never littering repos), waits up to 750ms (25ms poll), releases cleanly in `finally`, and degrades gracefully if contended or unavailable.

## Trade-Offs & Verification
- Zero new dependencies; all 4 new modules are pure Babashka under 75 LOC each.
- Verified via `loop-break-test`, `stop-intent-test`, `queued-fold-test`, `path-lock-test`, `bb lint` (`0 errors, 0 warnings`), `bb test-roster` (`never-run: 0`), and full `bb test:bb-agent`.
