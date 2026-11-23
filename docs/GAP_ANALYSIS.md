# Rich Hickey Gap Analysis — Theseus Current Ledger

> **Benchmark Policy (2026-11-22, owner directive):** Exclude `AlcaponeCoder` and `hermes-beam` from all gap analyses going forward. **The bar is OpenCrabs.** Historical pre-v1.3 lineage was cleared on 2026-11-22; this ledger tracks only the current codebase architecture, active audits, learnings, and verified receipts.

---

## 1. Codebase Audit Remediation & Honest Gates (2026-11-21)

An audit of `main` (`v1.3.1`, `b95ecfe`) uncovered six systemic defect classes hiding behind green-looking checks. Five remediation commits (`b25bf41`–`caf635c`) plus ADR `0002` (`docs/babashka-rewrite/adr/0002-codebase-audit-hardening-and-honest-gates.md`) resolved every finding under red/green TDD while keeping the owner-directed `>= 500 LOC` exemption active for this repo.

### Scope & commits landed on `main`

- **`b25bf41` (Security boundaries):** Path-traversal and symlink containment on `/api/goal-detail`, `/api/goal-control`, and `/api/artifact/<id>`; canonical `protected-secret-path?` enforcement across `file.clj`, `process.clj`, and `web_artifacts.clj`; wrapper-aware `never-auto` shell checks; SHA-256-hashed bearer tokens (`"sha256:..."`) in `state/web-sessions.edn`; cross-process `.web-pass-ro.edn` mtime revocation + live SSE heartbeat auth checks + pre-auth bounded body reads; born-`0600` private state creation; `pr-str` escape-aware secret redaction; and removal of cockpit `.innerHTML` status injection.
- **`04b2a67` (Honest lint & test gates):** Resurrected 3 nested `deftest`s (`web_test.clj`, `coordinates_test.clj`), removed all 18 `:level :off` overrides in `.clj-kondo/config.edn`, fixed all 98 warnings / 9 info notices, switched `bb lint` and `e2e.kondo-test` to `--fail-level warning`, removed unused `fswatcher` pod, unified `test:e2e:all`, updated `.github/workflows/tests.yml`, and replaced wildcard test server binds across 20 `test/e2e/` files with kernel-assigned `{:port 0 :ip "127.0.0.1"}`.
- **`b1e92f3` (Runtime, Anthropic wire & build parity):** Split/quoted FTS5 tokens in `web_search.clj`, classified HTTP `500`/`520`/`522`/`524` + peer-reset/EOF as `:infra` in `error_classifier.clj`, checked HTTP status `>= 400` before JSON decode in `provider.clj`, added full `:anthropic-compatible` wire parity, fixed `:role :user` loop nudges in `core.clj`, added `30s` HTTP timeout + 3-nil poll tolerance in `a2a_client.clj` and `[1000 600000]` ms validation in `a2a_tool.clj`, allowlisted durable state entries in `state_hygiene.clj`, and removed duplicate `bb-excel` unpacking in `script/build-binary.sh`.
- **`7a8759b` (Decomplected brain ledgers):** Excluded append-only `improvements.md` and `wins.md` from `load-brain`, indexing them via `ledger-index` in `load-brain-context` (`bb context-cost` dropped from `946071` chars to `7835` chars).
- **`caf635c` (Concurrency & storage hardening):** Serialized `approval.clj` via JVM lock + companion `approvals.edn.lock` `FileChannel/.lock` + born-`0600` atomic rename; migrated `memory.clj` from `sqlite3` CLI to parameterized transactional `pod.babashka.go-sqlite3`; wrapped `store.clj` `index-usage!` in `BEGIN IMMEDIATE` and hardened SQL `query` validation; scoped `core.clj` `provider-breaker` per provider; and bounded `@uploads` (`max-uploads 100`) with unreferenced disk eviction in `web_turn.clj`.

### Learnings & patterns

