# Rich Hickey Gap Analysis — AlcaponeCoder vs hermes-beam vs theseus

**Date:** 2026-08-23 · **Method:** static analysis (structure, LOC, git history, CI, test inventory). No test suites were executed — ratings are evidence-based, not run-verified.

---

## 0. The Headline

These are not three competitors. **They are three generations of the same agent runtime, one author (Moe), three technology bets.** Analyzed as rivals they make no sense; analyzed as a lineage they tell one clean story:

```
AlcaponeCoder (Apr–Jul 2026)      hermes-beam (Jun–Jul 2026)         theseus (Jul 14 2026)
Babashka port, benchmark-first    Gleam/BEAM bet, supervision-first  Babashka again, zero-dep-first
        └──── SQLite EAV datoms ────┘                                        │
                └──────────── shared DNA: EAV store + explicit approvals ────┘
```

The convergent endpoint — **one Babashka runtime + SQLite EAV datoms + explicit approvals** — is the analysis's real finding. Everything else is detail.

---

## 1. Maturity Dashboard

| Signal | AlcaponeCoder | hermes-beam | theseus |
|---|---|---|---|
| What it is | Hermes Agent, Babashka port (xharness) for Terminal-Bench 2.0 | Gleam/Erlang-OTP supervised agent runtime | Standalone Babashka agent extracted from OpenCrabs rewrite |
| Real commits | 47 | 37 touching `hermes_beam/` (10,692 / 1,548 contributors **inherited fork history** — Teknium/Nous) | 1 |
| Real contributors | 2 (Moe 34, wanhua.gu 13) | ~2 on the Gleam core | 1 |
| Active window | 2026-04-13 → 07-14 (3 mo) | 2026-06-05 → 07-03 (4 wks on Gleam core) | 2026-07-14 (day one) |
| Source LOC | 2,758 (27 modules) | 12,847 (~49 modules) | 1,887 (21 modules) |
| Largest module | codedb.clj 411 | hermes_agent.gleam **1,986** | provider.clj 215 |
| Tests | 12 files (10 clj + 2 py smoke) | 26 gleam files incl. **property tests (qcheck)** | 9 e2e files, 2,559 LOC, deterministic fake provider |
| CI | 1 workflow | 7 workflows + smoke-test action + PR template + supply-chain audit | none |
| External deps | 0 | 14 hex deps + 2 dev | **0** |
| Tags/releases | 0 | 1 (v2026.7.3) | 1 (v0.1.0) |
| Repo hygiene | ⚠ binaries committed (hermes.jar, tar.gz, test_state.db + WAL, log.bak) | ⚠ build_output.txt, invalid_messages.json, nested dup dir | ✅ clean |
| Docs | README en/zh, Next.js docs site | 30+ gap analyses, walkthrough, production.md, security | ROADMAP, PRODUCT_SPEC, TASKLIST, ADRs, CHANGELOG w/ verification claim |
| TODO/FIXME debt | 0 | 0 | 0 |

**Composite maturity (1–5, opinionated):**

| Dimension | AlcaponeCoder | hermes-beam | theseus |
|---|---|---|---|
| Core-loop hardening | 4 (benchmark-proven, exit-0 discipline) | 3 (unproven at benchmark scale) | 3 (deterministic e2e only) |
| Testing | 3 | **4** | 3.5 (e2e-first, but no CI to enforce it) |
| Ops / release discipline | 2.5 | **5** | 1.5 |
| Breadth of capability | 3 | **5** | 2 |
| Simplicity / decomplection | 3 | 2.5 (code decomplected, **repo complected**) | **5** |
| Velocity signal | slowing (last commit = handoff to theseus) | paused (last commit Jul 3) | starting |

---

## 2. Module-by-Module Feature Matrix

