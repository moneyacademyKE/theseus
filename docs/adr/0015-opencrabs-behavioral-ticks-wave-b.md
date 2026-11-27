# ADR 0015: OpenCrabs Behavioral Ticks (Wave B)

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Second wave of pure-data behavioral ticks from the OpenCrabs v0.6.0 Rich Hickey Gap Analysis (`docs/adr/0014` shipped Wave A).

## Decisions

1. **Harness-Driven Brain-File Hints on Tool Miss (`bb-agent.brain-hints`, wired in `bb-agent.tool-hints`):**
   - On `:status :error` tool results, scores `<home>/brain/knowledge/*.md` (excluding `.bak*` snapshots) against non-stopword query tokens and attaches up to 2 compact snippets (≤500 chars each, ≤900 chars total) as `:brain/hints`. Activates all 32 knowledge pages at failure time with zero extra round-trips.
2. **Tail-Anchored Mid-Sentence EOS Continuation + Usage-Gap Corroboration (`bb-agent.truncation`):**
   - `looks-truncated-mid-sentence` detects premature EOS cutoffs (dangling clause, mid-word, unclosed code fence).
   - `usage-gap?` corroborates against billed non-reasoning output tokens (`visible_chars < (output - reasoning) * 2`).
   - `continuation-nudge` embeds the verbatim last 120 chars between `---` delimiters and explicitly forbids lone markup closure; `degenerate-continuation?` rejects blank, punctuation-only, or tail-echo continuations (capped at 2 attempts).
3. **Pre-Compaction Context-Pressure Nudge Band (`bb-agent.truncation/context-pressure-nudge`, wired in `bb-agent.core`):**
   - Injects a transient `:system` behavioral nudge when live context usage sits in the 55–64% band of `live-budget`, telling the model to finish its current sub-task and persist critical state to disk before compaction clips older turns. Never persisted to conversation history; omits noisy percentage numbers.
4. **Gaslighting-Preamble Stripping (`bb-agent.gaslighting`, wired in `bb-agent.core`):**
   - Strips leading refusal/broken-tool preamble paragraphs (*"tools aren't responding"*, *"isn't registered"*, *"upload to imgur"*) when a turn actually executed at least one successful tool call, preserving the genuine answer underneath.

## Trade-Offs & Verification
- Zero new dependencies; all 3 new modules are pure Babashka under 115 LOC each.
- Verified via `brain-hints-test`, `truncation-test`, `gaslighting-test`, `pressure-nudge-test`, `bb lint` (`0 errors, 0 warnings`), `bb test-roster` (`never-run: 0`), and full `bb test:bb-agent`.
