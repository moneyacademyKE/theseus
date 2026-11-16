# Theseus iteration-speed audit

**Date:** 2026-11-16, EAT 06:00–06:50
**Scope:** developer experience (DX), agent experience (AX), and user-facing feedback loops around the Theseus app.
**Method:** source inspection plus timed native commands; no product code changed.

## Executive verdict

The biggest iteration killer is not Babashka or vanilla JavaScript. It is **feedback that is expensive, ambient, and split across several registries**. The agent pays roughly 235k estimated tokens of always-injected brain context, the full e2e gate takes 97.67s in a hermetic home, and the CI workflow runs only one aggregate command that omits lint and the fast Node web tests. Meanwhile, the running services use a different checkout from this audit worktree.

The best move is a three-lane loop:

1. **Fast lane:** lint, pure/unit tests, Node web tests, and module-graph checks.
2. **Integration lane:** focused service/e2e suites under a disposable `THESEUS_HOME`.
3. **Release lane:** the complete hermetic graph and live/browser probes.

Do not parallelize blindly. First make each lane deterministic and make one manifest define what each lane owns.

## Baseline evidence

| Surface | Measured result | Meaning |
|---|---:|---|
| Babashka smoke | 0.02s, exit 0 | Runtime startup is not the bottleneck. |
| `bb lint` | 1.26s, exit 0; 0 errors | Static feedback is cheap. |
| `bb test:goal` | 6.06s, exit 0; 96 tests / 482 assertions | Useful focused lane. |
| `bb test:bb-agent` | 34.14s, exit 0; 373 / 1,749 | Main unit lane is already expensive. |
| `bb test:e2e:web` | 12.79s, exit 0; 32 / 337 | Valuable focused integration lane. |
| `bb test:e2e:telegram` | 37.82s, exit 0; 71 / 362 | Large, process-heavy integration lane. |
| Node web tests | 0.22s, exit 0; 11 passing | Excellent fast feedback, currently not wired into a task or CI. |
| `bb test:e2e:all` with live home | 72.24s, exit 1 | Failed because ambient `state/hot-reload.log` violated state hygiene. |
| `bb test:e2e:all` with temp home | 97.67s, exit 0 | The full graph is healthy when hermetic; the live home is the contaminant. |
| Web module graph checker | 0.03s, exit 0 | Cheap seam; should be a first-class fast gate. |
| Test roster | 0.07s, 121/121 assigned | Accounting is healthy, but the registry remains manual. |
| Brain context cost | 940,301 chars; ~235,075 estimated tokens | AX pays a massive fixed context tax every turn. |

Counts are receipts from the commands run in this audit. The token figure is the repository's own `chars / 4` estimate, not a provider tokenizer.

## Ranked bottlenecks

### 1. Always-on brain ledgers are an AX latency tax

`bb context-cost` reports 940,301 always-injected characters. `improvements.md` is 766,807 chars (81.8%); `wins.md` is 165,817 (17.7%). Together they are 99.5% of the measured identity payload, yet they are append-only ledgers rather than per-turn operating instructions.

**Why it slows iteration:** every task starts with a huge context before source inspection, reducing useful context, increasing compaction pressure, and making agents re-parse historical noise. It also makes the cost of a trivial question resemble the cost of a code change.

**Simplest cut:** keep the ledgers durable but queryable; inject a small current summary plus relevant slices, and run `context-cost` as a budget check. Do not delete history. This removes repeated reading without adding a memory framework.

### 2. The full gate mixes hermetic integration with the live machine

`test:e2e:all` reaches 48 dependencies from a 58-task e2e surface. It took 97.67s with a disposable home and failed in 72.24s against the live home because `state/hot-reload.log` was present. The failure is environmental, not product behavior.

**Why it slows iteration:** a developer can spend more than a minute to learn that a launchd service wrote a log into the test home. Worse, a green disposable run and a red live run answer different questions but share one command name.

