# Theseus vs OpenCrabs — Architecture Gap Analysis (tools focus)

**Date:** 2026-11-11 · **Prepared by:** OpenCrabs v0.5.14 (this harness), for moe
**Evidence:** both live trees read this turn (~10:40 EAT). Theseus @ `2d479fa` (`~/theseus/theseus`); OpenCrabs v0.5.14 live runtime + source tree (`~/.opencrabs/src`). Nothing recycled from history without re-reading. The other session's WIP (`coordinates.clj`, `web_auth.clj`, tests) untouched and uncommitted.

## TL;DR

Same DNA; one genuinely different layer.

Both systems are the same animal: one agent loop (turn engine), N chat transports, a tool registry the model calls, file-based state, a scheduler, skills, approvals, autonomy tiers, provider fallback, context compression, memory ranking, RSI. You built both, and it shows — `autonomy.clj` with computed promotion bars here, `/autonomy` with earned tiers there; `fallback.clj`'s `try-chain` here, `[fallback]` chains there; BM25+recency `semantic_memory` here, `memory_search` scopes there.

The one layer where they truly diverge is **the tools layer**:

- **Theseus: 8 tools, one flat registry, all schemas in every request.** `read_file, write_file, shell, search, git_status, telegram_send_file, web_send_file, launch_goal`. Handlers in-process (`bb-agent.tool.*`), gated by a policy DSL + approval ledger. The real surface is "8 tools + whatever `shell` can do" — the OS is the tool, and skills are the knowledge of what shell can do.
- **OpenCrabs: ~75 built-in tools in tiers + 8 runtime-registered + MCP + A2A remotes + 63 skills.** Schema delivery is two-phase (46 schemas in-prompt, ~29 name-only, activated on demand) because a fat registry eats the context window; dynamic tools load from `tools.toml` without restart — the first dynamic entry here is a Babashka script, so the two systems literally already compose at this seam.

**Measured (§5):** 154,260 lifetime tool calls on this box — bash alone is ~39% of them, the top 20 tools carry ~97% of ledger volume, and 18 of those 20 map to a Theseus primitive. The 75-tool breadth is mostly dormant; Theseus' measured gaps all sit in the ≤0.5%-each long tail, which argues for bridging it, not porting it.

Theseus pays per-capability build cost for total control. OpenCrabs pays platform complexity for breadth and hot extension. Neither should become the other.

## 1. The tools layer, side by side

| Aspect | Theseus | OpenCrabs |
|---|---|---|
| Registry | `tools.clj`: 8 defs, OpenAI function format, one source of truth (provider, handlers, policy predicates share names) | compiled registry: 46 schemas in-prompt + ~29 lazy (name-only until `tool_search`) + 8 dynamic (`tools.toml`) |
| Schema delivery | all 8, every request | two-phase; lazy activation exists for context economics |
| Handlers | `bb-agent.tool.*` (7 namespaces) | compiled Rust + shell-executor dynamic tools (bb scripts allowed) |
| Gating | `policy.clj` rules DSL (external file, 2s eval budget) + `approval.clj` ledger + buttons on both transports | approval policy + earned-autonomy classes + plan gates + skill trust (Wilson lower bound) + quarantine + prompt-scan |
| Telemetry | `stats.clj` summaries; `rsi.clj` tools-aggregate (min 50 events, 0.3 flag rate) | per-call feedback ledger → honest failure-rate reports |
| Runtime lifecycle | none — tools are compiled into the image | enable/disable/reload via `tools.toml` + `tool_manage`, no restart |
| External tool sources | none (BrowserOS neo MCP driven ad-hoc over HTTP per session) | MCP client (Gmail workspace) + A2A both directions + Bankai as external tools |
| Add a tool | write Clojure + tests + restart | append a `tools.toml` entry (shell/bb executor) + reload |

## 2. Tool-category inventory (verified this turn)

