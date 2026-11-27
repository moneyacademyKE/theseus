# Rich Hickey Gap Analysis — Enforcing REPL-Driven Development (RDD) in Theseus

- **Date:** 2026-11-27
- **Target System:** Theseus `v1.5.1` (`a57b0d5`)
- **Benchmark Bar:** OpenCrabs / Babashka native nREPL + SCI workflow (2025–2026)

---

## 1. Feature-Set Differences Matrix

| Dimension | Current Theseus (`a57b0d5`) | Option A: Prompt-Only RDD Rules | Option B: External nREPL-Only MCP Daemon | **Option C: Native `bb-agent.repl` + Mechanical Gate + Auto-nREPL (Recommended)** |
|---|---|---|---|---|
| **Live Form Evaluation** | Only via `shell` (`bb -e ...`), no structured REPL tool | Same (`shell` prose workaround) | Requires external running MCP + nREPL daemon | **First-class `repl_eval` tool**: connects to `.nrepl-port` over built-in `bencode.core` if running, falls back seamlessly to `bb -e` (zero daemon required) |
| **Enforcement Mechanism** | None — agent can blindly edit `.clj` files without ever evaluating | Prose only (easily ignored under context pressure) | External MCP cannot mechanically gate `write_file`/`edit_file` | **Two mechanical gates:** (1) `:repl-gate` blocks `.clj`/`.cljc`/`.cljs`/`.bb` edits until `repl_eval` succeeds in the turn; (2) `edamame.core` syntax/paren check refuses broken Clojure before touching disk |
| **Paren-Drift Prevention** | Manual (`AGENTS.md` Rule 13) | Manual | Manual | **Automatic pre-write parse check** in `tool/file.clj` (`write_file` & `edit_file`) using built-in `edamame.core/parse-string-all` |
| **Startup & Packaging** | Standalone `theseus` binary, zero extra deps | Zero deps | Extra MCP server + nREPL daemon | **Zero new dependencies** (`bencode.core` + `edamame.core` are built into Babashka), packs directly into `theseus` binary |
| **Escape Hatch / Config** | N/A | N/A | Stop MCP | `:repl/enforce` in `config.edn` (default `true` in `core/run-turn!`, opt-out `false`, registered in `doctor.clj`) |

---

## 2. Benefits vs. Tradeoffs

| Option | Core Benefits | Core Tradeoffs |
|---|---|---|
| **Option A (Prompt-only)** | 0 code. | Zero enforcement; models revert to blind edit-then-crash under pressure. |
| **Option B (External nREPL MCP)** | Persistent JVM/bb state when daemon is up. | Breaks standalone zero-daemon packaging; cannot mechanically gate `write_file`/`edit_file` inside `tool.clj`. |
| **Option C (Native `bb-agent.repl` + Mechanical Gates)** | True mechanical enforcement (eval-before-edit + parse-before-land); auto-uses live `.nrepl-port` over built-in `bencode.core` when available and degrades cleanly to `bb -e` when not; zero new deps. | Adds one turn-scoped gate (`:repl-gate`) that requires a fast `repl_eval` round before the first `.clj` edit in a turn (easily opt-out via `:repl/enforce false` if ever needed). |

---

## 3. Complexity vs. Utility Matrix (Weighted)

Weights: **RDD Enforcement Strength (35%)**, **Paren/Syntax Safety (25%)**, **Operational Simplicity / Zero-Daemon (25%)**, **Implementation Speed (15%)**.

| Option | Enforcement (35%) | Syntax Safety (25%) | Ops Simplicity (25%) | Speed (15%) | **Weighted Score (/10)** |
|---|---:|---:|---:|---:|---:|
| **Option C: Native `bb-agent.repl` + Mechanical Gates** | **9.6** | **9.8** | **9.5** | **9.0** | **9.54** |
| **Option B: External nREPL MCP** | 5.5 | 4.0 | 5.0 | 6.0 | **5.08** |
| **Option A: Prompt-Only** | 2.0 | 2.0 | 10.0 | 10.0 | **5.20** |

---

## 4. Actionable Recommendation (Rich Hickey Path)

Implement **Option C (`9.54 / 10`)** via Red/Green TDD:
1. Create `src/bb_agent/repl.clj`:
   - `clojure-file?` (`.clj`, `.cljc`, `.cljs`, `.bb`), `parse-check-clojure` (built-in `edamame.core/parse-string-all` catching unbalanced parens/syntax errors before disk write).
   - `nrepl-eval` (built-in `bencode.core` TCP client talking to `.nrepl-port` when present) + `bb-eval` (bounded `10s` `bb -e` subprocess fallback), unified in `eval-code!`.
   - `repl-gate-blocked?` (checks `(:repl/enforce cfg)`, mutating tool `write_file`/`edit_file`, Clojure target path, and zero prior successful `repl_eval` calls in `(:repl/evals cfg)` atom).
   - `handlers` (`{"repl_eval" run-repl-tool!}`).
2. Wire into:
   - `src/bb_agent/tools.clj`: advertise `repl_eval`.
   - `src/bb_agent/tool.clj`: merge `repl/handlers`, enforce `:repl-gate` before `write_file`/`edit_file` on Clojure files, and increment `(:repl/evals cfg)` on successful `repl_eval`.
   - `src/bb_agent/tool/file.clj`: run `repl/parse-check-clojure` in both `write_file` (non-append) and `edit_file` before writing any `.clj`/`.cljc`/`.cljs`/`.bb` file.
   - `src/bb_agent/core.clj`: initialize `:repl/evals (atom 0)` and default `:repl/enforce true` (unless explicitly `false` in `cfg`) per turn.
   - `src/bb_agent/doctor.clj`: add `:repl/enforce` to `known-config-keys`.
   - `bb.edn` & `src/bb_agent/cli.clj`: add `bb repl:server`, `bb repl:eval`, and `theseus repl <expr>`.
