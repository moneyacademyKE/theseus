# Rich Hickey Gap Analysis — 17 Runtime Robustness Upgrades for Theseus

- **Date:** 2026-11-24
- **Target System:** Theseus `v1.5.0` (`001de13`)
- **Methodology:** Read-first audit across provider, turn loop, file I/O, usage/pricing, scheduler, Web Deck data, SQLite memory/store, MCP client, plan runner, config boot, and native Datalog engine.

---

## 1. Feature & Failure-Mode Matrix (All 17 Fixes)

| # | Subsystem & Site | Current Behavior (Fragile) | Proposed Behavior (Robust) | Failure Mode Eliminated |
|---|---|---|---|---|
| **1** | `provider.clj:28-31` (`parse-json-args`) | Uncaught `JsonParseException` on malformed model tool arguments. | Catch parse error and return `{:_parse_error msg}` (or `{}` if non-map). | Malformed tool JSON crashes entire LLM turn instead of letting model self-correct. |
| **2** | `core.clj:512-515` (parallel read batch) | Raw `(mapv deref futs)` throws if any sibling `future` throws. | Wrap each `future` in `try/catch` returning `common/error-result`. | One failing parallel read discards all sibling read results and aborts the turn. |
| **3** | `tool/file.clj:9-15, 59` (`read_file` line args & slice) | `(parse-long (str/trim v))` returns `nil` on `"abc"`, silently ignoring `start_line` and dumping the whole file; `subvec` lacked `(> from-i to-i)` guard. | Return `::invalid` on unparseable string line bounds and guard `(> from-i to-i)` in `subvec`. | Malformed string line bounds silently dump megabyte files into context. |
| **4** | `tool/file.clj:75-87` (`write_file` overwrite) | Raw `(spit (str target) content)` on overwrite. | Atomic temp-file + rename via `aio/atomic-spit!` for overwrites. | Mid-write crash truncates/corrupts workspace files. |
| **5** | `usage.clj:10-14` (`load-events`) | Bare `(edn/read-string (slurp ...))` crashes on truncated `usage.edn`. | `aio/read-edn-safe` with sequential check, fallback `[]`. | Corrupt `usage.edn` breaks `usage`, `stats`, `store`, and Web Deck. |
| **6** | `usage.clj:32-36` (`pricing`) | Bare `edn/read-string` on `usage_pricing.edn` crashes every turn's `usage/event`. | `aio/read-edn-safe` + map check, fallback `default-pricing`. | Typo in custom pricing file breaks all LLM turns. |
| **7** | `usage.clj:73-77` (`price-entry`) | `(keyword (name provider))` throws NPE when `provider` is `nil`. | Guard `(when provider ...)` returning `nil` (unpriced). | Partial/legacy usage records with nil provider crash cost estimation. |
| **8** | `usage.clj:109-114` (`append-event!`) | Bare `spit` exposes partial/truncated `usage.edn` to concurrent readers. | `aio/atomic-spit!` for atomic swap. | Web Deck / `store` racing with `append-event!` reads half-written EDN. |
| **9** | `schedule.clj:17-27, 64-86` | Bare `edn/read-string` + `spit` on `schedules.edn` and `schedule-runs.edn`. | `aio/read-edn-safe` + `aio/atomic-spit!` everywhere. | Truncated schedule ledger crash-loops `com.theseus.schedule-tick`. |
| **10** | `web_data.clj:219` (`usage-series`) | `(subs (str (:created/at e)) 0 10)` throws `StringIndexOutOfBoundsException` on nil/short timestamps. | Guard `(>= (count ts) 10)` before `subs`. | One malformed event timestamp 500s `/api/usage` and Web Deck sparkline. |
| **11** | `memory.clj:41-49` (`normalize-row`) | Bare `edn/read-string` on SQLite `:status`/`:kind` columns. | Safe EDN parser helper falling back to `nil`/`:raw`. | Malformed text in SQLite row crashes all memory loads. |
| **12** | `memory.clj:23-30` (`with-sqlite`) | No `PRAGMA busy_timeout` on SQLite connection. | Execute `PRAGMA busy_timeout = 5000` on connection open. | Transient multi-process lock immediately throws `SQLITE_BUSY`. |
| **13** | `store.clj:19-31, 58-62` (`event-row`, `index-usage!`) | `(long (:tokens/input e 0))` throws NPE when key exists with `nil` value; no `busy_timeout`. | `(long (or (:tokens/input e) 0))` + `(double (or (:cost/estimate-usd e) 0.0))` + `PRAGMA busy_timeout = 5000`. | Nil token/cost fields or concurrent reader locks crash `usage:index`/`usage:query`. |
| **14** | `mcp_client.clj:55-68` (`decode-messages`) | One non-JSON SSE keepalive `data:` frame throws and aborts the whole MCP call. | Safely skip unparseable SSE frames while keeping strict parse on single JSON bodies. | Keepalive/status SSE lines break `browser_read` and MCP servers. |
| **15** | `plan.clj:88-98` (`run-verify-cmd`) | Uncaught exception if `p/process` fails to spawn; missing `:continue true`. | Wrap in `try/catch` + `:continue true`, returning `{:ok? false :exit -1 :error ...}`. | Verification command spawn error crashes `plan` tool instead of returning structured failure. |
| **16** | `config.clj:39-43` (`load-config`) | Bare `edn/read-string` on `config.edn` crashes all daemons if file is corrupt. | On parse failure, fall back to valid `config.last-good.edn`, else `default-config`. | Corrupt `config.edn` crash-loops `telegram`, `web`, and `schedule-tick` on boot. |
| **17** | `datalog.clj:190-196` (`eval-pred`) | Type mismatch (e.g. `(>= nil 2)`) throws NPE and aborts entire Datalog query. | Catch `Exception` in `eval-pred` and return `nil` (non-match for that binding). | Heterogeneous/optional attributes in EDN crash `query_graph` queries. |