- **macOS `0.0.0.0` vs `127.0.0.1` bind collision:** Always pass `{:port 0 :ip "127.0.0.1"}` and read `(:local-port (meta stop-server))`.
- **Companion `.lock` `FileChannel` for EDN read-modify-write:** Pair a static companion lock path (`*.edn.lock`) with a process-local `ReentrantLock` around born-`0600` atomic temp-file renames.
- **`pr-str` escape-aware secret redaction:** Match across both literal and escaped boundary forms.
- **SQLite integrity via `go-sqlite3` transactions:** Parameterized queries under `BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK` eliminate escaping bugs and partial index rebuilds.
- **Separate append-only ledgers from per-turn identity:** Keep growth logs on disk behind a compact metadata index.

---

## 2. Audit Wave 2: Shared Atomic I/O, Security Boundaries & Runtime Hardening (2026-11-22)

Three Red/Green TDD commits (`c729c4b`–`e98b5f6`) plus ADR `0003` (`docs/babashka-rewrite/adr/0003-audit-wave2-atomic-io-and-runtime-hardening.md`):

- **`c729c4b` (Shared `bb-agent.atomic-io`):** Extracted born-`0600` atomic writes and `ReentrantLock` + companion-`FileChannel` locks into `src/bb_agent/atomic_io.clj` and migrated `approval.clj`, `session.clj`, `web_auth.clj`, `web_a2a.clj`, `config.clj`, `outbox.clj`, `telegram_notes.clj`, `subagent.clj`, `telegram_lifecycle.clj`, and `goal/lock.clj`.
- **`5bb49f7` (Security boundaries):** Blocked `write_file` on `protected-write-path?` (all secret paths + `~/.ssh/authorized_keys` and `~/.ssh/config`); eliminated `#=(...)` read-eval execution in `rtk.clj`; added 64KB body cap (`413`) + `405` non-POST rejection + `"UTF-8"` HMAC bytes in `notify_listen.clj`; validated `Host`/`X-Forwarded-Proto` and mtime-cached `a2a-token` in `web_a2a.clj`; blocked loopback/link-local/RFC1918/CGNAT targets in `browser_tool.clj` (GraalVM native-image safe); and disabled HTTP redirects + enforced 2MB response cap in `a2a_client.clj`.
- **`e98b5f6` (Runtime, SQLite & error handling):** Added `15s` timeouts to `telegram_delivery.clj` and `bot_commands.clj`; serialized and transacted `web_search.clj` SQLite refresh/search under `BEGIN IMMEDIATE` (fixing a concurrent `go-sqlite3` deadlock); guarded `session.clj` `list-metadata`/`load-metadata`; guarded non-JSON Telegram responses and redacted error logs / `web_http.clj` 500 messages; guarded `net_search.clj` `decode-entities` with `Character/isValidCodePoint`; and hardened `hot_reload.clj`.

### Learnings & patterns

- **GraalVM native-image reflection trap on `Inet4Address`:** Inspect `(.getHostAddress addr)` with pure lexical IP checks instead of reflection-sensitive predicates.
- **`pod-babashka-go-sqlite3` concurrent refresh deadlock:** Serialize refresh + query behind one JVM lock and a single `BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK` connection.
- **Never call bare `read-string` on user-editable config:** Reject `#=` and bind `*read-eval* false`.

---

## 3. Hierarchical Context Compaction & Persistent Checkpoints (2026-11-22)

Landed in `33eebb6` + `cee0903` + ADR `0004` (`docs/babashka-rewrite/adr/0004-hierarchical-context-compaction.md`):

- **Persistent rolling checkpoints (`state/session-compaction/<id>.edn`):** Tracks `:last-summarized-turn`, folds at most 3 tiers (`ancient` re-compressed above `2400` chars), zero LLM calls when watermark unchanged, delta-only summaries with `previous-summary` when advanced, auto-cleared on `session/reset!`.
- **Structured memory ledger:** EDN `{:summary ... :facts [...] :decisions [...] :files [...] :pending [...]}` with plain-prose fallback, merged additively with newest-tail bounded dedupe (12 items/field).
- **Turn-range provenance & verbatim drill-down:** Compacted tiers stamp `[Turns A-B]` matching 0-based indices on disk and advertise auto-safe, session-scoped `read_session_turns(from, to)` backed by `session/load-turn-range`.
- **Idempotent 1-line tool breadcrumbs & live mid-turn pruning:** `prune-old-tool-results` condenses older tool messages inside both `compression/compress` and `core.clj`'s multi-round tool loop.
- **In-turn read-only tool cache:** Caches `:ok` results for `read_file`/`search`/`git_status`/`document_read`/`read_session_turns` within a turn and invalidates on any mutating tool.
- **Model-aware dynamic budget & pure retention check:** Unified `chars-per-token = 4`; `history-budget-chars` scales from model window minus actual injected overhead; `compression/retention-report` checks fact/entity retention.