| Capability | AlcaponeCoder (`clj_agents/`) | hermes-beam (`hermes_beam/src/`) | theseus (`src/bb_agent/`) |
|---|---|---|---|
| Agent loop | agent.clj 208 | hermes_agent.gleam 1,986 | core.clj 100 |
| Runtime entry | harbor.clj 45 (benchmark guard) | hermes_beam.gleam 1,729 + api_server 175 + batch_runner | cli.clj 201 + daemon.clj 19 |
| LLM / providers | llm.clj 117 | hermes_client 128 + model_router 180 | provider.clj 215 (OpenAI + Anthropic + switching) |
| Usage/cost | — | **usage_pricing 1,075** | usage.clj 104 |
| State store | store.clj 133 + **codedb.clj 411 (SQLite EAV, final commit)** | hermes_state 796 + state_actor 475 + datom.gleam | session.clj 78 |
| Memory | memory 126 + memory_manager | memory_plugin 216 + curator 225 + semantic_search + cross_session 162 | memory.clj 110 (EDN + SQLite seam) |
| Tool execution | tools/ terminal·browser·webdriver·patch·multimedia·xml·system (493) | hermes_exec 605 + tools_registry 170 + wasm_executor | tool/ file·process·path·common (349) |
| Permissions | permissions.clj 52 (thin) | permission.gleam + tests | **approval.clj 117 — policy + durable pending + TG replies** |
| Subagents | delegation.clj | **subagent_supervisor 529 + babashka_workers sidecar** | — |
| Scheduling | cron.clj 57 | cron_scheduler 350 (tested) | schedule.clj 74 + daemon |
| Channels | — (benchmark focus) | telegram_gateway 282 (telega) + HTTP api (mist/wisp) | telegram.clj 117 + slack.clj 93 (polling, offset durability) |
| Skills | skill.clj 116 | skill 106 + skill_compiler | — |
| Compaction | compression.clj 72 | context_engine + token_budget | — |
| Resilience | recovery.clj | **circuit_breaker 172 + error_classifier + iteration_budget** | — |
| MCP | — | mcp_client 391 (tested) | — |
| TUI / render | pilot.clj 118 (JLine3) | kawaii_spinner | rich.clj 100 (AST → terminal/TG/Slack renderers) |

---

## 3. Per-Repo Module Maturity

### AlcaponeCoder — *benchmark-hardened core, accreting scope*
- **harbor.clj + agent loop — 4/5.** Exit-0 discipline as a fatal-bug class is real production thinking; agent/error tests exist. This is the repo's proven asset.
- **codedb.clj (SQLite EAV) — 3/5.** 411 LOC, tested, but landed the *same day* the lineage moved to theseus — it's a handoff artifact, not a settled module.
- **Browser tooling — 3/5, complected.** `tools/browser.clj` (109) *and* `tools/webdriver.clj` (87) — two overlapping browser stacks; a choice nobody made.
- **reviewer / taste / evaluator — 2/5.** Speculative modules ahead of any evidence they earn their complexity.
- **Repo sins:** committed binaries (`hermes.jar`, `bb-*.tar.gz`), a live SQLite db + WAL, `.log.bak`. Source braided with product — classic complecting.

### hermes-beam — *the breadth leader with god-module debt*
- **Supervision stack — 4/5, the BEAM payoff.** subagent_supervisor (529), circuit_breaker_actor (172), state_actor (475) — all tested. This is what the BEAM bet bought.
- **hermes_state + datom — 4/5.** Append-only EAV datom logs: state as values, identity separated from time. The most Hickey-faithful storage in the lineage.
- **hermes_agent.gleam (1,986) + hermes_beam.gleam (1,729) — 3/5.** Two god-modules braiding loop, policy, and orchestration. Both exceed a 500-LOC ceiling 3–4×. Tested, but structural debt.
- **The June pivot (honest simplification):** they *removed* the in-process Gleam Datalog engine and moved logic to an out-of-process Babashka micro-Datalog worker — admitting the two-runtime complection. That admission is the pivot that produced theseus.
- **Repo sins:** 10,692 commits / 1,548 strangers in the history, infographic/, locales/, plans/, build_output.txt, `babashka_workers/babashka_workers/` double-nesting. The CI (7 workflows, supply-chain audit, smoke action) is the best in class — on the most cluttered stage.