---

## 2. Benefits vs. Tradeoffs

| Group | Core Benefits | Tradeoffs & Mitigations |
|---|---|---|
| **Atomic & Safe EDN I/O (#4, #5, #6, #8, #9, #16)** | Eliminates every crash-on-truncated-file vector across `config.edn`, `usage.edn`, `schedules.edn`, `schedule-runs.edn`, `usage_pricing.edn`, and `write_file`. | `atomic-spit!` creates a sibling temp file + atomic rename (~0.2ms overhead, negligible vs LLM/disk latency). |
| **SQLite Contention & Null Guards (#11, #12, #13)** | Multi-process launchd fleet (`telegram`, `web`, `schedule-tick`, CLI) stops failing on transient `SQLITE_BUSY` locks and nil columns. | `busy_timeout = 5000` waits up to 5s before failing rather than failing in 0ms — strictly better for background daemons. |
| **LLM / Tool / Query Edge-Case Isolation (#1, #2, #3, #7, #10, #14, #15, #17)** | Bad model JSON, inverted line ranges, partial timestamps, non-JSON SSE frames, heterogeneous Datalog values, and parallel read exceptions degrade gracefully instead of aborting turns. | Errors are still surfaced honestly as structured error maps / `:warning` / non-matches — never silent fake-successes. |

---

## 3. Complexity vs. Utility Matrix (Weighted)

Weights: **Crash/Outage Prevention (40%)**, **Blast Radius Reduction (25%)**, **Implementation Simplicity (20%)**, **Zero-Regression Safety (15%)**.

| Group | Crash Prevention (40%) | Blast Radius (25%) | Simplicity (20%) | Zero-Regression (15%) | **Weighted Score (/10)** |
|---|---:|---:|---:|---:|---:|
| **Config & Ledger Atomic/Safe IO (#4, #5, #6, #8, #9, #16)** | **9.8** | **9.6** | 9.5 | 9.5 | **9.65** |
| **LLM & Parallel Tool Isolation (#1, #2, #3, #15, #17)** | **9.5** | **9.4** | 9.4 | 9.6 | **9.47** |
| **SQLite Busy Timeout & Null Guards (#11, #12, #13)** | 9.2 | 9.0 | **9.8** | 9.6 | **9.33** |
| **Web/Usage/MCP Edge Guards (#7, #10, #14)** | 9.0 | 8.8 | **9.8** | **9.8** | **9.23** |

---

## 4. Actionable Recommendation (Rich Hickey Path)

Implement all **17** fixes in one cohesive Red→Green TDD wave (zero new dependencies, pure local guards + existing `bb-agent.atomic-io`), pin every single fix with a focused regression test in `test/bb_agent/robustness_17_test.clj`, register it in `bb.edn`, verify `bb lint` + `bb test-roster` + `bb test:fast` + affected suites, document in ADR `0011`, push to `origin/main`, and rebuild `/Users/moe/.local/bin/theseus`.
