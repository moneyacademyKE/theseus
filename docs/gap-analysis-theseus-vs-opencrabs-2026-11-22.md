# Theseus vs OpenCrabs v0.6.0 — Deep Architectural Gap Analysis (Workflows, Tools & LLM Build Mechanics)

- **Date:** 2026-11-22
- **Theseus ground truth:** `main` @ `9e88d99` (`/Users/moe/theseus/theseus`, Babashka Clojure, **120 files / 22,137 prod LOC**)
- **OpenCrabs ground truth:** `org/main` @ `8666ca2d9` (`v0.6.0`, 2026-11-22, `/Users/moe/.opencrabs/src`, Rust, **990 prod files / 425,906 prod LOC**; 2,324 total files / 684,523 LOC incl. tests)
- **Scope:** Not a feature/channel checklist. This analysis compares **how** both systems actually run LLM turn loops, model/gate/execute tools, and orchestrate multi-step workflows to build software with LLMs.

---

## 0. Executive Summary (Rich Hickey Lens)

OpenCrabs `v0.6.0` is **19.2× larger** in production LOC than Theseus (`425,906` vs `22,137`). That ratio is not bloat alone — it reflects two fundamentally different engineering bets on how to control an unreliable LLM:

- **OpenCrabs (`v0.6.0`) bets on a hardened interactive harness:** async token streaming with 20s idle + live repetition cutoffs, surgical file editing (`edit_file` + `hashline_edit` + line-ranged `read_file`), parallel read/disjoint-write tool batching, mechanical `plan_gate` (forces a plan after 12 tool calls) + `skill_gate` (glob-triggered just-in-time `SKILL.md` injection), **Ralph Loop** shell-verified plan execution, and real child subagents running in isolated **git worktrees**. The cost is immense surface complexity (`tool_loop.rs` alone is `8,650 LOC`, `plan_tool.rs` is `4,161 LOC`, `phantom.rs` is `2,379 LOC`).
- **Theseus (`9e88d99`) bets on small, pure data seams + a cybernetic build loop:** a `647 LOC` synchronous batch turn loop, zero-dep security/taint/exfil gating (`security.clj` — which OpenCrabs `v0.6.0` does **not** have), persistent file-based rolling compaction with `[Turns A-B]` verbatim disk drill-down, and a **far stronger autonomous build engine (`goal/`, `3,124 LOC`)** using deterministic disk observers, integrity-before-goal predicates, automatic `git tag` checkpoint + `git reset --hard` rollback, and a data-driven escalation ladder `[:rollback :reseed :reframe :escalate-model]`.

**The headline asymmetry:**
1. **Interactive coding & editing in an existing repo:** OpenCrabs is **decisively ahead** because Theseus lacks targeted file editing (`write_file` is whole-file overwrite/append only), line-ranged `read_file`, regex/rg `search`, parallel safe-tool batching, streaming/idle cutoffs, and live running subagents (`subagent.clj` is a `108 LOC` uncalled ledger).
2. **Unattended, verifiable autonomous builds:** Theseus's `goal/` cybernetic loop is **architecturally cleaner and more trustworthy** than OpenCrabs's `687 LOC` LLM-judge `src/brain/goal/` — deterministic observers + git rollback beat an LLM grading its own homework.
3. **Security & prompt-injection boundary:** Theseus's `security.clj` + `redact.clj` taint/exfil/spotlight pipeline is **ahead** of OpenCrabs `v0.6.0` (`org/main` has `confidential.rs` path denylist + output redaction, but no per-turn taint/provenance/exfil tracker).

---

## 1. Subsystem Scale Comparison (Measured LOC)