**Simplest cut:** make the default full gate always use a temporary `THESEUS_HOME`; move live-state hygiene to an explicit `test:host-state` check. Add `test:fast` and `test:changed` so ordinary edits do not pay the release gate.

### 3. Test/task ownership is duplicated across a 694-line `bb.edn`

The repository has 94 task entries, 58 e2e task names, 121 test files, and 92 `run-tests` occurrences. A suite can be represented in `:requires`, a `run-tests` vector, an aggregate `:depends` list, and sometimes a nested aggregate task. Static analysis sees 47 e2e tasks reachable from `test:e2e:all`; the remaining e2e names are hidden inside grouped task bodies.

**Why it slows iteration:** adding or moving one test requires editing multiple lists. A green roster proves naming, not that the right aggregate task reaches the suite. This is bookkeeping complexity, not test coverage.

**Simplest cut:** one data manifest of namespace → tier/tag, with a tiny runner generating focused and aggregate tasks. Keep explicit groups such as Telegram and web, but derive their membership once. No test framework migration required.

### 4. CI is not testing the same contract as local development

`.github/workflows/tests.yml` has one test invocation: `bb test:e2e:all`. It does not run `bb lint`, `bb test:goal`, or `node --test test/web/*.test.mjs`. The workflow also installs Babashka and clj-kondo with network retries on every run; clj-kondo is not cached. The current CI history contains repeated failures in the real-PDF test because the workflow installs no `pdftotext`/`mutool` reader, while the committed fixture asserts that an external reader exists.

**Why it slows iteration:** failures arrive after a remote run rather than in the cheapest local lane, and the same red PDF failure has repeated across runs. Retry logic makes infrastructure failures slower without making the dependency contract explicit.

**Simplest cut:** make CI run the same fast lane first, install or deliberately disable the PDF reader contract, cache all pinned tools, and keep the hermetic full lane afterward. A system dependency should be an explicit CI step or the test should be fixture-only.

### 5. Browser correctness still depends on manual probing

`test/web/` has 11 Node tests that complete in 0.22s, but `git grep` finds no `node --test` task in `bb.edn` or the workflow. `scripts/check_web_graph.bb` validates import/export shape in 0.03s, but it is not a real browser. The app has already had browser-only failures: missing static whitelist entries and a missing named export both survive per-file syntax checks.

**Why it slows iteration:** the fastest client tests are invisible to the normal gate, while the highest-value failures are discovered by manual browser sessions after a change has accumulated.

**Simplest cut:** wire the Node tests and graph checker into `test:fast`; add one authenticated browser smoke for boot, login, one push, and one write lane. Reuse the existing browser surface rather than importing a UI framework.

### 6. Hot reload restarts too much, too often

`src/bb_agent/hot_reload.clj` polls every 1,000ms. Any change under `src/`, `scripts/`, `config.edn`, or `skills/` restarts **both** Webdeck and Telegram; `web/` restarts Webdeck. There is no debounce window, and the eight launchd plists duplicate absolute `/Users/moe/theseus` and `/Users/moe/theseus/theseus` paths.

**Why it slows iteration:** editing an unrelated source file interrupts both long-lived surfaces, resets live state, and makes a harmless save look like a transport failure. A one-second poll also adds churn while a multi-file edit is in progress.

**Simplest cut:** classify paths by actual service ownership, debounce a burst of writes, and make the restart plan observable before kicking launchd. Keep the watcher; narrow its blast radius.

### 7. Process boundaries dominate the Telegram lane

The test tree contains roughly 66 process/shell boundaries and 35 fresh `bb` subprocess mentions. The Telegram lane takes 37.82s for 71 tests, while many scenarios are deliberately testing process, filesystem, or retry behavior.

**Why it slows iteration:** every subprocess pays startup and environment setup, and the test output is noisy with intentionally simulated failures. The suite is valuable, but it is the wrong default for a one-function edit.

