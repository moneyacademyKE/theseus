#!/usr/bin/env bash
# Axiom A/B benchmark actor. Deterministic, env-driven; models an agent at work.
#   AXIOM_BENCH_MODE=transient|corrupt   failure scenario
#   AXIOM_BENCH_STATE=<dir>              counter home OUTSIDE the git workdir (survives rollback)
#   AXIOM_BENCH_LOG=<file>               one line appended per invocation (invocation metric)
#   AXIOM_BENCH_K=<n>                    transient: succeed on invocation n (default 3)
# State model: artifact.txt == OK is success; BROKEN is a poisoned workspace the
# actor cannot self-heal (it refuses while poisoned) — only a checkpoint restore fixes it.
set -u
STATE="${AXIOM_BENCH_STATE:?state dir required}"
MODE="${AXIOM_BENCH_MODE:?mode required}"
LOG="${AXIOM_BENCH_LOG:?log required}"
K="${AXIOM_BENCH_K:-3}"
mkdir -p "$STATE"
N=$(( $(cat "$STATE/n" 2>/dev/null || echo 0) + 1 ))
echo "$N" > "$STATE/n"
echo "$(date +%s) $MODE N=$N K=$K" >> "$LOG"
[ -f artifact.txt ] && ART="$(cat artifact.txt)" || ART=""
if [ "$ART" = "BROKEN" ]; then
  echo "actor: poisoned, refusing" >&2; exit 1
fi
if [ "$MODE" = "transient" ]; then
  if [ "$N" -ge "$K" ]; then echo OK > artifact.txt; exit 0; fi
  exit 1
fi
# corrupt mode: first invocation poisons the workspace, later ones fix a clean one
if [ "$N" -eq 1 ]; then echo BROKEN > artifact.txt; echo "actor: corrupted artifact" >&2; exit 1; fi
echo OK > artifact.txt
exit 0