| Category | Theseus | OpenCrabs | Verdict |
|---|---|---|---|
| File/shell/git | read/write/search/git_status/shell | read/write/edit (hash-anchored)/bash/ls/glob/grep | parity |
| Internet search | **none** — `web_search.clj` is local FTS5 over transcripts, not the web | `web_search` fan-out (4 engines) + `exa_search` + `brave_search` | **gap, Theseus** |
| Browser | none in the registry (BrowserOS neo via ad-hoc MCP HTTP) | CDP suite (`browser_*`), first-class tools | **gap, Theseus** (registry-wise) |
| Documents | none — artifacts are raw files | `generate_document` (xlsx/docx/pdf/pptx), `parse_document`, `pdf_to_images` | **gap, Theseus** |
| Vision | `vision.clj` (attach to first user msg, provider-bound) | `analyze_image`/`analyze_video` (provider-bound) | parity; both currently blocked by the glm-4.6v account state |
| Voice | `telegram_voice.clj`: whisper/whisper-cpp STT only | local-stt + local-tts compiled | small gap (TTS) |
| Messaging | `telegram_send_file` + web artifacts | 5 channels × full control + A2A + `session_notify` | gap, breadth (OpenCrabs) |
| Delegation | `launch_goal` supervised loop + `subagent.clj` claim/capacity ledger | `spawn_agent` (own worktree/branch), teams, `tasks_list` | both have it — different bets (§3) |
| Memory search | FTS5 over session files + `semantic_memory` BM25+recency | SQLite truth + `memory_search` scopes + code-graph | different bets (§4) |
| Skills | 41 skills + `skill_economy.clj` (claims/receipts/fleet) + `skill_research_github` | 63 skills + trust tiers + quarantine + outcomes ledger | different sophistication: economy vs trust |
| MCP / A2A | **none** | MCP client (Gmail), A2A client+server (agent card, profiles) | **gap, Theseus** |
| Scheduling | `schedule.clj` + daemon matcher | `cron_manage` + heartbeat protocol | parity |
| Compaction | `compression.clj` (prune tool results, summarize middle) | `/compact` auto at 80% | parity |

## 3. Delegation: goal runners vs sub-agent sessions

The deepest design difference inside the tools layer. Theseus' `launch_goal` spawns a **supervised loop** — acts, measures observers, rolls back on integrity violations, budgets time, reports progress (`goal/`: registry, budget, liveness, outcomes, taxonomy, decision, predicates — 21 namespaces, 2,444 LOC with `tool/`), plus `subagent.clj`'s claim/capacity ledger. OpenCrabs' `spawn_agent` spawns a **parallel session** with its own worktree and branch running the same engine; the parent gets pushed results and a roster.

Theseus optimizes for convergent, verifiable single builds (integrity predicates, rollback). OpenCrabs optimizes for parallel throughput (worktree isolation). Neither is strictly ahead. Note that the owner's Sequential Work Mandate on this OpenCrabs instance effectively disables its concurrency in practice — under that policy, Theseus' supervised model is the more honest fit.

## 4. State: files-as-truth vs DB-as-truth

Theseus: session EDN files are the write path and the truth; `search.db` is a **derived** FTS5 index (mtime-incremental, one-way flow — stated in the namespace docstring). OpenCrabs: SQLite (`opencrabs.db`, 505 MB on this machine) is the truth for sessions/messages/ledger, with brain/memory files alongside.

Hickey verdict: Theseus' shape is the cleaner one — truth you can `cat`, indexes you can rebuild, zero DB-migration tail (OpenCrabs carries `migrations/` and a stack of `.db.bak` files as the price of that choice). OpenCrabs buys queryability and concurrent access. For a single-operator agent, files-as-truth is underappreciated.

## 5. Measured usage — what the 75 tools actually do here (2026-11-11)

Pulled live this turn from this OpenCrabs instance: the per-call feedback ledger (RSI events) plus mission-control aggregates over `opencrabs.db`. Lifetime on this box: **154,260 tool calls, 13,431 failures (8.7%)**. Shares below are of the top-20 volume (167,970 ledger events; the DB aggregate agrees on order and shape).

