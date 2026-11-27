# ADR 0014: OpenCrabs Behavioral Ticks (Wave A)

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Direct source-level Rich Hickey Gap Analysis against OpenCrabs v0.6.0 (`~/.opencrabs/src/src/brain/`) identified 12 high-leverage runtime behavioral ticks not yet present in Theseus. Wave A ports the 5 highest-ROI, pure-data ticks with zero new dependencies.

## Decisions

1. **Conservative Tool-Name Self-Heal (`bb-agent.tool-name-heal`, wired in `bb-agent.tool`):**
   - Resolves near-miss tool names (`repl-eval`, `tg_send_file_now`, `read_flie`) across 3 strictly conservative unique-match stages: (1) normalized exact, (2) abbreviation-expanded token subset (≥2 tokens, unique most-specific), (3) Damerau-Levenshtein ≤ `clamp(len/6, 1, 2)`. Ambiguous matches return `nil`. Stamps `:tool/healed-from`.
2. **Mechanism-Based Phantom Nudges (`bb-agent.turn-integrity`):**
   - Upgraded `execute-guard-nudge-message` and added `uncalled-commands-nudge`, `unbacked-facts-nudge`, and `no-tool-calls-nudge` (`:local-model?` avoids `"STOP"`). Every variant states the mechanism (*"nothing runs inside your reasoning"*) and carries an explicit finished-work escape hatch so completed turns never loop.
3. **Early Tool-Repeat Nudge & Poisoned-History Pruner (`bb-agent.tool-repeat`, wired in `bb-agent.core`):**
   - `create-tracker` / `observe!` / `reset!` catches 4 consecutive identical `(tool, canonical-args)` calls before provider HTTP 500 loop guardrails fire.
   - `prune-repetitive-tool-calls` collapses consecutive duplicate pure-tool rounds (`tool_use` + paired `tool_result`) when a provider repetitive-tool 500 error still occurs, retrying once with healed history.
4. **Session-Scoped Fully-Read Path Guard (`bb-agent.tool.file`, wired in `bb-agent.tool`):**
   - Tracks `(session-id, canonical-path)` on full `read_file` reads. Non-append `write_file` overwrites on existing files are refused (`:executed? false`) unless the file was read in full in that session. New files, `append? true`, and `edit_file` remain unrestricted.
5. **Recoverable vs. Defect Shell Failure Classifier (`bb-agent.bash-failure`, wired in `bb-agent.tool`, `bb-agent.usage`, `bb-agent.rsi`):**
   - Scans `STDERR:` only (when present) to classify shell failures into `:model-error` (precedence) vs `:environmental` vs `:unknown`. `tool-event!` records `:tool/recoverable?` and `rsi/tools-aggregate` keeps recoverable environmental/stale-anchor misses out of `:fail` (reporting them separately under `:recoverable`).

## Trade-Offs & Verification
- Zero new dependencies; all new modules are pure Babashka data/functions under 125 LOC each.
- Verified via dedicated unit tests (`tool-name-heal-test`, `turn-integrity-test`, `tool-repeat-test`, `read-state-test`, `bash-failure-test`), `bb test-roster` (`never-run: 0`), `bb lint` (`0 errors, 0 warnings`), and `bb test:bb-agent`.
