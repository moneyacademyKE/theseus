# Rich Hickey Gap Analysis — Wave 2: 17 More Runtime Robustness Upgrades

- **Date:** 2026-11-24
- **Target System:** Theseus `v1.5.0` (`b3b9ce2`)
- **Scope:** Second read-first sweep across namespaces not covered in Wave 1 (ADR `0011`): `cron`, `semantic_memory`, `outbox`, `skill`, `tool/file` (`edit_file`/`read_file`), `batch_checklist`, `web_search`, `web`, `retry`, `approval`, `memory_dream`, `compression`, `fallback`, and `web_auth`.

---

## 1. Feature & Failure-Mode Matrix (17 New Fixes)

| # | Subsystem & Site | Current Behavior (Fragile) | Proposed Behavior (Robust) | Failure Mode Eliminated |
|---|---|---|---|---|
| **1** | `cron.clj:35-36` (`part-matches?`) | `*/0` runs `(mod % 0)` → `ArithmeticException: Divide by zero`. | Guard `(and n (pos? n) ...)`. | Malformed cron `*/0 * * * *` crashes the scheduler daemon. |
| **2** | `semantic_memory.clj:92-102` | Bare `edn/read-string` + `spit` on `state/session-summaries.edn`. | `aio/read-edn-safe` (map-checked, fallback `{}`) + `aio/write-private-file!`. | Truncated summaries file crashes every LLM turn's `attach-context`. |
| **3** | `outbox.clj:89-95` (`mark-attempt!`) | Bare `(edn/read-string (slurp f))` throws if file was concurrently drained or corrupted. | `aio/read-edn-safe` returning `0` when missing or non-map. | Race between outbox send failure and drain crashes Telegram delivery. |
| **4** | `skill.clj:96-100` (`discover-skills`) | One malformed `SKILL.md` throws and aborts discovery of all skills in the directory. | `keep` with per-file `try/catch` so one bad skill is skipped cleanly. | Single broken skill file hides every other skill from `/skills` and prompts. |
| **5** | `tool/file.clj:249` (`edit_file`) | Raw `(spit (str target) updated)` on overwrite. | Crash-safe `aio/write-atomic-file!`. | Mid-edit crash truncates/corrupts edited source files. |
| **6** | `tool/file.clj:33-53` (`read_file`) | Slurps binary files (`.png`, `.sqlite`, `.bin`) raw into UTF-8 garbage. | Reuse `binary-file?` guard to refuse NUL-byte blobs cleanly. | Accidental `read_file` on binary file floods LLM context with garbage. |
| **7** | `batch_checklist.clj:57-60` (`load-checklist`) | Bare `(slurp (str p))` without `try/catch` if file vanishes or errors concurrently. | Wrap in `try/catch` returning `nil`. | Concurrent checklist delete/race crashes checklist resume. |
| **8** | `web_search.clj:53-58` (`session-row`) | Assumes `edn/read-string` yields a sequence of maps; non-sequential EDN breaks `turn-texts`. | Coerce `(if (sequential? raw) (filter map? raw) [])`. | Corrupt session EDN breaks FTS5 search index refresh. |
| **9** | `web.clj:400` (`-main` port parse) | `(Integer/parseInt ...)` throws on blank/invalid `THESEUS_WEB_PORT`. | Safe `parse-port` helper clamping to `1..65535` with fallback `8787`. | Bad env var crashes Web Deck boot. |
| **10** | `web.clj:374` (`api-routes` 500 body) | Returns `{:error (str (.getMessage e))}` — leaks raw exception/paths or empty `""` on nil message. | Return `{:error "internal-error"}` consistently with `web_http.clj`. | Nil message or unredacted internal path leakage on API 500s. |
| **11** | `retry.clj:46-52` (`backoff-ms`) | Large `attempt` (>= 64) overflows `Math/pow`; negative jitter/base can yield negative sleep → `Thread/sleep` throws `IllegalArgumentException`. | Cap exponent `(min 30 (max 0 (dec (long attempt))))` and clamp result `(max 0 ...)`. | Extreme retry counts or bad `:retry` config crash `Thread/sleep`. |
| **12** | `retry.clj:80-114` (`with-retries`) | `max-attempts <= 0` or nil `:sleep` misbehaves/throws NPE. | Clamp `max-attempts` to `(max 1 ...)` and guard `(:sleep opts)`. | Misconfigured `:retry` map crashes provider calls. |
| **13** | `approval.clj:90-108` (`waiting-approver`) | Throwing `notify` or `on-expire` crashes the turn and leaves pending approval orphaned. | Wrap `notify` and `on-expire` in `try/catch`. | Transient Telegram/UI notify hiccup wedges approvals and kills the turn. |
| **14** | `memory_dream.clj:82-92` (`run-dream`) | `(double (:confidence it))` throws `ClassCastException` on string `"high"`; non-sequential bucket value throws/splits string. | Safe numeric coercion + `(if (sequential? v) (filter map? v) [])`. | Slightly off-schema LLM DREAM JSON crashes overnight consolidation. |
| **15** | `compression.clj:286-301` (`summarize-middle`) | Non-map elements in `middle` or nil/non-string return from `call-llm-fn` break downstream string ops. | Filter `(filter map? middle)` and coerce `(str (or (call-llm-fn prompt) ""))`. | Empty/nil summarizer response crashes compaction. |
| **16** | `fallback.clj:100-120` (`try-chain`) | Nil/non-map step in `steps` or exception with nil `ex-message` produces broken `:fallback/tried` entries. | Filter `(filter map? steps)` and fallback `(or (ex-message e) (.getName (class e)))`. | Missing error reason or malformed fallback entry obscures diagnostics. |
| **17** | `web_auth.clj:157-166` (`load-sessions!`) | Non-map EDN (e.g. `42` or `[]`) in `web-sessions.edn` crashes `(into {} (remove ...) raw)` on boot. | Guard `(if (map? raw) raw {})` and `(map? v)` inside `remove`. | Corrupt non-map `web-sessions.edn` crash-loops `com.theseus.web`. |

