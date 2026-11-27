# ADR 0013 — Enforced REPL-Driven Development (`bb-agent.repl`, `:repl-gate` & Pre-Write Parse Check)

- **Date:** 2026-11-27
- **Status:** Accepted
- **Gap Analysis:** [`docs/audits/repl-driven-development-gap-analysis-2026-11-27.md`](../../audits/repl-driven-development-gap-analysis-2026-11-27.md)

## Context

Previously, Theseus had no first-class REPL tool and no mechanical enforcement of REPL-driven development — an agent could blindly edit `.clj`/`.cljc`/`.cljs`/`.bb` files without evaluating hypotheses or checking paren balance before writing to disk.

## Decision

Introduce a zero-dependency, mechanically enforced REPL-driven development workflow in `src/bb_agent/repl.clj`:
1. **First-class `repl_eval` tool (`tools.clj`, `tool.clj`, `repl.clj`):**
   - Auto-discovers `.nrepl-port` in `cwd` and evaluates over Babashka's built-in `bencode.core` TCP client when a live nREPL server is running.
   - Seamlessly falls back to bounded `bb -e` subprocess execution (default `10s`, max `30s`, `16KB` output cap) when no nREPL server is active.
2. **Mechanical Gate #1 — `:repl-gate` (`repl.clj`, `tool.clj`, `core.clj`, `doctor.clj`):**
   - Active by default in `core/run-turn!` (`:repl/enforce true`, opt-out via `:repl/enforce false` in `config.edn`, registered in `doctor.clj`).
   - Denies `write_file` / `edit_file` targeting `.clj`/`.cljc`/`.cljs`/`.bb` with `:denial/source :repl-gate` until at least one `repl_eval` call succeeds in the current turn (`:repl/evals` atom).
3. **Mechanical Gate #2 — Pre-Write Syntax/Paren Check (`tool/file.clj`):**
   - Every non-append `write_file` and every `edit_file` targeting a Clojure source file is validated with Babashka's built-in `edamame.core/parse-string-all` before touching disk; unbalanced parens or reader syntax errors are refused cleanly.
4. **CLI & Tasks (`bb.edn`, `cli.clj`):**
   - Added `bb repl:server [port]`, `bb repl:eval '<expr>'`, and `theseus repl '<expr>'`.

## Consequences

- REPL-driven development is enforced mechanically rather than by prompt prose alone, paren-count drift is caught before disk writes, and zero new dependencies or mandatory daemons are introduced.