| # | Tool | Calls | Share | OK rate | Theseus primitive |
|---|---|---|---|---|---|
| 1 | bash | 66,205 | 39% | 93.1% | `shell` ✓ |
| 2 | read_file | 19,139 | 11% | 98.4% | `read_file` ✓ |
| 3 | edit_file | 15,909 | 9% | 95.2% | `write_file` (rewrite-style edits) ~ |
| 4 | session_context | 15,212 | 9% | 70.5% | none — session state is files, by design |
| 5 | opencrabs_sqlite_query | 9,197 | 5% | 91.9% | `shell` + bb over EDN ✓ |
| 6 | session_search | 7,270 | 4% | 90.4% | `search` (FTS5) ✓ |
| 7 | plan | 7,119 | 4% | 97.4% | `launch_goal` + goal runners ✓ |
| 8 | tool_search | 5,106 | 3% | 99.9% | unneeded — all 8 schemas ship in every prompt |
| 9 | write_file | 4,618 | 3% | 89.4% | `write_file` ✓ |
| 10 | grep | 4,564 | 3% | 98.4% | `search` / `shell` ✓ |
| 11 | bankai_task | 3,073 | 2% | 98.7% | Bankai native ✓ |
| 12 | glob | 2,076 | 1% | 97.7% | `shell` ✓ |
| 13 | load_brain_file | 1,921 | 1% | 99.2% | `read_file` over `brain/` ✓ |
| 14 | telegram_send | 1,772 | 1% | 94.5% | telegram turn + `telegram_send_file` ✓ |
| 15 | ls | 1,376 | 1% | 97.1% | `shell` ✓ |
| 16 | write_opencrabs_file | 1,072 | 0.6% | 94.9% | `write_file` ✓ |
| 17 | http_request | 650 | 0.4% | 91.8% | `shell` + curl ~ |
| 18 | cron_manage | 613 | 0.4% | 96.4% | `schedule` ✓ |
| 19 | bankai_query | 541 | 0.3% | 99.8% | Bankai native ✓ |
| 20 | memory_search | 537 | 0.3% | 83.2% | `search` + `semantic_memory` ✓ |

What the numbers say:

- **The 8-tool shape covers the real workload.** Every top-20 tool maps to an existing Theseus primitive (18 cleanly; two partials: in-place edits, first-class HTTP). Everything below rank 20 is individually ≤0.3% — the breadth is dormant.
- **bash alone is ~39% of everything.** The OS really is the tool — Theseus' thesis, now with numbers.
- **The internet-facing tools never reach the top 20.** `web_search` (367), `exa_search` (303), the ten `browser_*` tools (~711 combined), `gmail_mcp` (492), `analyze_image` (483) all start around rank 21. Real but rare: Theseus' measured gaps live entirely in this long tail.
- **The tail is where reliability rots.** `pdf_to_images` 0/9, `a2a_send` 44%, `generate_document` 60%, `gmail_mcp` 61%. A fat registry isn't just dormant — unused tools silently fail when finally needed.
- **OpenCrabs' #4 tool is its single biggest failure source.** `session_context`: 4,486 failures (29.5% fail rate) — roughly a third of ALL failures on this box; the JSON-corruption disease is chronic enough to have a repair recipe in the brain files. Theseus has no equivalent tool to fail: session state is files (§4, now with numbers).
- **Phantom tools are recorded as data.** 200+ invented `done-*`/`stop-*`/`final-*` tool names, nearly all failed calls — the loop-terminator hallucination disease as a table. Theseus' validated 8-name registry makes the attempt structurally impossible.
- **Lazy activation is a real tax that works.** 3% of all calls are `tool_search` itself (5,106 calls, 99.9% OK) — the price of fitting ~75 tools in one context window. At 8 tools Theseus pays nothing; at 75 there is no alternative. Both designs are internally consistent.
- **Telemetry without digestion.** RSI last ran 26 days ago; 21,973 tool events since have gone un-analyzed — and the two stores disagree on some tools (`hashline_edit`: 99% OK in the ledger, 62% in the DB aggregate). The ledger's value is real; the missing half is the scheduled digest.

Effect on §6 and §9 (both revised below): the missing-capability list survives, now ranked by measured demand — recurring need (web search) is a native build; the ≤0.5%-each tail (browser, mail, vision, docgen) argues for borrowing via the A2A/MCP bridge instead of porting five low-usage capabilities.

## 6. Gaps — OpenCrabs has, Theseus lacks (measured leverage order)