---

## 2. Benefits vs. Tradeoffs

| Group | Core Benefits | Tradeoffs & Mitigations |
|---|---|---|
| **Daemon & Boot Guards (#1, #2, #3, #8, #9, #10, #17)** | Eliminates boot/tick crash-loops in `com.theseus.web`, `com.theseus.telegram`, and `com.theseus.schedule-tick`. | Bad files/ports fall back safely to clean defaults instead of panicking; valid behavior is 100% unchanged. |
| **Tool, Skill & File Safety (#4, #5, #6, #7)** | Atomic `edit_file`, binary-safe `read_file`, fault-isolated skill discovery, and race-safe checklists. | `read_file` refuses NUL-byte binaries (directing the LLM to `document_read` or `shell`, matching `search`). |
| **LLM Resilience Primitives (#11, #12, #13, #14, #15, #16)** | Retry math never overflows, approvals survive notify errors, DREAM/compaction/fallback survive off-schema LLM outputs. | All failures still surface as structured errors/counts rather than silent data loss. |

---

## 3. Complexity vs. Utility Matrix (Weighted)

Weights: **Crash/Outage Prevention (40%)**, **Blast Radius Reduction (25%)**, **Implementation Simplicity (20%)**, **Zero-Regression Safety (15%)**.

| Group | Crash Prevention (40%) | Blast Radius (25%) | Simplicity (20%) | Zero-Regression (15%) | **Weighted Score (/10)** |
|---|---:|---:|---:|---:|---:|
| **Daemon & Boot Guards (#1–#3, #8–#10, #17)** | **9.8** | **9.5** | 9.5 | 9.6 | **9.64** |
| **LLM Resilience Primitives (#11–#16)** | **9.4** | **9.3** | 9.6 | 9.7 | **9.46** |
| **Tool, Skill & File Safety (#4–#7)** | 9.3 | 9.2 | **9.7** | 9.5 | **9.39** |

---

## 4. Actionable Recommendation (Rich Hickey Path)

Implement all **17** Wave 2 fixes via Red→Green TDD in `test/bb_agent/robustness_wave2_test.clj`, register in `bb.edn`, verify `bb lint` + `bb test-roster` + `bb test:fast` + affected suites, document in ADR `0012`, and commit cleanly on `main`.
