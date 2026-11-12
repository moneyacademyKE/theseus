# Theseus vs OpenCrabs — Architecture Gap Analysis (tools focus)

**Date:** 2026-11-11 · **Prepared by:** OpenCrabs v0.5.14 (this harness), for moe · **Updated 2026-11-12 01:10 EAT after the browser_read follow-up.**
**Evidence:** both live trees read this turn (~10:40 EAT); recommendations implemented and re-verified the same day. Theseus @ `c8c8612` (`~/theseus/theseus`); OpenCrabs v0.5.14 live runtime + source tree (`~/.opencrabs/src`). Nothing recycled from history without re-reading. The other session's WIP (`coordinates.clj`, `web_auth.clj`, tests) untouched and uncommitted.

## Execution status (2026-11-12, 00:12 EAT)

The three recommendations from §9 are no longer proposals. Receipts live in §10; the headline facts: web search is a native `web_search` tool backed by Brave HTML with a relevance gate; Theseus exposes an A2A card + JSON-RPC on the live deck; and the tool-outcome ledger is the existing `usage.edn` funnel, now with wall-time and a query script. Bing was tried and dropped: it served WhatsApp forum posts for a Babashka query.

Follow-up `bk-c6c7` closes the MCP-client gap: `bb-agent.mcp-client` now performs the Streamable HTTP handshake, carries the session and negotiated protocol headers, handles JSON and SSE, and makes bounded `tools/list` / `tools/call` requests. Live BrowserOS neo 0.0.93 returned 27 tools and completed a read-only `tabs list`; its session id and tab payload stayed out of output. This is a protocol module, not yet a model-visible browser tool.

Follow-up `bk-5c6a` closes that last slice: `browser_read` is now the registry's 10th tool — a single-purpose page reader. Each call opens one tab of its own, reads it as text (bounded to 20,000 chars, framed untrusted), closes it, and fails closed. It is deliberately NOT auto-safe: a real browser can carry authenticated sessions, so an auto-safe turn refuses it and a human approval or policy rule is required. No click, type, evaluate, credentials, or payment surface exists on the tool at all — of BrowserOS' 27 advertised tools it uses exactly two: `tabs` and `read`.

## TL;DR

Same DNA; one genuinely different layer.

Both systems are the same animal: one agent loop (turn engine), N chat transports, a tool registry the model calls, file-based state, a scheduler, skills, approvals, autonomy tiers, provider fallback, context compression, memory ranking, RSI. You built both, and it shows — `autonomy.clj` with computed promotion bars here, `/autonomy` with earned tiers there; `fallback.clj`'s `try-chain` here, `[fallback]` chains there; BM25+recency `semantic_memory` here, `memory_search` scopes there.

The one layer where they truly diverge is **the tools layer**:

- **Theseus: 11 tools, one flat registry, all schemas in every request.** `read_file, write_file, shell, search, web_search, browser_read, git_status, telegram_send_file, web_send_file, launch_goal, a2a_task`. Handlers in-process (`bb-agent.tool.*`), gated by a policy DSL + approval ledger. The real surface is "11 tools + whatever `shell` can do" — the OS is the tool, and skills are the knowledge of what shell can do. A2A is now both directions: a server endpoint over the web lane, and a model-visible `a2a_task` client that borrows remote agents (approval-gated; the stored bridge credential attaches only to localhost targets).
- **OpenCrabs: ~75 built-in tools in tiers + 8 runtime-registered + MCP + A2A remotes + 63 skills.** Schema delivery is two-phase (46 schemas in-prompt, ~29 name-only, activated on demand) because a fat registry eats the context window; dynamic tools load from `tools.toml` without restart — the first dynamic entry here is a Babashka script, so the two systems literally already compose at this seam.

**Measured (§5):** 154,260 lifetime tool calls on this box — bash alone is ~39% of them, the top 20 tools carry ~97% of ledger volume, and 18 of those 20 map to a Theseus primitive. The 75-tool breadth is mostly dormant; Theseus' measured gaps all sit in the ≤0.5%-each long tail, which argues for bridging it, not porting it.

Theseus pays per-capability build cost for total control. OpenCrabs pays platform complexity for breadth and hot extension. Neither should become the other.

## 1. The tools layer, side by side

