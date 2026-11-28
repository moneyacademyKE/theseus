# ADR 0020: T3 Code Provider Integration via ACP (`theseus acp`)

- **Date:** 2026-11-28
- **Status:** Accepted
- **Benchmark Bar:** OpenCrabs `0.6.0` (`opencrabs acp`)

## Context

T3 Code (`v0.1.13`, `/Applications/T3 Code (Beta).app`, `~/.t3/userdata/settings.json`) is a control plane that drives local coding agents via built-in drivers or its `acpRegistry` (`source: "local"`) Agent Client Protocol (ACP) adapter. OpenCrabs already runs inside T3 Code via `commandPath: "/Users/moe/.local/bin/opencrabs"`, `commandArgs: ["acp"]`.

## Decision

Implement an ACP v1 JSON-RPC 2.0 server in pure Babashka (`src/bb_agent/acp.clj`) exposed as `theseus acp` via `src/bb_agent/cli.clj`, and register `"theseus"` in `~/.t3/userdata/settings.json` under `providerInstances` with `driver: "acpRegistry"`, `source: "local"`, `commandPath: "/Users/moe/.local/bin/theseus"`, `commandArgs: ["acp"]`.

## Consequences

- **Zero T3 Code fork:** Survives T3 Code updates automatically.
- **Universal ACP compatibility:** Works in T3 Code, Zed, JetBrains, MonoCode, Noodle, and `acpx`.
- **Stdout purity:** `serve-io!` binds `*out*` to `stderr` during turn execution and serializes JSON-RPC frames exclusively onto the real `System/out` writer.
- **Full OpenCrabs ACP parity:** Supports `initialize`, `authenticate`, `session/new`, `session/list`, `session/load`, `session/resume`, `session/set_model`, `session/set_mode`, `session/set_config_option`, `session/prompt`, `session/cancel`, `session/close`, `session/delete`, and `_session/steer` (wired directly to `:turn/drain-steer`).
- **Zero new dependencies.**