### theseus — *right shape, day one, no scar tissue*
- **approval.clj — 4/5.** Best permission story per LOC in the whole lineage: explicit policy, durable pending state, Telegram approval replies.
- **provider.clj + deterministic fake provider — 4/5.** OpenAI + Anthropic adapters with model switching, proven by e2e against a *fake* — tests as data, not mock soup. Hickey-grade.
- **rich.clj render AST — 3/5.** One AST, three renderers (terminal/TG/Slack). Small, composed, correct.
- **schedule/daemon — 2/5.** Youngest surface; e2e exists but no scar tissue yet.
- **Missing entirely:** compaction, subagents, skills, MCP, browser tools, circuit breaker, error classification, semantic memory. It is a *seed*, not a system.

---

## 4. Rich Hickey Decomposition — Complecting vs Decomplecting

| | AlcaponeCoder | hermes-beam | theseus |
|---|---|---|---|
| **Decomplected** | exit-0 guard as separate module; EAV store as values; zero deps | actor mailboxes (pure msg passing); EAV datom logs (identity ≠ time); logic moved out-of-process; property tests | zero deps; tool protocol seams; render AST; deterministic test provider; approvals as durable data |
| **Complected** | source + binaries in git; two browser stacks; docs site braided into agent repo; teaching series (agents/s01–s27 py) braided with implementation | **two runtimes** (BEAM + Babashka sidecar + nrepl/nextdoc sidecars); fork history = 10k strangers; god modules braid loop+policy+orchestration; repo as museum | nothing yet — it hasn't had time to sin |

**The pattern:** each repo's *code* got more decomplected while each repo's *boundary* struggled. AlcaponeCoder complected artifacts into git. hermes-beam complected runtimes and history. theseus decomplected everything except its own maturity — it simply doesn't have any yet.

---

## 5. The Gaps (What to Port Where)

**theseus ← AlcaponeCoder:**
1. `compression.clj` (72 LOC) — cheapest high-value port; long sessions die without it.
2. `harbor.clj` exit-0 discipline + benchmark harness — theseus has no proof-at-scale story.
3. Browser tool — but pick ONE stack this time.

**theseus ← hermes-beam:**
4. Circuit breaker + error classifier — port the *pattern as data*, not the actor. Babashka can express retry budgets as plain maps.
5. Subagent supervision (529 LOC) — flatten to process maps + explicit claim/complete, à la Bankai beads.
6. `tests.yml` CI — hermes-beam's best artifact; theseus's biggest hole (tests exist, nothing enforces them).
7. cron_scheduler's tested semantics (350 LOC → theseus's 74 needs the edge cases).

**hermes-beam internal debt:**
8. Split the two god modules (1,986 + 1,729) before any feature work.
9. Detach fork history and support dirs — the Gleam core deserves a clean repo.

**AlcaponeCoder:** it already shipped its keeper (codedb/EAV → theseus). Retire or freeze; harvest harbor harness on the way out.

---

## 6. Recommendation (Opinionated)

**theseus is the trunk. Bet on it, and mine the other two for parts.**

Evidence, not vibes:
1. hermes-beam's *own* June gap analysis concluded the Datalog engine should move out-of-process **to Babashka** — the BEAM runtime was already being demoted to a host for a Babashka worker. That is the architecture admitting the second runtime wasn't pulling its weight.
2. AlcaponeCoder's final commit (Jul 14) adopted the SQLite EAV datom store — the same day theseus was cut. The store is the shared DNA; theseus is where it landed.
3. All three converge on: Babashka logic · SQLite EAV state · explicit approvals. Theseus is the only one that is *only* that.

The BEAM bet bought supervision (real, tested, good) — but supervision patterns are portable as data; the platform tax (Gleam+OTP+sidecars+nrepl) is not. Hickey verdict: **port the patterns, not the platform.**

