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
- [ADR 0007 — OpenCrabs v0.6.0 Workflow Parity: Surgical I/O, Mechanical Gates, Parallel Reads & Live Subagents](babashka-rewrite/adr/0007-opencrabs-parity-surgical-io-gates-and-subagents.md)
- [ADR 0008 — DuskBot-Inspired Reliability, Safety Floor, Pacing & Memory Upgrades](babashka-rewrite/adr/0008-duskbot-reliability-safety-and-memory-upgrades.md)
- [ADR 0009 — Zero-Daemon Babashka-Native Datalog Engine](babashka-rewrite/adr/0009-zero-daemon-native-datalog-engine.md)
- [ADR 0010 — Security-vs-Robustness Calibration](babashka-rewrite/adr/0010-security-vs-robustness-calibration.md)
- [ADR 0011 — 17-Point Runtime Robustness Hardening Wave](babashka-rewrite/adr/0011-runtime-robustness-17-hardening-wave.md)
- [ADR 0012 — Wave 2: 17-Point Runtime Robustness Hardening](babashka-rewrite/adr/0012-runtime-robustness-wave2-17.md)

## Active Subsystems & Audits
- [The Web Deck](webdeck.md) — architecture, explicit state machines, AX contract (`window.__THESEUS_DECK__`, `data-*`), vendoring policy, and tests.
- [Web Deck State-Machine Audit (2026-11-22)](audits/webdeck-state-machines-2026-11-22.md) — full 24-machine inventory and 15 UX/DX/AX decisions.
- [Multi-Writer & Datalog DB Gap Analysis (2026-11-22)](audits/multiwriter-db-gap-analysis-2026-11-22.md) — 5-way DB comparison, empirical DataScript SCI receipt, and zero-daemon native Datalog path.
- [Security vs. Robustness Gap Analysis (2026-11-24)](audits/security-vs-robustness-gap-analysis-2026-11-24.md) — 6 security-vs-robustness collisions and 5 calibrated fixes.
- [17-Point Runtime Robustness Gap Analysis (2026-11-24)](audits/robustness-17-gap-analysis-2026-11-24.md) — 17 verified runtime crash/corruption fixes across 12 namespaces.
- [Wave 2: 17 More Runtime Robustness Upgrades (2026-11-24)](audits/robustness-wave2-17-gap-analysis-2026-11-24.md) — 17 additional crash/corruption fixes across 14 namespaces.
- [Hot-Code Reload](hot-reload.md) — opt-in launchd development supervisor (`THESEUS_HOT_RELOAD=1`).
- [Goal Runner × OpenCode Dogfood Ladder](opencode-dogfood.md) — dogfood contract pinned by `test/bb_agent/goal/phase9_opencode_dogfood_test.clj`.

## Gap Analysis (Benchmark Bar: OpenCrabs)
- [Theseus Current Gap Analysis & Delivery Ledger](GAP_ANALYSIS.md) — active audits, learnings, patterns, and verified receipts.
- [Theseus vs OpenCrabs v0.6.0 — Workflows, Tools & LLM Build Mechanics (2026-11-22)](gap-analysis-theseus-vs-opencrabs-2026-11-22.md) — deep side-by-side analysis against OpenCrabs `v0.6.0` (`8666ca2d9`).
