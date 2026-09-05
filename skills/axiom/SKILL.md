---
name: axiom
description: Delegate a bounded build task to the Axiom goal runner folded into the Theseus repo (`bb goal`) — Axiom supervises an agent harness against an observable workspace with checkpoints and rollback.
---

# axiom

Use this skill when the user wants Axiom to drive a bounded creation/build task from OpenCrabs.

## What this does

This skill does **not** hijack an already-running opencode TUI session. That would be fake-magic bullshit.

Instead, it uses OpenCrabs as the front door, then:

1. switches into `~/Desktop/rich-hickey-gap-analysis/theseus` — Axiom lives here as `src/axiom/`, exposed as the `bb goal` task (fold commit `2e6c2ef`, lock fix `a2900b0`)
2. inspects `configs/` and the dogfood docs for existing patterns
3. creates or adapts a goal config for the requested task
4. runs `bb goal <config.edn>` so **Axiom** supervises the configured harness (`opencode` by default; harnesses are argv templates, overridable in config) as an external process
5. verifies the resulting world state from files/build/tests instead of trusting harness self-report

## Required behavior

- Treat `~/Desktop/rich-hickey-gap-analysis/theseus` as the control repo unless the user explicitly says otherwise. Entrypoint is `bb goal` from the theseus root — never the old `~/Desktop/axiom` checkout (deleted; the GitHub copy is stale/broken at HEAD).
- **Rollback has teeth:** Axiom's stall recovery executes `git reset --hard` against the config's `:workdir`. Only point `:workdir` at a dedicated directory with a clean, fully committed tree. Never aim it at a repo holding uncommitted work — it will eat them (learned the hard way, 2026-09-05).
- Use **absolute** `:workdir` paths in configs — relative paths resolve against the invoking cwd, not the config's location.
- One runner per config: an atomic lockfile refuses concurrent runs on the same config (`Lock held by a live process`). That is correct behavior — don't fight it.
- Lifecycle: `bb goal <config.edn> [--once]`, then `bb goal status|pause|resume|stop <config.edn>`.
- After any change to `src/axiom/` or `test/axiom/`: run `bb test:axiom` (89 tests / 467 assertions, green at `a2900b0`).
- Read the current config examples (`configs/`) and dogfood docs before creating a new goal config.
- Keep the task bounded and observable. Convert vague requests into concrete required artifacts, checks, and build/test predicates.
- Prefer creating disposable workspaces outside the theseus tree unless the user explicitly wants a real target repo changed.
- Use real provider-qualified opencode model ids from the local opencode config.
- If the user asks to "take over my opencode instance", explain the boundary clearly: Axiom launches supervised delegated work; it does not possess another live TUI process.
- After the run, report what Axiom accomplished, what the harness did, and what the observed world proves.

## Inputs to gather from the user request

Extract or infer:
- the thing to build
- required artifacts/files
- required phrases/content
- any build/test command
- whether this should run in a disposable fixture or a real repo

If these are too vague to verify, tighten them before launching Axiom.

## Success criteria

The result is successful only when the observed world proves it:
- required files exist
- required content/build checks pass
- Axiom halts cleanly with success or a clearly explained failure mode

## Suggested command shape

A typical user invocation is:
- `/axiom build a run club website`
- `/axiom make axiom dogfood opencode against a landing page task`
- `/axiom create a disposable fixture that builds and verifies a docs microsite`

Interpret the trailing text as the task brief and execute the workflow above.