Two conditions, non-negotiable:
- **CI is theseus's first commit back** — hermes-beam proved the discipline; theseus has the tests and nothing enforcing them.
- **Zero-dep is the crown.** Every ported module must re-justify itself against "does this need to exist in-process?" or theseus becomes hermes-beam in miniature within six months.

**Risk:** theseus is a v0.1.0 with one commit. Its maturity is aspiration. The honest read: right shape × no history = unproven. The e2e discipline is the only evidence it will stay simple, and only CI makes that evidence durable.

---

## 7. Ports Executed (2026-08-23)

Section 5 items 1, 4, and 6 landed on theseus branch `port/sibling-parts` (4 commits, `6f7bd77..28095b0`):

| Port | From → To | LOC | Tests |
|---|---|---|---|
| Context compression | AlcaponeCoder `compression.clj` → `bb-agent.compression` | 113 | 6 tests / 28 assertions |
| Circuit breaker | hermes-beam `circuit_breaker_actor.gleam` (actor) → `bb-agent.circuit-breaker` (pure value) | 57 | 7 tests / 26 assertions (shared suite) |
| Error classifier | hermes-beam `error_classifier.gleam` → `bb-agent.error-classifier` (data) | 39 | ↑ same suite |
| CI enforcement | hermes-beam `tests.yml` → theseus `.github/workflows/tests.yml` | 42 | runs `bb test:e2e:all` |

Adaptations, not copies: keyword roles + `:tool/results` shapes; AlcaponeCoder's unused cheshire/http requires dropped (zero-dep preserved); OTP actor → map + explicit transitions with time as an argument; classifier pattern tables kept as data with precedence intact.

Verification: `bb test:e2e:all` exit 0 — 46 tests, 259 assertions, 0 failures (was 33/205 at baseline). Still open from section 5: harbor harness (item 2), one browser stack (3), subagent supervision (5), cron edge cases (7).

---

## 8. Addition Roadmap — what else theseus needs (2026-08-23)

Grounding fact: the ported resilience modules (`circuit_breaker.clj`, `error_classifier.clj`) currently have **no consumer** — grep confirms no reference from `core.clj` / `provider.clj` / `tool.clj`. Priority 1 closes that loop.

| # | Addition | Source | Effort | Why |
|---|---|---|---|---|
| 1 | **Retry/backoff executor** — `with-retries` consulting classifier (`retryable?`) + breaker (record/trip), capped attempts, jittered backoff; wired into `provider.clj` | hermes-beam pattern (iteration_budget + breaker) | ~45 min | Completes the resilience loop; without it the ports are data with no caller. Dead code is worse than no code. |
| 2 | **Cron edge-case tests** — steal the *test semantics* (DST/timezone, missed-run catch-up, drift, day-of-week) not the 350 LOC implementation | hermes-beam `cron_scheduler` tests | ~30 min | Cheapest maturity win; theseus `schedule.clj` (74 LOC) is its youngest surface (2/5 in §3). Tests-as-spec port. |
| 3 | **Exit-0 benchmark harness** — scripted task suite + exit-code contract as a `bb benchmark` task | AlcaponeCoder `harbor.clj` + `run_official_benchmark.py` | ~2–3 h | theseus's named deficit is "unproven at scale." This converts aspiration into evidence — AlcaponeCoder's one proven asset. |
| 4 | **Subagent supervision, flattened** — claim/complete records in the EAV store, process maps not processes | hermes-beam `subagent_supervisor` (529 LOC) → ~150 LOC | ~½ day | Every real session eventually wants delegation. Data in the existing store, no new substrate. |
| 5 | **Semantic/cross-session memory** — only after real sessions demand it | hermes-beam semantic_search + cross_session (162) | defer | `memory.clj` (110) has the seam. Adding search before memory exists to search is speculative complexity. |

**Refuse list (Hickey gate):** MCP client (391 LOC, protocol churn, no consumer), browser tooling (what rotted AlcaponeCoder — revisit only with a concrete need), `usage_pricing` beyond tables (theseus `usage.clj` 104 is right-sized), wasm_executor / dialectic / evolutionary / kawaii_spinner (museum pieces).