| Aspect | Theseus | OpenCrabs |
|---|---|---|
| Registry | `tools.clj`: 10 defs, OpenAI function format, one source of truth (provider, handlers, policy predicates share names) | compiled registry: 46 schemas in-prompt + ~29 lazy (name-only until `tool_search`) + 8 dynamic (`tools.toml`) |
| Schema delivery | all 9, every request | two-phase; lazy activation exists for context economics |
| Handlers | `bb-agent.tool.*` (7 namespaces) | compiled Rust + shell-executor dynamic tools (bb scripts allowed) |
| Gating | `policy.clj` rules DSL (external file, 2s eval budget) + `approval.clj` ledger + buttons on both transports | approval policy + earned-autonomy classes + plan gates + skill trust (Wilson lower bound) + quarantine + prompt-scan |
| Telemetry | `usage/tool-event!` funnel: one event per call with name, session, status, denial source, executed?, wall ms; `scripts/tool_ledger.bb` reports per-tool counts/rates/timing | per-call feedback ledger → honest failure-rate reports |
| Runtime lifecycle | none — tools are compiled into the image | enable/disable/reload via `tools.toml` + `tool_manage`, no restart |
| External tool sources | A2A server on `web_routes` (agent card + JSON-RPC, bearer-gated); `mcp_client.clj` Streamable HTTP client for BrowserOS and other MCP servers | MCP client (Gmail workspace) + A2A both directions + Bankai as external tools |
| Add a tool | write Clojure + tests + restart | append a `tools.toml` entry (shell/bb executor) + reload |

## 2. Tool-category inventory (verified this turn)

| Category | Theseus | OpenCrabs | Verdict |
|---|---|---|---|
| File/shell/git | read/write/search/git_status/shell | read/write/edit (hash-anchored)/bash/ls/glob/grep | parity |
| Internet search | `web_search` — Brave HTML SERP, keyless, direct URLs, query-relevance gate; Bing backend rejected after serving unrelated WhatsApp posts for a Babashka query | `web_search` fan-out (4 engines) + `exa_search` + `brave_search` | **closed for Theseus** (native, one engine) |
| Browser | `browser_read` — single-purpose page reader over the MCP client: HTTPS-only, one owned tab per call, approval-gated, fail-closed | CDP suite (`browser_*`), first-class tools | **closed for Theseus** (bounded read; no act/eval surface) |
| Documents | none — artifacts are raw files | `generate_document` (xlsx/docx/pdf/pptx), `parse_document`, `pdf_to_images` | **gap, Theseus** |
| Vision | `vision.clj` (attach to first user msg, provider-bound) | `analyze_image`/`analyze_video` (provider-bound) | parity; both currently blocked by the glm-4.6v account state |
| Voice | `telegram_voice.clj`: whisper/whisper-cpp STT only | local-stt + local-tts compiled | small gap (TTS) |
| Messaging | `telegram_send_file` + web artifacts | 5 channels × full control + A2A + `session_notify` | gap, breadth (OpenCrabs) |
| Delegation | `launch_goal` supervised loop + `subagent.clj` claim/capacity ledger | `spawn_agent` (own worktree/branch), teams, `tasks_list` | both have it — different bets (§3) |
| Memory search | FTS5 over session files + `semantic_memory` BM25+recency | SQLite truth + `memory_search` scopes + code-graph | different bets (§4) |
| Skills | 41 skills + `skill_economy.clj` (claims/receipts/fleet) + `skill_research_github` | 63 skills + trust tiers + quarantine + outcomes ledger | different sophistication: economy vs trust |
| MCP / A2A | A2A server shipped (both card paths, `/a2a/v1`, bearer-gated, reuses the web turn lane); MCP client shipped with JSON/SSE, session-id and tools call support | MCP client (Gmail), A2A client+server (agent card, profiles) | **A2A + MCP client closed** |
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
| 8 | tool_search | 5,106 | 3% | 99.9% | unneeded — all 9 schemas ship in every prompt |
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
- **Lazy activation is a real tax that works.** 3% of all calls are `tool_search` itself (5,106 calls, 99.9% OK) — the price of fitting ~75 tools in one context window. At 11 tools Theseus pays nothing; at 75 there is no alternative. Both designs are internally consistent.
- **Telemetry without digestion.** RSI last ran 26 days ago; 21,973 tool events since have gone un-analyzed — and the two stores disagree on some tools (`hashline_edit`: 99% OK in the ledger, 62% in the DB aggregate). The ledger's value is real; the missing half is the scheduled digest.

Effect on §6 and §9 (both revised below): the missing-capability list survives, now ranked by measured demand — recurring need (web search) is a native build; the ≤0.5%-each tail (browser, mail, vision, docgen) argues for borrowing via the A2A/MCP bridge instead of porting five low-usage capabilities.

## 6. Gaps — OpenCrabs has, Theseus lacks (measured leverage order)