| Subsystem | Theseus (`9e88d99`) | OpenCrabs `v0.6.0` (`8666ca2d9`) | Ratio | Structural Takeaway |
|---|---:|---:|---:|---|
| **Total Production Code** | **22,137** (120 files) | **425,906** (990 files) | 19.2× | Theseus fits in one human head; OpenCrabs is a multi-surface platform (TUI+GUI+5 channels) |
| **LLM Turn Loop + Resilience** | **1,871** (`core.clj` 647) | **29,359** (`tool_loop.rs` 8,650) | 15.7× | Streaming + multilingual phantom guards + background compaction drive OpenCrabs size |
| **Tools + Gating + Editing** | **2,792** (19 files) | **56,608** (153 files) | 20.3× | OpenCrabs ships surgical editors, plan/skill gates, worktree subagents, 75+ tools |
| **Provider Layer** | **324** (1 file) | **22,391** (39 files) | 69.1× | OpenCrabs wraps streaming SDKs + CLI providers (`claude_cli`, `codex_cli`, `opencode_cli`) |
| **Goal / Build Runner** | **3,124** (21 files) | **687** (6 files) + **5,837** (Plan/Ralph) | ~2.1× | Theseus puts build rigor in `goal/` (disk observers + git rollback); OpenCrabs puts it in `plan_tool.rs` (Ralph Loop) |
| **Memory & Compaction** | **~1,100** (`compression`+`memory`+`beliefs`) | **~7,042** (`src/memory/` + `compaction` + `epistemic`) | 6.4× | Files-as-truth + derived FTS vs SQLite truth + vectors + symbol index |
| **RSI / Self-Improvement** | **1,104** (`rsi.clj` + `rsi_learn.clj`) | **7,171** (`rsi*.rs` + `self_improve.rs`) | 6.5× | Propose-only ledger vs background backoff engine with typed proposal queues |

---

## 2. Deep Dive 1: LLM Turn Loop & Execution Mechanics

| Mechanism | Theseus (`core.clj`, `provider.clj`, `compression.clj`, `turn_integrity.clj`) | OpenCrabs `v0.6.0` (`tool_loop.rs`, `helpers.rs`, `compaction.rs`, `phantom.rs`) | Winner & Why |
|---|---|---|---|
| **Transport & Latency** | Synchronous batch HTTP (`60s` timeout). User sees nothing until full JSON completes. | Async **streaming** (`stream_complete`), live `ProgressEvent` chunks, `90s` handshake + **20s inter-chunk idle timeout**. | **OpenCrabs** — fast stall detection + real-time UX. |
| **Tool Dispatch** | Strictly **sequential** (`reduce` over `tool-requests`). | **Parallel bounded batching** (`parallel_tools.rs`) when `len >= 2`, all auto-approved, and disjoint `write_target` paths; serializes same-path writes + advisory `path_lock.rs` (750ms). | **OpenCrabs** — multi-file reads/searches run concurrently without racing edits. |
| **In-Turn Read Cache** | **Yes** (`cacheable-read-tools` in `core.clj`), wiped on any mutating tool. | **No** general read-result cache (tracks `read_state::mark_fully_read` for edit safety instead). | **Theseus** — eliminates redundant repeat reads in one turn. |
| **Mid-Turn User Steering** | None — new user message waits in session queue until turn finishes. | **`queued_fold.rs`**: folds mid-turn user messages with `NOT_DELIVERED_NOTE` so model answers both in one unified reply; `CancellationToken` + `restart_recovery.rs`. | **OpenCrabs** — natural interruption and crash-resume. |
| **Context Compaction** | **Synchronous** before turn (`compress-session-turns!`), rolling file checkpoints (`state/session-compaction/<id>.edn`), 3 tiers `[Turns A-B]`, structured EDN ledger, **verbatim disk drill-down** via `read_session_turns`, + live mid-turn 1-line tool-result pruning. | **Background async** at **65%** pressure with **80% backpressure ceiling** (`compaction.rs`), 3-scope incremental DB markers (`FullWindow` / `DeltaSinceMarker` / `SegmentConsolidation`), structured `ContextManifest`, skill-epoch stamping, `PlanRecovery`, and 20% output-reserve cap (`request_budget.rs`). | **Draw (complementary)** — OpenCrabs's async 65%/80% background pipeline never stalls a turn; Theseus's `[Turns A-B]` + `read_session_turns` gives lossless drill-down. |
| **Token Accounting** | Local `4 chars/token` heuristic + provider usage fields. | **`evaluate_token_report`** calibrates against lying providers (rejects Zhipu +20k bug, `<100` floor, `<0.2×` drops). | **OpenCrabs** — immune to buggy provider telemetry. |
| **Prompt Caching** | Anthropic `cache_control` on `:system` + last 2 messages (not `:tools`). | Anthropic `cache_control` on static brain prefix + **last tool schema**; volatile runtime block placed after cache boundary. | **OpenCrabs** — caching tool schemas saves thousands of tokens on large registries. |
| **Loop & Repetition Guards** | Inline `seen-calls` (nudge at 2, halt at 3) + 1-shot truncation nudge + regex execute-narration guard + 2-shot phantom tool-text guard + silent-turn factual receipt synthesizer. | Live **streaming repetition detector** (`REPEAT_WINDOW=2048`, `REASONING_REPEAT_WINDOW=8192`), `ToolRepeatTracker` (nudge at 4), `prune_repetitive_tool_calls`, **multilingual `phantom.rs`** (8 languages, 2,379 LOC), `fenced_command.rs`, `gaslighting.rs`, `bare_tool_call_extractor.rs`, `json_repair.rs`, 2-attempt anchored truncation stitching (`TAIL_ANCHOR_CHARS=120`). | **OpenCrabs** — battle-tested against every real-world LLM failure mode (though at high LOC cost). |
| **Stall Watchdog** | Daemon thread emits advisory `:turn/still-working` (180s) / `:turn/stalled` (480s) — **cannot cancel hung HTTP**. | `stream_idle_timeout` (**20s**) actually aborts dead streams via `CancellationToken` and triggers cooldown/fallback. | **OpenCrabs** — active cancellation beats advisory logging. |
| **Fallback & Routing** | `circuit_breaker` (5 fails / 30s) + `retry` (3 attempts) + `fallback/try-chain` (per-turn) + `saver_mode` daily token budget scaling. | `FallbackProvider` + `CooldownRegistry` + **session-level sticky promotion** (4 rescues in 15m) + RAII `FallbackProviderGuard` + `guard_cross_provider_model_leak` + CLI providers (`claude_cli`, `codex_cli`, `opencode_cli`). | **OpenCrabs** (routing/CLI) / **Theseus** (`saver_mode` budget scaling). |