---

## 4. Mercury-Agent Safety, Economics, Integrity & Learning Upgrades (2026-11-22)

Landed across `48260af`, `bfefb69`, `699318f`, `e362eb9` + ADR `0005` (`docs/babashka-rewrite/adr/0005-mercury-agent-safety-economics-and-learning.md`):

- **Security & taint boundary (`48260af`):** `bb-agent.redact` (regex + runtime-registered literal secrets from `config.edn`) and `bb-agent.security` (turn provenance/taint tracking + `:subagent/exposure` inheritance, zero-LLM prompt-injection spotlighting, Shannon-entropy + seen-URL + shell-upload exfil detector, fail-closed model-strength tiering).
- **Provider & token economics (`bfefb69`):** Anthropic ephemeral `cache_control` breakpoints, auto-safe `discover_tools` deferred optional schema loading (`:tools/defer-optional true`), and `bb-agent.saver-mode` soft daily-budget degradation (`:off`/`:on`/`:auto` at 75% budget).
- **Turn integrity & reliability (`699318f`):** `:finish/reason` capture with sectioned-write recovery prompts on `:length`/`max_tokens` truncation, `bb-agent.turn-integrity` (execute-mode narration guard, silent-turn factual receipt synthesizer, two-stage daemon stall watchdog), and `bb-agent.crash-flag` (`state/.crash-flag.edn`).
- **Memory & self-learning (`e362eb9`):** `bb-agent.beliefs` (light-stem + synonym + negation-aware claim dedupe, `:candidate` → `:held` → `:confirmed` promotion, non-destructive `:contested` supersession excluded from `search-memories`, idempotent SQLite `status`/`evidence_count` columns) and `bb-agent.turn-signals` (zero-LLM behavioural friction cues recorded on `usage.edn`).

---

## 5. Web Deck Implicit State-Machine Audit & UX/DX/AX Hardening (2026-11-22)

Full audit (`docs/audits/webdeck-state-machines-2026-11-22.md`, ADR `0006`, commit `5f38ea0`) of all 24 state machines across `web/index.html`, `web/js/*.js` (16 modules), and `src/bb_agent/web*.clj`:

- **Pure FSM kernel & AX surface (`web/js/fsm.js`, whitelisted in `src/bb_agent/web.clj`):** `createMachine`, `createGate`, `registerMachine`, `projectAttr`, and frozen read-only `window.__THESEUS_DECK__` (`snapshot()`, `history()`, `machines()`).
- **Unified Auth/Role & Honest Transport (`state.js`, `api.js`):** Removed duplicate `post()`/`put()` in `cockpit.js` and `columns.js`; every HTTP 401 and SSE `unauthorized` syncs `auth=locked` + `conn=login required` + guarded `showModal()`; projected `html[data-role]` (`owner` | `ro`) disabling write controls in read-only mode; immediate first poll on fallback.
- **Live bugs fixed:** `Enter` bypassing edit-last mode, attachment loss on send failure, 1s SSE re-enabling in-flight approval buttons (`decidingApprovalId`), `loadOlder` corrupting `lastUserInput`, hardcoded `400ms`/`1200ms` search highlight timers (replaced by `theseus:transcript-loaded`), `media.js` undeclared `r` `ReferenceError`, `columns.js` `card()` missing `return c;` and `showModal()` `InvalidStateError`, `pip.js` `total / 60` hours bug (now `total / 3600`) and DOM scraping, `⌘K` filter wiped by SSE ticks (`reapplyFilter()`), non-delegated inspector `dblclick`, and cleartext `prompt()`/`alert()` password rotation (now `<dialog id="rotate-dialog">`).

