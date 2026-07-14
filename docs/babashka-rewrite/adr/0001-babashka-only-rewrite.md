# ADR 0001: Babashka-Only Semantic Rewrite

## Status

Accepted.

## Context

The current OpenCrabs implementation is a Rust application with broad native, channel, TUI, media, memory, and autonomous-agent capabilities. A direct source-to-source transpilation into Babashka would preserve incidental Rust structure while losing Rust's strongest benefits.

The desired outcome is a hackable local agent with high practical utility and lower implementation complexity.

## Decision

Create a Babashka-only semantic rewrite for high and medium viability components. Do not attempt direct Rust-to-Babashka transpilation or full feature parity.

The rewrite will use:

- Babashka as the application runtime
- EDN as the initial native config/session/memory format
- data envelopes for turns, providers, tools, and results
- e2e tests as phase gates
- external CLIs for medium-complexity native capabilities where possible

## Rich Hickey Analysis

| Option | Power | Simplicity | Speed | Trade-off | Decision |
|---|---:|---:|---:|---|---|
| Direct transpilation | Low | Low | Low | Hard to prove behavior and imports Rust incidental complexity. | Reject |
| Full manual parity rewrite | High | Low | Low | Too broad before core loop is proven. | Reject |
| Babashka semantic rewrite | High | High | High | Gives up Rust-native TUI/media/channel parity. | Accept |
| Rust core plus Babashka tools | High | Medium | Medium | Keeps current complexity and splits runtime. | Defer |

## Consequences

Positive:

- lower implementation surface
- faster iteration
- simple local scripting model
- clear data boundaries
- e2e-gated feature growth

Negative:

- no full OpenCrabs parity
- weaker native TUI/media support
- less suitable for high-throughput long-running multi-channel workloads
- some provider/channel integrations may require hand-written HTTP code

## Guardrails

- Every channel must call the same core turn loop.
- Tool execution defaults to approval-required.
- Medium-viability extensions require a failing acceptance test or documented user need.
- Low-viability components remain out of scope unless this ADR is superseded.