---

## 3. Deep Dive 2: Tools Architecture, Editing & Safety Mechanics

| Dimension | Theseus (`tool.clj`, `tools.clj`, `tool/*.clj`, `security.clj`) | OpenCrabs `v0.6.0` (`registry.rs`, `catalog.rs`, `edit.rs`, `hashline/`, `skill_gate.rs`) | Winner & Why |
|---|---|---|---|
| **Code Editing Primitives** | **`write_file` ONLY** (full-file overwrite or append, max 1MB). No targeted patch/replace, no fuzzy match, no hash anchors. | **`edit_file`** (5 ops: `replace`, `replace_lines`, `insert_line`, `delete_lines`, `regex_replace` + `fuzzy.rs` whitespace/Unicode fallback) + **`hashline_edit`** (6-char FNV-1a line-anchored batch edits with stale-tag diagnostics) + `write_file`. | **OpenCrabs (massive gap)** — full-file rewrites burn tokens, hit `max_tokens`, and risk silent code loss on large files. |
| **Code Reading & Search** | `read_file` reads **whole file** (no line offset/limit). `search` is literal `str/includes?` (no regex, no `rg`, 50-match cap). | `read_file` supports **line ranges + `hashline=true`** + `OUTPUT_BUDGET=128KB` + media redirect. Dedicated `glob` (500k cap) + `grep` (regex + `SKIP_DIRS`). | **OpenCrabs (massive gap)** — surgical reading is prerequisite #1 for surgical editing. |
| **Schema Delivery & Discovery** | 9 core + 3 dormant optional; `:tools/defer-optional` hides optional tools behind `discover_tools`. | 23 `CORE_TOOLS` + grouped `EXTENDED_TOOL_INVENTORY` (max 24 active), `tool_search` (weighted scoring), **JIT activation** on direct call, `tool_name_heal` (Levenshtein), `PARAM_ALIASES`. | **OpenCrabs** — JIT activation + name/param healing eliminates wasted retry rounds. |
| **Dynamic Extensibility** | Compiled Clojure handlers only; restart required. `mcp_client.clj` hardcoded to BrowserOS (`127.0.0.1:9210`). | `tools.toml` (`DynamicToolLoader` + `HotToml<T>` mtime hot-reload, shell/bb executors); no MCP bridge on `org/main`, but full **A2A + ACP** (`src/acp/`). | **OpenCrabs** — hot-add a Babashka script tool in `tools.toml` with zero restart. |
| **Skill Injection at Point-of-Edit** | `skill.clj` dumps frontmatter list into system prompt; LLM must remember to read `SKILL.md`. | **`skill_gate::check`**: if any tool touches a path matching a skill's `globs:` frontmatter (unseen in current compaction epoch), **blocks the call once, injects the full `SKILL.md` in the error, marks seen, and lets retry pass**. | **OpenCrabs (brilliant mechanism)** — turns advisory prose into mechanical just-in-time enforcement. |
| **Approval & Policy Gates** | 4 static modes (`:auto-all`, `:auto-safe`, `:never`, `:ask`) + hardcoded secret-path & `never-auto-shell` floors (`rm`/`dd`/`sudo`/`chown`) + file-locked `approvals.edn`. | `guest_allows` + `plan_gate` (freeze `.md`, block mutating tools in `Editing`, **require plan after 12 tool calls**) + `skill_gate` + `requires_approval` + explicit `GrantAutonomy`/`RevokeAutonomy` + `confidential.rs` denylist + `ssrf.rs`. | **OpenCrabs** for workflow gates; **Theseus** for simplicity. |
| **Prompt-Injection / Taint / Exfil** | **`security.clj` (238 LOC)**: per-turn provenance/taint (`:external?`, zero-LLM injection scanner, Shannon-entropy + unseen-URL + shell-upload exfil detector, model-strength tiering) overrides `:auto-all` on taint hits + `redact.clj` postwalks all tool outputs. | **None on `org/main`** (no taint/provenance tracker or exfil entropy detector; relies on `confidential.rs` + output redactor). | **Theseus (clear win)** — genuine defense-in-depth against indirect prompt injection. |
| **Output Compression (RTK)** | Native 127-LOC `rtk.clj` (opt-in, 4 rules for `shell`/`git_status`, zero external binary). | External `rtk` binary wrapper (`rewrite.rs` 507 LOC, broad CLI coverage + savings tracker). | **OpenCrabs** for coverage; **Theseus** for zero external deps. |