1. **Document generation** — CLOSED 2026-11-12 (bk-31e2). Ingest is native: pdf via poppler 26.11 + PATH-robust binary resolution (`d4937bc`), xlsx via bb-excel (`2a687e7`); polished output borrows through `a2a_task` (`6146d0e`) — live receipt: OpenCrabs generated a PDF over the bridge whose marker sentence came back out through Theseus' own pdftotext lane. `skills/documents/SKILL.md` pins every working path and the dead ends.
2. **Runtime tool lifecycle** — tools are compiled-in; no enable/disable/reload. Cheap to accept at 11 tools, expensive at 30.

Closed by the 2026-11-11/12 execution: internet web search (native `web_search`, Brave + relevance gate), the A2A endpoint (live card + JSON-RPC on the deck), the MCP client module (live BrowserOS handshake + tools call), the browser registry tool (`browser_read`, bounded page reader, `c8c8612`), and the document layer (native pdf/xlsx ingest `d4937bc`/`2a687e7` + the live A2A borrow `a2a_task`, `6146d0e`).

## 7. Gaps — Theseus has, OpenCrabs lacks

1. **The web deck** — live transcripts, approval buttons, artifact download, roles, schedule CRUD, mic/camera capture. OpenCrabs has no browser-facing surface at all (TUI + chat channels only).
2. **Compiled guardrails** — boot dead-config-key guard, `state_hygiene` gate, module-graph checker, witness `.edn` files. OpenCrabs' equivalents are prose rules in brain files, not enforced code.
3. **Files-as-truth state** (§4) — including the zero-migration tail.
4. **Policy DSL for tool gating** — an external, editable rules file evaluated per tool call (2s budget) is more malleable than OpenCrabs' compiled approval classes.

## 8. Where they already converged (do not re-build)

Approvals with buttons, autonomy tiers with computed promotion, provider fallback chains, circuit breakers, context compression, BM25+recency memory ranking, FTS session search, skills with a discover/research loop, doctor/preflight, state hygiene, durable sessions, rate-limited web auth with roles, Telegram bot-command registration from skills. Any "gap" work in these categories would duplicate something that already exists on both sides.

## 9. Recommendations (revised by measured usage — §5)

> **Owner directive 2026-11-12 20:13 EAT (topic 4721):** "these new capabilities are low priority. they should be optional." Enforced same day (`7173035`, bead `bk-ac0b`): `web_search`, `browser_read`, `a2a_task` are **dormant by default** — filtered out of every provider prompt and refused at dispatch unless `:tools/enable-optional` in config.edn opts them in. The live registry is back to the pre-capability 8 tools. Receipts in §10. No further capability threads without owner pull.