**Order:** 1 → 2 → 3 → 4 → (5 deferred). Items 1+2 are one sitting.

## 9. Roadmap Items 1–4 Executed (2026-08-23, later session)

Branch `port/sibling-parts`, commits `641bb6b` → `fa6aab9` (5 commits). Suite: **61 tests, 325 assertions, 0 failures** (baseline before this work: 46/259). Item 5 (semantic memory) remains deferred per §8.

| # | Delivered | Shape | Proof |
|---|---|---|---|
| 1 | `bb-agent.retry` (91 LOC) + wiring via `complete-retrying` in `core.clj` | pure opts+thunk executor; classifier decides retry, breaker records retryable failures only; clock/sleep/rand injectable; shared per-provider breaker atom; `:retry` config map | 9 tests / 34 assertions incl. e2e: two 503s absorbed, third call succeeds through real HTTP |
| 2 | `bb-agent.cron` (83 LOC) + edge suite | hermes-beam expression matrix + `java.time` DST behavior: spring-forward gap never matches, fall-back slot occurs twice (25-hour day), catch-up windows, wall-clock-anchored drift | 6 tests / 32 assertions |
| 3 | `bb benchmark` (117 LOC harness) | 4 scenarios x 3 iters, offline (fake provider + flaky loopback); exit code = verdict | all green; retry-absorb ~385ms mean vs ~44ms plain-turn — resilience cost, measured |
| 4 | `bb-agent.subagent` (108 LOC) | 529-LOC OTP supervisor → pure record ledger; id-or-record handles; capacity as data; `state/subagents.edn` shell | 7 tests / 31 assertions incl. subprocess file round-trip |

