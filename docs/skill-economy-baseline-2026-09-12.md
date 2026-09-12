# Skill Economy — Baseline Measurement

**Date:** 2026-09-12 · **Instrument:** retroactive session-log scan (88 sessions) + `bb skill-economy` (59 workspaces)

## Numbers

| Metric | Value |
|---|---|
| Skills in the injected index | 43 |
| Skills ever read (SKILL.md in session logs) | **1** — `verification-witness` (4 reads) |
| Workspaces carrying `:skills-used` claims | 0 (contract postdates the fleet — shipped 2026-09-11 with the dogfood fixes) |
| `bb skill-economy` prune candidates | none (zero claims → nothing to compare) |

## Decision: NO PRUNE — baseline only

The retroactive instrument measures *reads*; the claims instrument
(`:skills-used` in project.edn) shipped two days ago and the 59-workspace
fleet predates it. Pruning 42 skills on reads alone would punish skills
that predate the honesty contract — the dogfood itself showed the contract
*works* (verification-witness was hunted, read twice, claimed, and its
fingerprints were all over the artifact).

**Re-measure trigger:** when the goals fleet has turned over (≥10 new
workspaces authored under the claims contract), re-run `bb skill-economy`
plus this session scan. Skills with zero reads AND zero claims across both
eras get retired from the *author-side index* (chat turns keep the full
index — `jab-hook` is useless to a goal author but valid in chat).

## What would change the decision sooner

- Authoring prompt cost becomes measurable pain (token pressure in
  `authoring.edn` receipts) → filter the author-side index to a goal-
  relevant subset early, driven by topic tags not by usage counts.