### Current Verified Receipts (2026-11-22)

- `bb scripts/check_web_graph.bb`: `16 files, 0 missing exports, 0 unparsed forms` (`EXIT=0`).
- `bb test:web:js`: `16/16` Node JS tests (`0 fail, 0 skipped`, `EXIT=0`).
- `bb test`: `49` Clojure test blocks totaling `975` tests / `4676` assertions (`0 failures, 0 errors`), plus `16/16` Node JS tests and `bb verify:rewrite-docs` (`EXIT=0`).
- `bb lint`: `0 errors, 0 warnings` (`EXIT=0`); `bb test-roster`: `128/128`, `0 never-run` (`EXIT=0`).

---

## 6. OpenCrabs v0.6.0 Workflow Parity: Surgical I/O, Mechanical Gates, Parallel Reads & Live Subagents (2026-11-22)

Three Red/Green TDD waves (`aaf9b63`, `ff75512`, Wave 3 + ADR `0007`) closed the core workflow/tool mechanics gap identified in `docs/gap-analysis-theseus-vs-opencrabs-2026-11-22.md` with zero new dependencies:

- **Wave 1 (`aaf9b63` — Surgical Code I/O):** Added `edit_file` (exact search-replace with unique-match guard + `replace_all`, whitespace-trimmed line-block fallback, and 1-based inclusive line-range replace, enforcing `protected-write-path?` and `knowledge/guard-write!`, classified in `security/control-tools` and `turn-integrity/mutating-tools`), optional `start_line`/`end_line` on `read_file`, and optional `regex` + `glob` filtering on `search`.
- **Wave 2 (`ff75512` — Mechanical `skill_gate`, Parallel Read-Only Batching, Anthropic `:tools` Cache):** Parsed `globs:` frontmatter in `skill.clj` and wired `skill/check-skill-gate` in `tool.clj` (blocks the first matching `write_file`/`edit_file` once per turn with `:denial/source :skill-gate` and full skill body injected); parallelized eligible multi-call auto-approved read-only tool rounds via `future`s in `core.clj` while folding cache/exposure deterministically in order; marked the last tool schema with `:cache_control {:type "ephemeral"}` in `provider.clj`.
- **Wave 3 (`8c70abb` — Live `spawn_subagent` & Slim `plan` Gate):** Extended `subagent.clj` with `run-subagent!` (capacity-checked, optional temporary detached `git worktree` cleaned in `finally`, inherited parent `:security/exposure` taint, cycle-free runner resolution) and added `plan.clj` (`state/plans/<session-id>.edn`, allowlisted in `state_hygiene.clj`) with shell-verified `complete` (`verify_cmd`) and an opt-in mechanical `:plan/gate-after-tool-calls` gate in `tool.clj`.
- **Wave 4 (OpenCrabs Polish — 6 Remaining Mechanics):** Added tool-name alias/Levenshtein-1 + parameter-alias self-healing (`tool/common.clj`), harness-written `## PLAN STATE (harness)` prompt injection (`plan.clj`/`core.clj`), 20% `bounded-output-tokens` cap + `calibrate-token-report` (`compression.clj`/`provider.clj`), session sticky fallback promotion + `guard-model-leak` (`fallback.clj`/`core.clj`), `turn_signals` → `rsi/analyze` `:friction-signals` aggregation (`rsi.clj`), and `extract-bare-tool-call` + `unrun-fenced-shell?` (`turn_integrity.clj`/`core.clj`).

**Rich Hickey certification:** ~1,050 LOC of pure, zero-dependency data transformations closed the surgical-editing, skill-enforcement, parallel-read, prompt-cache, worktree-subagent, plan-gate, alias-healing, sticky-fallback, and RSI friction-signal gaps without importing OpenCrabs's 400k-LOC platform tax.

---

## 7. DuskBot-Inspired Reliability, Safety Floor, Pacing & Memory Upgrades (2026-11-22)

Eleven zero-dependency capabilities (plus PII-free failure-signature normalization) adapted from `DuskBotAI/duskbot` landed across three Red/Green TDD commits (`82d349b`, `2a2eb00`, Tier 3 + ADR `0008`):