1. **Web search tool (Theseus)** — ✅ SHIPPED, **now optional** (`5633faa`, repaired in `10ecda3`, gated in `7173035`): native `web_search`, keyless Brave HTML, direct URLs, query-relevance filter. Live probes: 5/5 relevant for Babashka and 5/5 for Clojure spec. Bing was dropped after serving WhatsApp forum posts for a Babashka query.
2. **A2A agent card for Theseus** — ✅ SHIPPED (`2c839f1`): both discovery paths (`agent.json` + `agent-card.json`), `/a2a/v1` + `/a2a`, bearer-gated, mapped onto the existing `web_turn` job lane. Live receipt: OpenCrabs `a2a_send discover` returns the card; unauthenticated send is 401; an authenticated `/usage` turn went `submitted → completed` with the real reply artifact.
3. **Tool outcome ledger (Theseus)** — ✅ SHIPPED (`eb56e7d`): the existing `usage.edn` funnel now records wall ms + executed? per call (never args); `scripts/tool_ledger.bb` prints per-tool call/ok/error/denied/avg/max. The scheduled digest already exists (`rsi-daily`, 09:30 EAT) — the 26-day-stale figure was the old reality, corrected.
4. **MCP client module (Theseus)** — ✅ SHIPPED (`e6300b1`, `bk-c6c7`): 175-line bounded Streamable HTTP client with JSON/SSE decoding, strict response-id matching, session/protocol headers, initialized notification, `tools/list` and `tools/call`. Live: BrowserOS neo 0.0.93, 27 tools, read-only `tabs list` succeeded; 6 focused tests / 30 assertions and full suite 325 / 1,511 are green. Browser-as-a-registry-tool was the next slice, and it is closed now — and gated like the rest: `bk-5c6a` (`c8c8612`) shipped `browser_read` (HTTPS-only, one owned tab per call, deliberately not auto-safe); `bb scripts/probe_browser.bb` is all-green against live BrowserOS.
5. **PDF dossier generation** — ✅ CLOSED as a borrow (bk-31e2, `6146d0e`): `a2a_task` sent a real task to the OpenCrabs bridge, the PDF landed on disk, and its marker sentence extracted back through this repo's own pdftotext lane. The `a2a_task` tool is now optional like the others.
6. **OpenCrabs steals** — unchanged (webdeck cockpit pattern; boot dead-key guard in `config_manager`; witness files on releases).
7. **Hygiene note (Theseus)** — 62 `*.bak-*` files in `src/bb_agent/` remain local iteration residue (counted live 2026-11-12); password-rotation leftovers were moved to a mode-700 `scratch/` quarantine, but the `.bak-*` sweep still waits for a calm moment (another session's WIP is in flight in this tree).

## 10. Execution receipts (bk-a325, bk-c6c7, bk-ac0b)

| Recommendation | Commit | Gates that ran |
|---|---|---|
| `web_search` tool (Bing → Brave repair) | `5633faa`, `10ecda3` | unit suite 319 tests / 1,481 assertions / 0 failures; targeted kondo 0 errors; live probe 5/5 relevant (Babashka), 5/5 relevant (Clojure spec), direct untruncated URLs |
| A2A server on the deck | `2c839f1` | `bb test:e2e:web` 32 tests / 337 assertions / 0 failures; live `a2a_send discover` returns the card; unauthenticated `a2a_send send` → HTTP 401; authenticated `message/send` → `submitted`, `tasks/get` → `completed`, artifact = real `/usage` reply |
| Tool-outcome ledger timing + report | `eb56e7d` | unit suite 319 / 1,479 / 0 failures (pre-search-repair state); live `git_status` event recorded `16ms, executed=true`, no argument keys; `bb scripts/tool_ledger.bb` exit 0 over the real ledger |
| MCP client for BrowserOS | `e6300b1` | focused 6 tests / 30 assertions; full unit suite 325 / 1,511 / 0 failures; targeted kondo 0 errors / 0 warnings; live initialize + initialized + 27-tool list + read-only `tabs list`, session id and tab payload withheld |
| `browser_read` registry tool | `c8c8612` | focused 5 tests / 19 assertions; full unit suite 337 / 1,557 / 0 failures; targeted kondo 0 errors / 0 warnings; live `bb scripts/probe_browser.bb`: auto-safe denial, non-HTTPS rejection, real example.com read (1,398 chars, origin-stamped), zero tab leak |
| Optional-tool gating (owner directive 2026-11-12) | `7173035` | unit 350 / 1,620 / 0 failures; advertisement lane 4 tests / 27 assertions / 0 failures (default excludes all three optional tools, opt-in advertises only what is listed, unknown names enable nothing, dispatch denies a dormant call with `:dormant`); targeted kondo 0 errors / 7 pre-existing warnings; live: both services restarted, advertised = 8 of 11 (`read_file, write_file, shell, search, git_status, telegram_send_file, web_send_file, launch_goal`), config `:tools/enable-optional` nil |

Bearer handling, stated plainly: the live A2A token is generated mode-600 at `state/a2a-token`, hygiene-allowlisted, and delivered file-only to the owner's verified DM. The authenticated roundtrip used a Babashka client that reads the token from disk, because OpenCrabs' secret-handling rule forbids putting a live bearer into a tool argument. Discovery and the unauthenticated 401 check used the real `a2a_send` tool. Both deck and Telegram services were restarted onto `10ecda3`; the agent card answers HTTP 200 live.

## 11. Method & uncertainty

- Theseus: `src/bb_agent` enumerated (79 live namespaces + `tool/` 7 + `goal/` 21, ~15.5k LOC incl. local .bak residue); tool registry read in full; secondary namespaces read at API surface (defn lists), not line-by-line.
- OpenCrabs: live runtime surface (this session's registry: 46 schema'd + ~29 lazy tools), `tools.toml` (8 dynamic, first is a bb script), skills dir (63), source tree present at `~/.opencrabs/src` — structure verified, Rust internals not audited this turn.
- Test counts (325 tests / 1,511 assertions) are from the full `bb test:bb-agent` run at `e6300b1` on 2026-11-12, and 337 / 1,557 at `c8c8612` (browser_read plus the concurrent test-roster registration), 350 / 1,618 at `4e27c48` (bb-excel), and 350 / 1,620 at `7173035` (capability gating); e2e web 32 / 337 at `2c839f1`. The probes are `bb scripts/probe_mcp.bb` and `bb scripts/probe_browser.bb`; both print protocol facts and counts only.
- §5 usage data: this instance's per-call feedback ledger + mission-control aggregates over `opencrabs.db`, pulled 2026-11-11 ~11:05 EAT. Ledger and DB disagree on some per-tool rates (both quoted where divergent); the top-20 share base is the 167,970-event ledger volume.
- No secrets appear in this document.
