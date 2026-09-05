# Axiom × opencode Dogfood Ladder

Axiom should supervise real harnesses the way a good operator supervises a smart but unreliable contractor: give it a bounded task, observe the world, ignore self-report, and stop before it poisons the repo.

This ladder is deliberately incremental. Do not start with the giant refactor. Start with a cheap, low-blast-radius task and only climb when the previous rung leaves useful logs and a green world.

## Before running

1. Install and authenticate `opencode`.
2. Run from the repo root:
   - `./.tools/bin/bb -cp src:test -m axiom.run-tests`
3. Prefer a disposable branch or throwaway clone for the first live runs.
4. Inspect `CONFIG.md` for the harness config contract.

Axiom invokes opencode with argv, not shell interpolation:

`opencode run --model {{model}} {{prompt}}`

The harness can say anything. Axiom only trusts observers.

## Ladder summary

| Level | Config | Task | Risk | Success predicate |
|---:|---|---|---|---|
| 1 | `configs/dogfood/01-doc-link-check.edn` | Fix documentation/reference drift | Low | README/CONFIG/RELEASE all mention required release surfaces |
| 2 | `configs/dogfood/02-config-library-tighten.edn` | Tighten example config library | Low | every `configs/*.edn` and `configs/dogfood/*.edn` loads |
| 3 | `configs/dogfood/03-test-repair.edn` | Repair a targeted failing test | Medium | full Babashka suite green |
| 4 | `configs/dogfood/04-operator-status-ux.edn` | Improve status/operator facts UX | Medium | status/operator/harness tests green |
| 5 | `configs/dogfood/05-harness-contract-hardening.edn` | Harden opencode harness contract | Medium | harness + config validation tests green |
| 6 | `configs/dogfood/06-hot-reload-drill.edn` | Exercise hot-reload semantics under test | High | hot-reload + full suite green |
| 7 | `configs/dogfood/07-cross-cutting-refactor.edn` | Simplify/refactor across core/config/harness without semantic drift | High | full suite green, demos still gate |
| 8 | `configs/dogfood/08-release-candidate-sweep.edn` | Release-candidate sweep | Highest | suite green, demos correct, config library loads, docs present |

## Level 1 — documentation/reference drift

Purpose: prove Axiom can safely delegate a small text maintenance task.

Expected opencode behavior:
- inspect docs only;
- update stale counts or missing references;
- avoid touching source unless the docs prove a code contract mismatch.

Axiom checks documentation surfaces, not opencode's summary.

## Level 2 — config library tightening

Purpose: make opencode work with EDN configs and existing schema without inventing code.

Expected opencode behavior:
- load configs mentally and via tests;
- fix malformed comments, missing log dirs, missing bounded budgets, or stale examples;
- keep configs as data.

## Level 3 — targeted test repair

Purpose: introduce normal coding pressure.

Recommended drill:
1. Create a throwaway branch.
2. Break one assertion in a targeted test.
3. Run Axiom with this config.
4. Confirm it fixes the world, not merely the symptom.

## Level 4 — operator status UX

Purpose: force a small feature-style change across status/operator code while still bounded.

Good candidate improvements:
- clearer latest-event display;
- include active model/harness when known;
- better hot-reload state wording;
- preserve pure `operator-facts` shape.

## Level 5 — harness contract hardening

Purpose: make opencode itself the subject. This is where Axiom proves it can supervise the supervisor boundary.

Good candidate improvements:
- reject harness acts with blank prompts;
- make custom harness profile docs clearer;
- add test coverage for act-level args override;
- ensure argv never collapses into shell strings.

## Level 6 — hot-reload drill

Purpose: verify config swaps during a run without losing run-state. This is subtle; expect at least one halt bundle while tuning.

Rules:
- hot-reload must stay opt-in;
- swapped config must validate before use;
- run-owned fields must remain fixed;
- state counters must survive.

## Level 7 — cross-cutting refactor

Purpose: ask for simplification, not new features. This tests whether opencode can reduce incidental complexity under Axiom's invariants.

Prompt should emphasize:
- no behavior change;
- smaller pure functions;
- preserve tests and demos;
- improve names only where clarity increases.

## Level 8 — release-candidate sweep

Purpose: final broad pass. It is intentionally dangerous because the prompt is broad. Axiom's job is to bound it with strong integrity checks and halt loudly.

Done only when:
- full suite is green;
- four demos exit as expected;
- config library loads;
- docs are present;
- no halt bundle remains unexplained.

## Run commands

Run a dogfood config:

`./.tools/bin/bb axiom.clj configs/dogfood/01-doc-link-check.edn`

Inspect status:

`./.tools/bin/bb axiom.clj status configs/dogfood/01-doc-link-check.edn --last 5`

Run the final gate manually:

`./.tools/bin/bb -cp src:test -m axiom.run-tests`

`bash examples/demo/reset.sh >/dev/null 2>&1; ./.tools/bin/bb axiom.clj examples/steady.edn >/dev/null 2>&1; echo steady=$?`

`bash examples/demo/reset.sh >/dev/null 2>&1; ./.tools/bin/bb axiom.clj examples/stall.edn >/dev/null 2>&1; echo stall=$?`

`bash examples/demo/reset.sh >/dev/null 2>&1; ./.tools/bin/bb axiom.clj examples/corrupt.edn >/dev/null 2>&1; echo corrupt=$?`

`bash examples/demo-recover/reset.sh >/dev/null 2>&1; ./.tools/bin/bb axiom.clj examples/recover.edn >/dev/null 2>&1; echo recover=$?`

## Operator rule

If Axiom halts, do not blindly rerun. Read:

- `<log-dir>/events.log`
- `<log-dir>/halt-bundle.edn`
- latest `<log-dir>/iter-*.edn`

The halt bundle is the point. A failed dogfood run with a crisp halt reason is useful data. A successful run that got lucky and left no understanding is not.
