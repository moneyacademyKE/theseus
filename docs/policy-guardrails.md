# Tool policy and approval decisions

Theseus can evaluate a local constitution at `$THESEUS_HOME/brain/rules.clj` when `:policy {:enabled true}` is configured. Policy is optional: when disabled, the ordinary tool-approval flow applies.

## Decision order

1. A dormant optional tool is refused.
2. Every policy rule is evaluated. Any matching `:deny` takes precedence over all matching `:allow` rules. With multiple matches, the first deny (or first allow if there is no deny) supplies the diagnostic name.
3. When an enabled policy is missing, malformed, throws or times out, the call fails closed with `:denial/source :policy-integrity` rather than falling back to a more permissive mode.
4. A policy allow can lift an implicit default `:ask`. Explicit request policies and configured approval modes remain authoritative. Unknown approval modes are denied.

Denials include `:denial/rule`, `:denial/category` and `:denial/reason` when available. Attributed tool events carry those fields too, without adding the tool arguments to the usage ledger. Legacy unnamed rules are reported as `<unnamed>`.

## Candidate, not installation

`configs/rules.candidate.clj` is a proposed, tested constitution based on the existing local policy. It is not automatically installed or activated. The tests exercise it in disposable homes, including ordinary pushes, example env files, harmless echo commands, unrelated `rules.clj` paths, and representative denials. Compare and review it against the active constitution before making any deployment decision.

A filename such as `.env.example` is not proof that the file contains no credentials. Keep secrets out of example files.

## Security boundary

The candidate uses pattern matching on command strings and paths. Shell expansion, indirection, renamed executables, symlinks, directory searches and other execution surfaces can bypass such checks. The tests establish the cases they cover, not a general guarantee against secret disclosure or destructive operations. Treat the rules as operator guardrails, not as an OS sandbox or complete access-control boundary.

Enabling a missing or broken policy denies tool calls, including reads. Repair the policy out of band; do not disable it merely to force a call through. A timed-out SCI evaluation can leave a runaway worker thread until the process ends.

## Verification

Run `bb lint`, `bb test:e2e:policy`, `bb test:bb-agent`, `bb test:goal`, and `bb test:e2e:all` with `THESEUS_HOME` set to a disposable directory. `bb test:e2e:policy` tests the candidate through the real policy evaluator and tool seam; it does not need the production constitution.