---

## 4. Deep Dive 3: Workflows & Building Things with LLMs

This is where the two systems diverge most deeply in **how they actually get an LLM to build working software**:

### 4.1 OpenCrabs v0.6.0: Interactive Plan + Ralph Loop + Worktree Subagents
1. **Mechanical Plan Gate (`plan_gate.rs`):** Once a single turn exceeds **12 tool calls** with `NoPlan`, `ToolRegistry::execute` *mechanically refuses* further tool calls until the agent initializes a `SESSION PLAN` (design `.md` requiring UI Approve via TUI/GUI/Telegram/Discord Plan Card) or `CHECKLIST` (`.json`). This prevents runaway 40-tool-call improvisations.
2. **Anti-Shredding & Ground-Truth Plan State (`plan_tool.rs`):** The LLM cannot self-approve or discard a plan unless the user explicitly invoked `GrantAutonomy`. Every turn receives a harness-written `## PLAN STATE (harness)` block (explicitly stripped during compaction so old directives never zombie-replay).
3. **Ralph Loop Verification (`plan_tool.rs:806-1207`):** Completing a plan task isn't an honor-system checkbox — `RalphLoopConfig` executes real shell verification commands, parses test pass counts, binds commit SHAs to `working_directory`, requires `confirm_spec_check` for model-generated checks, and can execute tasks inside **isolated subagents** (`Start { isolated: Some(true) }`).
4. **Parallel Git Worktree Subagents (`subagent/`):** `SpawnAgentTool` + `SubagentWorktree::create` spawns real child agent sessions in isolated git worktrees (cloning build caches!), tracks them in `tasks_list`, supports named `TeamManager` cohorts + `TeamBroadcastTool`, and reconciles crashed orphans on boot.
5. **Weak Spot — `src/brain/goal/` (687 LOC):** OpenCrabs's goal loop relies on an **LLM judge** (`judge_goal` returning JSON `GoalVerdict`) after each turn — no git checkpoint/rollback and no deterministic disk observers.