**Simplest cut:** expose stable focused tasks by capability (`delivery`, `media`, `commands`, `voice`) and reserve the aggregate Telegram lane for integration changes. Share only fixture construction, not mutable global state.

### 8. Several production modules breach the repository's own 500-line ceiling

Measured largest files include `knowledge.clj` (1,521 lines), `goal_bridge.clj` (888), `rsi_learn.clj` (825), `coordinates.clj` (803), `brief.clj` (675), `preflight.clj` (521), and `web.clj` (449). The web client `cockpit.js` is 496 lines; the web e2e test is 1,119 lines.

**Why it slows iteration:** navigation, review, merge conflict resolution, and agent context selection all get worse as unrelated seams share one file. Large tests also hide which boundary a failure belongs to.

**Simplest cut:** split only at existing seams—types/data, evaluator, I/O boundary, and presentation—while keeping public names stable. Do not perform a heroic rewrite; each split should shorten one feedback path and land with a focused gate.

### 9. Retired policy machinery still taxes the code and agent surface

The active home has no retired executable policy artifact and config has `:policy {:enabled false}`, but this audit captured the pre-retirement state: the policy evaluator, candidate config, and policy suite were subsequently removed.

**Why it slows iteration:** agents see a live-looking policy contract that the runtime no longer uses, and every policy-related change carries dead compatibility surface and test noise.

**Simplest cut:** choose one truth: archive the retired evaluator/tests/docs, or explicitly reintroduce it as an opt-in experimental module. Given the owner's removal decision, archival is the simpler path; preserve fixtures/history outside the active gate.

### 10. The running services and the editing checkout are separate worlds

The audit worktree is `/Users/moe/theseus/policy-bk-1f32` on `fix/policy-bk-1f32`. The main service checkout is `/Users/moe/theseus/theseus` on `main`; launchd plists point there, while `THESEUS_HOME` points at `/Users/moe/theseus`. Both checkouts were clean when inspected, but a local edit in the audit worktree is not a production edit.

**Why it slows iteration:** an agent can verify code in one checkout and observe a service running another. This is exactly how “fixed locally” and “still broken in the deck” become simultaneously true.

**Simplest cut:** expose one canonical repo-root command (`bb doctor` or `bb ui status`) that prints checkout, branch, commit, service pids, and service roots. Normalize launchd paths from one configured root; fail loudly when the current checkout is not the service checkout.

## Recommended sequence

1. **Make the gates honest:** hermetic `THESEUS_HOME`, explicit host-state check, fix the CI PDF dependency, and run lint + Node tests in CI.
2. **Create the fast lane:** wire `bb lint`, `test:goal`, targeted `test:bb-agent` namespaces, `node --test`, and `check_web_graph.bb` behind one command.
3. **Replace registry duplication:** derive `bb.edn` test tasks from one namespace/tier manifest.
4. **Shrink AX context:** summary-first brain injection with on-demand ledger slices; retain all history.
5. **Narrow hot reload:** service ownership matrix plus debounce, then add the checkout/service identity probe.
6. **Split oversized seams and archive retired policy surface** one focused commit at a time.

## Rejected alternatives

- **React/Tailwind or a new build system:** the webdeck's no-build vanilla surface is an iteration advantage, not a defect.
- **Blind test parallelism:** shared homes, launchd services, and process fixtures make concurrency a source of false failures until isolation is explicit.
- **A large test-runner framework:** it would add another registry and dependency before removing the existing one.
- **Deleting history to fix context cost:** summarize and index the ledgers; do not destroy provenance.
- **Reintroducing the retired policy artifact because the tests exist:** dead policy machinery is not safety; it is a second constitution-shaped surface.

## Rich Hickey decision

Prefer **one source of truth, explicit boundaries, and data-derived lanes**. The highest-leverage work deletes complecting registries and ambient state; it does not add a framework. Keep the simple parts—Babashka, vanilla modules, pure decisions—and make the expensive parts honest, hermetic, and discoverable.