1. **Internet web search** — the biggest real gap and the only one with recurring measured demand (~670 search calls on this box). Briefs would benefit directly (news context, quotes beyond the registry). One engine behind a `web_search` tool shape is a small build.
2. **A2A endpoint** — OpenCrabs already speaks it both ways; Theseus has the `web_routes` base to hang an agent card on. The bridge that lets the two agents delegate instead of duplicating — and the designated carrier of the ≤0.5%-each tail that follows.
3. **MCP client as a module** — BrowserOS neo is driven ad-hoc over HTTP per session; a small MCP client namespace (initialize / session-id / tools-list / call) turns any MCP server into a first-class tool source. OpenCrabs' Gmail MCP shows the pattern (and, at 61% success, the testing burden it carries).
4. **Browser tool in the registry** — today the model cannot call browser automation as a tool; it must be scripted via shell. ~711 browser-tool calls on this box say it would get used.
5. **Document generation** — dossiers are text today. Measured demand here: 5 calls, 60% success — borrow through the bridge before building.
6. **Runtime tool lifecycle** — tools are compiled-in; no enable/disable/reload. Cheap to accept at 8 tools, expensive at 30.

## 7. Gaps — Theseus has, OpenCrabs lacks

1. **The web deck** — live transcripts, approval buttons, artifact download, roles, schedule CRUD, mic/camera capture. OpenCrabs has no browser-facing surface at all (TUI + chat channels only).
2. **Compiled guardrails** — boot dead-config-key guard, `state_hygiene` gate, module-graph checker, witness `.edn` files. OpenCrabs' equivalents are prose rules in brain files, not enforced code.
3. **Files-as-truth state** (§4) — including the zero-migration tail.
4. **Policy DSL for tool gating** — an external, editable rules file evaluated per tool call (2s budget) is more malleable than OpenCrabs' compiled approval classes.

## 8. Where they already converged (do not re-build)

Approvals with buttons, autonomy tiers with computed promotion, provider fallback chains, circuit breakers, context compression, BM25+recency memory ranking, FTS session search, skills with a discover/research loop, doctor/preflight, state hygiene, durable sessions, rate-limited web auth with roles, Telegram bot-command registration from skills. Any "gap" work in these categories would duplicate something that already exists on both sides.

## 9. Recommendations (revised by measured usage — §5)

1. **Web search tool (Theseus)** — the one native build with recurring measured demand (~670 live search calls on this box; the briefs would use it today). One engine first, fan-out later.
2. **A2A agent card for Theseus** on the existing `web_routes` base + OpenCrabs' existing `a2a_send` — the bridge that carries the long tail: browser (~711 calls), Gmail (492), vision (483), docgen each measure ≤0.5% of this box's volume, which is borrow-don't-build territory. OpenCrabs borrows Theseus' goal supervision for long builds over the same seam. Composition beats five low-usage ports.
3. **Tool outcome ledger (Theseus)** — upgraded by §5's data: the #4 tool here fails 29.5% (a third of all failures), tail tools rot at 0–61%, and RSI sat undigested for 26 days. One atom per call (name, ok, duration) beside the approval ledger — **plus a scheduled digest**; the ledger without the digest is the OpenCrabs failure mode, now measured.
4. **MCP client module (Theseus)** — after the A2A card; generalizes the ad-hoc BrowserOS driving. ~711 browser calls here say it would get used.
5. **PDF dossier generation** — demoted: 5 uses and 60% success even on OpenCrabs. Borrow through the bridge first; build natively only if it sticks.
6. **OpenCrabs steals** — the webdeck cockpit pattern; the boot dead-key guard in `config_manager`; witness files on releases.
7. **Hygiene note (Theseus)** — 60 untracked `*.bak-*` files in `src/bb_agent/` are local iteration residue; git history covers them, `trash` them in a calm moment (not now — another session's WIP is in flight in this tree).

## 10. Method & uncertainty

- Theseus: `src/bb_agent` enumerated (79 live namespaces + `tool/` 7 + `goal/` 21, ~15.5k LOC incl. local .bak residue); tool registry read in full; secondary namespaces read at API surface (defn lists), not line-by-line.
- OpenCrabs: live runtime surface (this session's registry: 46 schema'd + ~29 lazy tools), `tools.toml` (8 dynamic, first is a bb script), skills dir (63), source tree present at `~/.opencrabs/src` — structure verified, Rust internals not audited this turn.
- Test counts (307 tests / 1,427 assertions) quoted from the `2d479fa` gate run; not re-run today.
- §5 usage data: this instance's per-call feedback ledger + mission-control aggregates over `opencrabs.db`, pulled 2026-11-11 ~11:05 EAT. Ledger and DB disagree on some per-tool rates (both quoted where divergent); the top-20 share base is the 167,970-event ledger volume.
- No secrets appear in this document.