### 4.2 Theseus (`9e88d99`): Cybernetic Disk-Observer Goal Loop (`goal/`, 3,124 LOC)
1. **Detached Two-Phase Authoring (`goal_bridge.clj`):** Any build intent (`build/create/implement/scaffold` or `/goal` or `launch_goal`) scaffolds an isolated git repo outside the host repo, runs a dedicated authoring turn (48+ rounds, 8m idle / 2h ceiling) to write `project.edn` + `act.sh`, and validates baseline integrity with 1 repair turn before launching.
2. **Deterministic Disk Observers + Integrity-Before-Goal (`goal/core.clj`, `goal/decision.clj`):** Every iteration reads ground truth directly from disk via shell observers (`:int/:string/:bool/:lines`) and evaluates pure predicates (`goal/predicates.clj`). `goal.decision/decide` checks **`integrity-violations` BEFORE `goal-met?`** — an agent cannot pass a goal by breaking tests or deleting checks.
3. **Automatic Git Rollback & Data-Driven Escalation Ladder (`goal/git.clj`, `goal/core.clj`):** Tags `<prefix>-last-good` before every act; on stall (default 3) or integrity halt, runs `git reset --hard`. On thrash, walks an explicit escalation ladder: **`[:rollback :reseed :reframe :escalate-model]`**, guarded by `:seen-tuples` (`[prompt-index model-index progress-sig]`) to skip identical doomed retries.
4. **External Harness Delegation (`goal/harness.clj`):** Can drive `opencode run --model {{model}} {{prompt}}` in `:spawn` mode, polling observers every `200ms` and terminating early the moment the goal holds.
5. **Weak Spots in Theseus's Build Workflow:**
   - **No Plan Mode / Plan Gate** for interactive multi-file coding inside an existing repo (when you're NOT spinning up a greenfield `goals/<slug>` repo, Theseus has no structured plan card, no 12-call runaway brake, and no Ralph Loop verification binding).
   - **No Running Subagents:** `subagent.clj` (108 LOC) is an uncalled EDN ledger with zero production callers, no LLM tool, and no worktree isolation.
   - **Unwired Learning Loop:** `turn_signals.clj` records behavioural friction into `usage.edn`, and `autonomy.clj` tracks earned RSI tiers, but neither closes back into `rsi.clj` or tool dispatch yet (whereas OpenCrabs's `memory_recall` touches recalled beliefs to reset decay, and `rsi.rs` runs on a `[1h, 4h, 12h, 24h]` backoff ladder emitting typed `ToolProposal`/`CommandProposal`/`SkillProposal` queues).

---

## 5. Complexity vs. Utility Analysis — What Theseus Should Adopt (and Refuse)

### 5.1 Refuse List (Do NOT Port to Theseus)
- **OpenCrabs's `8,650 LOC` `tool_loop.rs` / `2,379 LOC` 8-language `phantom.rs`:** Massive complexity tax for diminishing returns on a single-operator English/code runtime.
- **SQLite-as-truth for sessions (`570 MB` `opencrabs.db` + migration/backup tail):** Theseus's files-as-truth EDN + derived rebuildable FTS5 index is strictly simpler (Rich Hickey win).
- **75-tool compiled registry + TUI/GUI/5-channel matrix:** Keep Theseus's lean core + Web Deck + Telegram.

### 5.2 High-Leverage Port Candidates (Complexity vs. Utility Matrix)

| Rank | Mechanic to Adopt in Theseus | Source in OpenCrabs `v0.6.0` | Est. LOC | Utility (UX/DX/AX) | Complexity | Tradeoff |
|---:|---|---|---:|---|---|---|
| **1** | **Targeted `edit_file` (search-replace + line-range) & line-ranged `read_file` (`offset`/`limit`) + regex `search`** | `edit.rs` + `fuzzy.rs` + `read.rs` + `grep.rs` | ~220 | **Massive** — eliminates full-file rewrites, cuts output tokens 5–10×, stops truncation corruption | Low | Slightly more schema surface; must keep `knowledge/guard-write!` + `protected-write-path?` intact |
| **2** | **Glob-Triggered `skill_gate` (Just-In-Time `SKILL.md` Injection)** | `skill_gate.rs` + `seen_skills` | ~90 | **Very High** — skills become mechanically enforced at edit time instead of passive prompt wallpaper | Very Low | Costs 1 retry round the first time a glob is touched per compaction epoch |
| **3** | **Parallel Safe-Tool Batching (with Same-Path Serialization)** | `parallel_tools.rs` | ~70 | **High** — multi-file `read_file`/`search`/`git_status` rounds run in parallel (`pmap`/futures) | Low | Must keep `seen-calls` & `read-cache` deterministic after batch join |
| **4** | **Lightweight Mechanical Plan Gate + Checklist Tool (Opt-In Threshold)** | `plan_gate.rs` + `plan_tool.rs` (slimmed 20×) | ~250 | **High** — stops runaway interactive coding turns after N tool calls without importing 4,161 LOC | Medium | Needs clean Web Deck + Telegram status projection |
| **5** | **Wire `subagent.clj` to a Real `spawn_subagent` Tool (with Optional Git Worktree)** | `subagent/{spawn,worktree}.rs` | ~260 | **High** — turns dead 108-LOC ledger into real isolated parallel/delegated worker turns with inherited `:subagent/exposure` taint | Medium | Must enforce `max-concurrent` + cleanup of worktrees on crash/exit |
| **6** | **Anthropic `:tools` Cache Breakpoint + Active HTTP Stream/Idle Timeout + Token-Report Calibration** | `anthropic.rs` + `helpers.rs` + `tool_loop.rs:84` | ~150 | **High** — real cost savings on repeated tool rounds + kills hung HTTP calls instead of advisory 180s/480s logging | Medium | Streaming SSE parsing adds ~100 LOC to `provider.clj` |

---

## 6. Actionable Recommendation (Weighted Power vs. Speed vs. Complexity)

**Do NOT turn Theseus into a Clojure clone of OpenCrabs.** Theseus's greatest competitive advantage is that **all of Theseus is `22k LOC`** (less than half of OpenCrabs's `tools/` directory alone), with cleaner files-as-truth storage, real prompt-injection/taint gating (`security.clj`), and a genuinely superior deterministic cybernetic `goal/` runner.

Instead, close the **exact workflow/tool mechanics gap** in **3 small, sequential Red/Green TDD waves (~850 LOC total, zero new dependencies)**:

1. **Wave 1 — Surgical Code I/O (~220 LOC, highest ROI):**
   - Add `edit_file` (exact search-replace with unique-match guard + optional whitespace-tolerant fallback + line-range replace) to `tool/file.clj` & `tools.clj`.
   - Add optional `start_line`/`end_line` to `read_file` and optional `regex?` + `glob` filter to `search`.
   - *Immediate impact:* Interactive coding in Theseus jumps from "rewrite whole files and pray" to surgical multi-file editing.
2. **Wave 2 — Mechanical `skill_gate` + Parallel Read-Only Tool Batching + `:tools` Cache Breakpoint (~180 LOC):**
   - Add `globs:` frontmatter support to `skill.clj` and a 1-time block-and-inject `skill_gate` inside `tool/handle-tool-request`.
   - Parallelize auto-safe read-only tool batches (`read_file`, `search`, `git_status`, `read_session_turns`) via bounded `future`s in `core.clj`.
   - Mark the last tool definition with `:cache_control {:type "ephemeral"}` in `provider.clj`'s Anthropic wire payload.
3. **Wave 3 — Real `spawn_subagent` (Git Worktree + Inherited Taint) + Slim Interactive Plan Gate (~450 LOC):**
   - Wire `subagent.clj` to an actual `spawn_subagent` tool running `core/run-turn!` in an optional temporary `git worktree` (inheriting `:subagent/exposure` taint).
   - Add a slim, pure-EDN `plan` tool + configurable `plan-gate-after-tool-calls` brake for interactive repo work, keeping `goal/` as the heavy autonomous build engine.

Execute those three waves and Theseus keeps its **~23k LOC simplicity** while matching or beating OpenCrabs `v0.6.0` on every workflow and tool mechanic that actually matters when building software with LLMs.
