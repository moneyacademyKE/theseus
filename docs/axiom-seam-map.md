# Axiom ↔ Theseus Seam Map

*Research deliverable for bk-af8d. Ground truth: git history + grep, 2026-09-09.*
*Question: what did the Axiom fold-in buy, and does any live seam to the external repo remain?*

## TL;DR

Axiom is **not a dependency — it is an ancestor**. The fold-in (`2e6c2ef`, completed by the
blend `abf5c50`, 2026-09-07) renamed `axiom.*` to `bb-agent.goal.*` and moved on. There is
**zero live coupling** to the external Axiom repo: `bb.edn` has no external paths or deps,
no source file requires anything axiom-named, and the only `axiom` token in `src/` is the
phrase "mission axiom" in a `decision.clj` comment.

## Timeline (from git log)

| Commit | What happened |
|---|---|
| `2e6c2ef` | Fold axiom goal runner in as `bb goal` task |
| `a2900b0` | Lock TOCTOU race fix (found by stress test) |
| `e5e519e` | Port `/axiom` skill to the folded `bb goal` entrypoint |
| `08437c6` | Run loop routed through `axiom.decision/decide` (drops core's inline twin) |
| `9c5cf13` | CLI dispatch via bb task form |
| `d49108e` | Axiom halts wired to Telegram (localhost listener) |
| `0fae74c` | Honest checkpoint primitives — no silent fail-open |
| `ec47c36` | A/B benchmark harness (actor + runner) |
| **`abf5c50`** | **The blend**: `axiom.*` → `bb-agent.goal.*`, integrity-bundle vocabulary, goal-runner identity (52 files, pure rename: 371+/371−) |

Post-blend: **11 commits** touched the goal tree + bridge — all Theseus-side evolution.

## What the blend bought

15 namespaces landed at blend time under `src/bb_agent/goal/`:
`budget, config, control, core, decision, git, harness, lock, log, main, notify,
observe, predicates, status, taxonomy` — plus `goal_bridge.clj` and `notify_listen.clj`.

That is the entire goal-runner capability: observable workspaces, checkpoint/rollback with
teeth, integrity bundles, stall detection, budget accounting, and the decision loop. The
A/B stress run (bk-0c72) measured its supervision value empirically before the blend.

**Theseus-born since the blend** (not axiom lineage): `goal/registry.clj`,
`goal/outcomes.clj`, `goal/progress.clj` (the bk-c60f split), topic-scoped recovery,
progress-based authoring stall detection (bk-963f), the `:already-satisfied` verdict
(bk-e827), and the SKILL CONTRACT in the author prompt (bk-d8a0).

## Consumers of the goal tree (outside `goal/`)

| Consumer | Uses |
|---|---|
| `goal_bridge.clj` | the chat→goal seam: authoring, launch, validation (registry, outcomes, predicates, observe, config, progress, control, notify) |
| `telegram_lifecycle.clj` | boot-time `recover-interrupted!` |
| `telegram_intake.clj` | `/goal` + build-verb routing |
| `tool/goal.clj` | in-turn goal tool |
| `notify_listen.clj` | halt notifications |

`goal/main.clj` has no external requires — it is the `bb goal` CLI entrypoint, invoked as
a task, not a library.

## The external repo: dead seam, one discrepancy

- `~/Desktop/axiom` exists, clean, **frozen at 2026-07-11** (`bb promote` era) — two months
  *older* than the blend, so it is not the blend's source tree.
- `skills/axiom/SKILL.md` claims the checkout is "deleted; the GitHub copy is stale/broken
  at HEAD". Half right: the directory is present (probably restored or never actually
  removed). The skill's operational guidance — entrypoint is `bb goal` from the Theseus
  repo, never the old checkout — remains correct.
- **Recommendation:** archive or delete `~/Desktop/axiom` (owner call), and correct the
  SKILL.md claim to "frozen pre-blend snapshot, not the source of truth". The source of
  truth is `src/bb_agent/goal/` here.

## Risks / notes

- None live. The seam that could have drifted (two repos evolving one runner) was closed
  by the blend's completeness.
- `skills/axiom/SKILL.md`'s rollback warning (stall recovery runs `git reset --hard` in
  `:workdir`) is still the sharpest edge in the system — keep pointing goals at dedicated
  directories only.
