# AGENTS.md — Rules for Agents Working in This Repo

This repo is maintained by AI agents (daemon sessions, plan workers, sub-agents).
Every rule below is indexed to a real incident; the full ledger lives in
`docs/GAP_ANALYSIS.md`. Read this file before writing code here.

## Before You Start

1. **One clone per repo.** Before cloning, search the machine for an existing
   clone. The canonical clone is `~/Desktop/rich-hickey-gap-analysis/theseus`.
   A second clone is drift waiting to happen.
   *(Incident: a plan worker that couldn't see the canonical clone created a
   duplicate at `~/theseus`.)*
2. **Check the tree before starting.** `git status` + look for HANDOFF notes.
   Parallel sessions work in this clone. Uncommitted work that isn't yours:
   read it, run the relevant suite against it, then absorb-after-verify or
   coordinate — never discard, never reimplement in parallel.
   *(Incidents: three worker collisions, every one resolved productively by
   verify-then-absorb.)*
3. **Fresh ground truth, every task.** Do not trust memory of file contents,
   branch state, or test counts — other sessions move the tree between turns.
   Re-read before every edit and every claim.

## Verification — Before Any Claim of Done

4. **Read the file, not the grep.** An absence claim ("X doesn't exist",
   "prices aren't tracked") requires a full read of the relevant file.
   *(Incident: the cost-table port was proposed because a token grep missed the
   pricing half of `usage.clj` — the machinery shipped in the repo's first
   commit, `62867f9`.)*
5. **Exit codes come from the command, never a pipe.** `bb test | tail` reports
   tail's exit code. Run the command bare, or `cmd; echo "exit=$?"` — a piped
   receipt is not a receipt. *(Incident: a false-green commit because a pipe
   masked exit 1; fixed forward in `f04310c`.)*
6. **Verify pushes with `git ls-remote`, not the push receipt.** A local crash
   can emit a push-shaped line after eating your commit.
   *(Incident: a signal-6 batch crash destroyed a CHANGELOG commit while the
   output still showed a push line; remote state was only settled by ls-remote.)*
7. **State changes are tool calls, not sentences.** "The PR is ready" is prose;
   `gh pr ready` is the action. Report only what a command returned.
   *(Incident: a merge attempt bounced off a draft PR a prior turn had called
   ready.)*
8. **Self-reports aren't receipts — including your own.** Verify absorbed or
   parallel work by re-running its suite yourself. Verify release/PR state with
   fresh queries before acting on it.
9. **`gh` field names: check before querying.** `gh release view` has no
   `isLatest` and no `target` (it's `targetCommitish`).
   *(Incidents: two failed verify queries, same class.)*

## Git Discipline

10. **Branch check before every commit.** `git branch --show-current` first.
    If a push returns "Everything up-to-date", you committed somewhere else —
    `git log -1 --all`, cherry-pick to the intended branch.
    *(Incident: a release stamp committed on a stale feature branch.)*
11. **Merge commits, never squash.** Delivery records cite SHAs; squash orphans
    every citation.
12. **Release ceremony, in order:** stamp the CHANGELOG
    (`## vX.Y.Z - YYYY-MM-DD`) → push → verify remote → annotated tag on the
    stamp commit → push tag → `gh release create` → verify with
    `gh release view --json tagName,isDraft,isPrerelease` + `git ls-remote` for
    the tag. Tag, changelog header, and release must name the same SHA.
    *(Incident: v0.2.0 shipped with its section still headed "Unreleased".)*

## Clojure Discipline (Babashka)

13. **Whole-file writes over paren surgery.** If a file is fully read, rewrite
    it whole rather than line-editing — paren-count drift is the top local
    failure mode. After structural edits, parse-check before running tests.
14. **One edit per file per batch.** Parallel edit calls to the same file race;
    the second write can silently not land. Serialize edits to one file.
    *(Incident: a call-site edit reported success while the old line survived.)*
15. **`=` is category-strict.** `(= 0.5 1/2)` is false — use `==` or coerce with
    `double` when comparing across notations. *(Incident: stats rate test.)*

## Tests and CI

16. **Red/green.** A failing test for the right reason before any implementation.
17. **Numbers are measured.** Test/assertion counts in CHANGELOGs and reports
    are summed from actual output (assertions are field 5 of each
    `Ran N tests containing M assertions` line), never arithmetic on remembered
    counts.
18. **LOC ceiling:** soft 250, hard 500 per file (test files exempt). `wc -l`
    before claiming done.
19. **Zero dependencies.** New dependencies require explicit owner approval.
