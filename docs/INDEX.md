# Theseus Documentation Index

Only active, factually accurate documentation for the current codebase is indexed here.

## Architecture & Decisions
- [Babashka Rewrite Spec, Roadmap & Tasklist](babashka-rewrite/README.md) — `PRODUCT_SPEC.md`, `ROADMAP.md`, `TASKLIST.md` (verified by `bb verify:rewrite-docs`).
- [ADR 0001 — Babashka-Only Semantic Rewrite](babashka-rewrite/adr/0001-babashka-only-rewrite.md)
- [ADR 0002 — Codebase Audit Hardening and Honest Gates](babashka-rewrite/adr/0002-codebase-audit-hardening-and-honest-gates.md)
- [ADR 0003 — Audit Wave 2: Shared Atomic I/O, Security Boundaries & Runtime Hardening](babashka-rewrite/adr/0003-audit-wave2-atomic-io-and-runtime-hardening.md)
- [ADR 0004 — Hierarchical Context Compaction & Persistent Checkpoints](babashka-rewrite/adr/0004-hierarchical-context-compaction.md)
- [ADR 0005 — Mercury-Agent Safety, Token Economics, Turn Integrity & Self-Learning](babashka-rewrite/adr/0005-mercury-agent-safety-economics-and-learning.md)
- [ADR 0006 — Web Deck Explicit State Machines & UX/DX/AX Hardening](babashka-rewrite/adr/0006-webdeck-explicit-state-machines.md)

## Active Subsystems & Audits
- [The Web Deck](webdeck.md) — architecture, explicit state machines, AX contract (`window.__THESEUS_DECK__`, `data-*`), vendoring policy, and tests.
- [Web Deck State-Machine Audit (2026-11-22)](audits/webdeck-state-machines-2026-11-22.md) — full 24-machine inventory and 15 UX/DX/AX decisions.
- [Hot-Code Reload](hot-reload.md) — opt-in launchd development supervisor (`THESEUS_HOT_RELOAD=1`).
- [Goal Runner × OpenCode Dogfood Ladder](opencode-dogfood.md) — dogfood contract pinned by `test/bb_agent/goal/phase9_opencode_dogfood_test.clj`.

## Gap Analysis (Benchmark Bar: OpenCrabs)
- [Theseus Current Gap Analysis & Delivery Ledger](GAP_ANALYSIS.md) — active audits, learnings, patterns, and verified receipts.
- [Theseus vs OpenCrabs — Architecture Gap Analysis](gap-analysis-theseus-vs-opencrabs-2026-11-11.md) — tool/architecture comparison against the OpenCrabs bar.
