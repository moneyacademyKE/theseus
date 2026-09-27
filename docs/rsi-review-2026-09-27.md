# RSI review — 2026-09-27

Requested from mutiny topic 4721. Every claim below was re-verified against
disk/git/processes this turn — nothing is taken from Eileen's own report
unchecked (though her report matched receipts everywhere I probed).

## The lane as it stands — three schedules

| schedule | cadence (EAT) | job | last run |
|---|---|---|---|
| rsi-daily | 09:30 | provider-health digest | ok — 279 events, 0 opportunities |
| rsi-learn-daily | 08:50 | GitHub self-learning scout | ok — 38 repos seen, 8 knowledge pages |
| rsi-report-daily | 20:00 | receipts-only self-report | **new today — first run died at delivery** (fixed this turn) |

## Did it add new features? Yes — verified green

1. **The scout** — `a49b324` (Sep 25). `src/bb_agent/rsi_learn.clj` (194 LOC)
   + `bb rsi learn fetch / propose` + a seen-ledger so it never rescans. Receipts:
   8 `brain/knowledge/` pages written with attribution, "what we deliberately did
   NOT take" sections, and proposals deduped into `brain/improvements.md`.
   The governance is the impressive part: it **refuses** — sutando (unattended
   self-modification, no ledger), GENesis-AGI/SuperInstance/evrim (wrong problems),
   and flags openclaw-worker + LLM-Agent-Factory as social-engineering shapes,
   ledgers them so future scouts skip them. A scout with taste.
2. **The knowledge ns** — `8e589b4` (Sep 27). Six stolen patterns as one
   namespace (169 LOC + 109 test LOC): staleness marks, wins ledger, protection
   tiers, pre-write snapshots, curator dry-run, no-regression promotion gate.
   Live-run receipts on disk: all 8 knowledge pages stamped `last-reviewed:
   2026-09-27`; every touched page has a `.bak` snapshot; `check-write!`
   refused `brain/rules.clj` + `config.edn` (the forbidden list at
   knowledge.clj:85 — the constitution-adjacent floor held on its first live
   contact); `brain/wins.md` created with evidence lines.
   Gate re-run by me this turn: **133 tests / 498 assertions, 0 failures**
   (+ goal suite 96/482). Her claimed numbers are real.
3. **The report lane** — rsi-report-daily (moe's directive today). First
   report compiled is genuinely receipts-only and honest about its own
   failures (names the 6-reco HALT, names make-rsi-do-this's `:invalid`).
   Delivery was broken — see defects.
4. **Smaller, real**: brief reruns inject prior coverage — new takes only
   (`bc3bb07`); authoring ceiling 45min → 2h (`0b1c54b`, config still disables
   it entirely); register-time cwd pin (`a49b324` — schedules were silently
   inheriting the daemon's ambient directory: real bug, real fix).

## The original mandate — still unshipped, relaunched tonight

"make rsi do this" (Sep 11): RSI must analyze what actually **fails** — tool
outcomes, approval decisions, policy verdicts — not just provider health.
Three deaths, none the spec's fault:

1. veto-costume bug (a `cat config.edn` constitution veto reported as an
   approval request) — fixed `a311630`;
2. the 45-min authoring ceiling guillotined live work — fixed `fd92959`/`0b1c54b`;
3. Sep 27 01:42 EAT: flash authoring ran 41 rounds / 22 min, produced a
   **complete, sophisticated artifact** — and the validator killed it on one
   missing config key (`:name`). The authored project.edn has a 3-layer
   methodology (goal unreachable at baseline, integrity floor, six observers),
   a PATH-hardened act.sh, declared deliverables. One key short.

This turn: patched `:name` into the authored config and relaunched the
artifact **as-is** via `scripts/relaunch_goal.clj` — no fourth authoring
roulette. The runner + watcher are live with progress in topic 5; its own
observers supervise each act (one-funnel / tool-table / rsi-tests /
agent-tests / lint), integrity holds between acts, rollback on failure.
The digest today still reads 279 events / **0 tool events** — the exact
blindness this goal exists to cure. When the 🎯 lands in topic 5, that
number gets eyes.

## Defects found this review (cheap ones fixed)

1. **rsi-report-daily delivery was broken** — its prompt hand-rolled a
   multipart sendDocument as a quoted `bb -e` one-liner; the first run
   compiled the report (~20:07 EAT) and died at delivery: no final in
   `schedule-runs.edn`, nothing in topic 5, stack trace in the tick log.
   Fixed: `scripts/send_document.bb` (the watcher's proven upload pattern
   as a CLI) + prompt rewritten to call it; **today's report was delivered
   through it as the live test** (ok=true). Backup:
   `scratch/schedules.edn.bak-20260927-231035`.
2. **digest.md is two days stale on disk** (mtime Sep 25 07:50) while
   rsi-daily finals on Sep 26/27 claim `digest -> digest.md`. The write
   path is silently failing or conditional. Flagged, not fixed — one look
   next session.
3. **Stale env name in schedule prompts** — `OPENCRABS_HOME` survived the
   decoupling and worked only by default-resolution luck. Learn-prompt env
   updated in the same surgery; count now zero in live state.
4. **implement-6-reco HALT** — the authored act.sh had no build step (a
   pure grader can't create its own evidence). Work landed anyway via
   `8e589b4`; the verdict stands honestly and the systemic finding
   ("authoring must demand a builder") is recorded in her report.

## Verdict

Yes — the RSI lane is now a system that adds features, with tests and
refusals: the scout steals selectively, the knowledge code protected the
constitution on first contact, and the self-report doesn't grade its own
homework (I checked its homework). The one thing it hasn't shipped is the
original mandate — tool-failure analysis — after three infra deaths. That
goal is running again tonight with its authored artifact intact, and the
report lane that was supposed to announce it can finally announce things.

*Hickey check:* the lane's best property is what it declines to take —
refusals with reasons, ledgers for poisoned repos, protection tiers that
bite on the first try. The features it did add are one-namespace-small and
test-pinned. The remaining gap is one goal-runner run away, supervised by
its own observers.