- **Tier 1 (`82d349b`):** `bb-agent.tool-hints` (5 failure classes + `:next-step` directives + `find /` shell timeout hints wired at `tool.clj`), 6 deterministic post-turn stop gates (`turn-integrity/stop-gate-for`, max 2/turn), and authorship-verified memory taint gate (`security/memory-trust-verdict` wired in `memory/add-memory!`).
- **Tier 2 (`2a2eb00`):** `bb-agent.shell-lexer` (linear-time shell scanner ignoring quoted/heredoc data, failing closed on unsynced syntax), `bb-agent.schedule-probe` (macOS Seatbelt SBPL sandbox + stripped env + output-diff/`wakeAgent` trigger + boot-hold/daily-cap/deterministic-backoff pacing), `bb-agent.action-receipts` (declarative action-claim vs. tool-receipt auditor + 0600 monthly JSONL receipts + `failure-key` normalizer), and `bb-agent.deliverable-check` (zero-tool untrusted-document critic).
- **Tier 3 (ADR `0008`):** `bb-agent.warm-compaction` (idle-gap background compaction inside prompt-cache TTL), `bb-agent.batch-checklist` (monotonic crash-resumable restart-merge checklist), `bb-agent.memory-dream` (cross-conversation DREAM consolidation with deterministic user-quote verification), and `bb-agent.checkpoints` (bare shadow-git per-turn workspace snapshots/diffs/restores). Allowlisted `"receipts"`, `"checklists"`, `"checkpoints"` in `state_hygiene.clj`.

## 8. Zero-Daemon Native Datalog Engine (2026-11-22 — ADR `0009`)
- **Empirical Babashka SCI Finding:** `datascript/datascript 1.8.1` fails under `babashka v1.13.222` (`datascript/lru.cljc` `deftype` without `IPersistentMap`).
- **Delivered:** `src/bb_agent/datalog.clj` (zero-dependency pure-map immutable EAV/AEV/AVE store, `q` with joins/allowlisted pure predicates/`not`/aggregates, `pull` with `:*` + nested refs, mtime-cached `theseus-db` hydrator over `memory.edn`/`session-summaries.edn`/`goals/*/project.edn`). Wired end-to-end as `query_graph` in `tools.clj`, `tool.clj`, `tool/common.clj` (`safe-auto-tools`), `core.clj` (`cacheable-read-tools` for read cache + parallel read batching), `cli.clj` (`theseus datalog <query|schema>`), and `bb.edn` (`bb datalog:query`). Full 5-way DB analysis in `docs/audits/multiwriter-db-gap-analysis-2026-11-22.md`.
- **Receipts:** `bb lint` (`0 errors, 0 warnings`), `bb test-roster` (`133/133`, `0 never-run`), `bb test:bb-agent` (`443` tests / `2148` assertions, `0 failures, 0 errors`, including `5` tests / `35` assertions in `bb-agent.datalog-test`), `bb test:e2e:tools` (`16` tests / `128` assertions, `0 failures, 0 errors`), `bb verify:rewrite-docs` (exit `0`).

## 9. Security-vs-Robustness Calibration (2026-11-24 — ADR `0010`)
- **Audit:** `docs/audits/security-vs-robustness-gap-analysis-2026-11-24.md` identified 5 high-impact false-positive security heuristics that bricked legitimate coding/browsing turns.
- **Delivered:** hard-vs-advisory injection signals (`:css-hidden`/`:md-comment` spotlight-only), weak-model denylist in `strong-model?` (unblocking `infered/auto` and new frontier models), calibrated `dense-encoded-blob?` + loopback `curl -d` exemption (`security.clj`), quote-aware shell safety floor preserving `$(...)` (`tool/process.clj`), and HTTPS `#fragment` support + graceful tab-close warning (`browser_tool.clj`).
- **Receipts:** `bb-agent.security-test` + `bb-agent.browser-tool-test` (`14` tests / `83` assertions, `0 failures, 0 errors`), `bb test:e2e:tools` (`18` tests / `134` assertions, `0 failures, 0 errors`), `bb lint` (`0 errors, 0 warnings`), `bb test-roster` (`137/137`, `0 never-run`).