**Adaptation notes.** Retry executor is a *caller* for the previously-dead breaker/classifier ports — the resilience loop is closed. Cron port kept the matcher and dropped the actor/tick machinery (same verdict as §4's Datalog finding). Supervisor port keeps pending/claimed/completed records and max_workers-as-predicate; mailboxes and UDS sockets did not survive the translation because nothing here needs them.

**Process findings (recorded to memory).** (1) Parallel `edit_file` calls against the same file race — one lands, one reports success without effect; always serialize edits per file. (2) Generic helpers over functions with heterogeneous return shapes (`spawn`'s `[ledger record]` pair vs ledgers) corrupt state — `spawn!` initially persisted the pair; homogeneous interfaces or explicit destructuring at the seam. (3) A docstring ending in `.)` closes the whole `ns` form, orphaning `(:require ...)` — sci's "Unable to resolve symbol" pointed at the require, not the docstring.
- **2026-08-23 CI exposure catch:** first real CI run exposed env coupling in theseus's ORIGINAL tools e2e (2 tests assumed `~/.opencrabs-bb` exists — true on dev box, false on fresh runner). Fixed by redirecting `bb-agent.config/home` to temp dirs, the same `with-redefs` pattern an adjacent test already used. Local proof: suite green with `~/.opencrabs-bb` moved aside. The CI port paid for itself on run one.

## 10. Delivery Record (2026-08-23, final)

PR #2 (`port/sibling-parts` → `main`) merged as `7609b36` (merge commit — preserves the 14-commit bisectable history and every SHA cited above). CI: `Babashka e2e tests → success` on `d37fd26` (run 32618939444), full 16-suite `bb test:e2e:all`. All six ported modules verified present on `main` via `git ls-tree`. Issue #1 closed by the merge (`Fixes #1` in PR body). Branch deleted post-merge (recoverable from the merge commit). Desktop workspace: this report + synced `theseus` clone on `main`; source clones (`AlcaponeCoder`, `hermes-beam`) trashed after verification that both were pristine and fully upstream.

**End state:** theseus went from 21 modules / 1,887 LOC / 33 tests to 27 modules / ~2,600 LOC / 61 tests + benchmark harness + CI enforcement — zero new dependencies, every module under 120 LOC. Item 5 (semantic memory) remains deferred until session history exists to search.

**Release:** `v0.2.0` tagged on `7609b36` and published (2026-08-23 04:58 UTC) — https://github.com/moneyacademyKE/theseus/releases/tag/v0.2.0

## 11. Item 5 Execution Record (2026-08-23, later)

Roadmap item 5 (semantic memory) executed as PR #4 (`feat/semantic-memory`, commit `c46fa70`): hermes-beam's `semantic_search` + `cross_session_search` ported as `bb-agent.semantic-memory` (179 LOC).

**Concurrent-writer incident, resolved:** the daemon's ~30-min recovery cron misread a provider-stalled turn (0-length response) as dead and ported the same module in parallel mid-turn (hash-embedding cosine variant, wired into core/cli, uncommitted, with a HANDOFF note). Merged per user decision: its module shell + core/cli wiring + config gate + CLI commands; BM25/IDF with age decay replaced its cosine scorer (token overlap with no IDF); auto re-index after each completed turn replaced manual-only indexing. Its empty `port/semantic-memory` branch deleted; handoff note absorbed into memory and trashed.

**Key port finding:** hermes-beam's cross-session design needs two network services (LLM summarizer + embeddings API); its own test silently dodges both. The theseus port keeps the session-linkage/ranking/injection essentials and injects both as functions — deterministic defaults, zero deps, offline.

**Verification:** 12 new tests / 35 assertions; full suite 73 tests / 360 assertions, exit 0; CI run 32620312885 `success` (first run). Notable bug caught by tests: BM25+ idf numerator sign (`(- n f 0.5)` vs `(+ (- n f) 0.5)`) — negative idf exactly when df = N (the norm in tiny corpora), silently emptying all results.

**Also fixed here:** v0.2.0 shipped with its CHANGELOG section still headed `Unreleased` — the rename commit `f57a349` from the release turn never existed (signal-6 batch crash; push receipt trusted was not real). PR #4 stamps `## v0.2.0 - 2026-08-23` properly.

**Delivered:** PR #4 merged as merge commit `b1c5f40` (2026-08-23 05:36 UTC), `c46fa70` preserved on main; issue #3 auto-closed via `Fixes #3` one second after merge; branch deleted; local clone synced. theseus end state: 28 modules, ~2,780 LOC, 73 tests / 360 assertions, CI enforced — roadmap items 1–5 all complete.

**Release:** `v0.3.0` tagged on `1176f7e` (CHANGELOG stamped *before* tagging this time — the release names itself in its own CHANGELOG; push verified via `ls-remote`, not receipt). Published 2026-08-23 06:33 UTC: https://github.com/moneyacademyKE/theseus/releases/tag/v0.3.0 — release notes kept at `~/.opencrabs/projects/theseus/release-v0.3.0.md`. The three-repo arc is closed: analysis → ports → roadmap 1–5 → v0.2.0 → v0.3.0.

## §13 — V2 Delivery Record (v0.4.0, 2026-08-29)

| Item | Result | Proof |
|---|---|---|
| PR #6 merged | `b783e0e` merge commit (parents `f09f98d` + `358fc0a`) | mergeable CLEAN, CI green pre-merge |
| Issue #5 | auto-closed 01:18:10Z | `Fixes #5` in PR body |
| CHANGELOG stamp | `a2fc2ba`, BEFORE tag | remote==local via ls-remote (v0.2.0 lesson applied) |
| Tag `v0.4.0` | on `a2fc2ba`, `^{}` deref confirms | ls-remote, not receipts |
| Release | published 01:18:59Z, final, target main | supported fields only (no isLatest) |
| Suite | 82 tests / 391 assertions / exit 0 | 16 suites, measured by awk from run output |

Process notes: PR #6 was created as a draft and the "ready" state was reported in prose without the `gh pr ready` call — the merge attempt caught it. Rule: state changes are tool calls, not sentences. Sub-agent schedule.clj refinement verified against both cron suites before commit (`1a066ee`); the fallback honesty fix (`finish-turn` prefers `:fallback/served-by`) was assertion-driven, not speculative.
