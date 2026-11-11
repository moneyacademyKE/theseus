# Theseus vs OpenCrabs — Architecture Gap Analysis (tools focus)

**Date:** 2026-11-11 · **Prepared by:** OpenCrabs v0.5.14 (this harness), for moe
**Evidence:** both live trees read this turn (~10:40 EAT). Theseus @ `2d479fa` (`~/theseus/theseus`); OpenCrabs v0.5.14 live runtime + source tree (`~/.opencrabs/src`). Nothing recycled from history without re-reading. The other session's WIP (`coordinates.clj`, `web_auth.clj`, tests) untouched and uncommitted.

## TL;DR

Same DNA; one genuinely different layer.

Both systems are the same animal: one agent loop (turn engine), N chat transports, a tool registry the model calls, file-based state, a scheduler, skills, approvals, autonomy tiers, provider fallback, context compression, memory ranking, RSI. You built both, and it shows — `autonomy.clj` with computed promotion bars here, `/autonomy` with earned tiers there; `fallback.clj`'s `try-chain` here, `[fallback]` chains there; BM25+recency `semantic_memory` here, `memory_search` scopes there.

The one layer where they truly diverge is **the tools layer**:

- **Theseus: 8 tools, one flat registry, all schemas in every request.** `read_file, write_file, shell, search, git_status, telegram_send_file, web_send_file, launch_goal`. Handlers in-process (`bb-agent.tool.*`), gated by a policy DSL + approval ledger. The real surface is "8 tools + whatever `shell` can do" — the OS is the tool, and skills are the knowledge of what shell can do.
- **OpenCrabs: ~75 built-in tools in tiers + 8 runtime-registered + MCP + A2A remotes + 63 skills.** Schema delivery is two-phase (46 schemas in-prompt, ~29 name-only, activated on demand) because a fat registry eats the context window; dynamic tools load from `tools.toml` without restart — the first dynamic entry here is a Babashka script, so the two systems literally already compose at this seam.

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

## 5. Gaps — OpenCrabs has, Theseus lacks (leverage order)

1. **Internet web search** — the biggest real gap. Briefs would benefit directly (news context, quotes beyond the registry). One engine behind a `web_search` tool shape is a small build.
2. **MCP client as a module** — BrowserOS neo is driven ad-hoc over HTTP per session; a small MCP client namespace (initialize / session-id / tools-list / call) turns any MCP server into a first-class tool source. OpenCrabs' Gmail MCP shows the pattern.
3. **A2A endpoint** — OpenCrabs already speaks it both ways; Theseus has the `web_routes` base to hang an agent card on. This is the bridge that lets the two agents delegate instead of duplicating.
4. **Document generation** — dossiers are text today; PDF/Excel would complete delivery.
5. **Browser tool in the registry** — today the model cannot call browser automation as a tool; it must be scripted via shell.
6. **Runtime tool lifecycle** — tools are compiled-in; no enable/disable/reload. Cheap to accept at 8 tools, expensive at 30.

## 6. Gaps — Theseus has, OpenCrabs lacks

1. **The web deck** — live transcripts, approval buttons, artifact download, roles, schedule CRUD, mic/camera capture. OpenCrabs has no browser-facing surface at all (TUI + chat channels only).
2. **Compiled guardrails** — boot dead-config-key guard, `state_hygiene` gate, module-graph checker, witness `.edn` files. OpenCrabs' equivalents are prose rules in brain files, not enforced code.
3. **Files-as-truth state** (§4) — including the zero-migration tail.
4. **Policy DSL for tool gating** — an external, editable rules file evaluated per tool call (2s budget) is more malleable than OpenCrabs' compiled approval classes.

## 7. Where they already converged (do not re-build)

Approvals with buttons, autonomy tiers with computed promotion, provider fallback chains, circuit breakers, context compression, BM25+recency memory ranking, FTS session search, skills with a discover/research loop, doctor/preflight, state hygiene, durable sessions, rate-limited web auth with roles, Telegram bot-command registration from skills. Any "gap" work in these categories would duplicate something that already exists on both sides.

## 8. Recommendations

1. **Bridge, don't converge (both).** A2A agent card for Theseus on the existing `web_routes` base + OpenCrabs' existing `a2a_send`. Then Theseus briefs can borrow OpenCrabs' search/browser/document tools without implementing them, and OpenCrabs can borrow Theseus' goal supervision for long builds. Composition beats reimplementation.
2. **Web search tool (Theseus)** — one engine first, fan-out later.
3. **Tool outcome ledger (Theseus)** — append one atom per call (name, ok, duration) beside the approval ledger; feeds honest failure rates. The OpenCrabs lesson: unmeasured tools rot silently.
4. **MCP client module (Theseus)** — generalize the ad-hoc BrowserOS driving.
5. **PDF dossier generation (Theseus)** — after search.
6. **OpenCrabs steals** — the webdeck cockpit pattern; the boot dead-key guard in `config_manager`; witness files on releases.
7. **Hygiene note (Theseus)** — 60 untracked `*.bak-*` files in `src/bb_agent/` are local iteration residue; git history covers them, `trash` them in a calm moment (not now — another session's WIP is in flight in this tree).

## 9. Method & uncertainty

- Theseus: `src/bb_agent` enumerated (79 live namespaces + `tool/` 7 + `goal/` 21, ~15.5k LOC incl. local .bak residue); tool registry read in full; secondary namespaces read at API surface (defn lists), not line-by-line.
- OpenCrabs: live runtime surface (this session's registry: 46 schema'd + ~29 lazy tools), `tools.toml` (8 dynamic, first is a bb script), skills dir (63), source tree present at `~/.opencrabs/src` — structure verified, Rust internals not audited this turn.
- Test counts (307 tests / 1,427 assertions) quoted from the `2d479fa` gate run; not re-run today.
- No secrets appear in this document.
