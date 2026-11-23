# ADR 0010 — Security-vs-Robustness Calibration (Prioritize Robustness Without Dropping Essential Invariants)

- **Date:** 2026-11-24
- **Status:** Accepted
- **Gap Analysis:** [`docs/audits/security-vs-robustness-gap-analysis-2026-11-24.md`](../../audits/security-vs-robustness-gap-analysis-2026-11-24.md)

## Context

Over-eager security heuristics were causing real-world false-positive turn failures across legitimate developer workflows:
1. Reading any CSS/HTML file with `display: none` or `opacity: 0` set `:suspicious? true` and hard-blocked every subsequent `edit_file`/`write_file`/`shell` call.
2. `strong-model?` used a hardcoded frontier allowlist that broke router aliases (`infered/auto`) and newer frontier models (`zai/glm-5.9`, `qwen3`, etc.) after any `web_search`.
3. `never-auto-shell-command?` matched raw command text inside quotes, blocking harmless `echo '(rm|dd)'` or multi-line quoted commit messages.
4. `dense-encoded-blob?` (entropy `>= 3.6`, len `>= 24`) false-flagged pure-letter CamelCase identifiers (`ConcurrentModificationException`), and `shell-exfil-re` blocked loopback `curl -d` to `127.0.0.1`/`localhost`.
5. `browser_read` rejected all HTTPS URLs containing `#fragments` and discarded a completed page read if tab-close transiently failed.

## Decision

Calibrate all 5 seams to prioritize robustness while preserving every essential security invariant:
1. **`src/bb_agent/security.clj`:** Split injection signals into `hard-blocking-signals` (`:instruction-override`, `:system-impersonation`, `:secrecy-directive`, `:tool-coercion`, `:zero-width-chars`, `:html-comment`) vs advisory spotlight-only (`:css-hidden`, `:md-comment`).
2. **`src/bb_agent/security.clj`:** Invert `strong-model?` to a weak-model denylist (`weak-model-markers`) so frontier models and router aliases work without allowlist churn.
3. **`src/bb_agent/security.clj`:** Calibrate `dense-encoded-blob?` (`len >= 28`, `entropy >= 4.0`, mixed letters + digits, excluding UUIDs/hex/slugs) and allow loopback-only `curl -d` (`127.0.0.1`/`localhost`/`[::1]`, non-netcat).
4. **`src/bb_agent/tool/process.clj`:** Strip single-quoted and substitution-free double-quoted string literals before checking `dangerous-cmd-re` while preserving `$(...)` and `` `...` `` command substitutions.
5. **`src/bb_agent/browser_tool.clj`:** Allow `#fragment` in HTTPS URLs and return `:ok` with `:warning` when a page read succeeds but tab-close fails.

## Consequences

- Eliminates all 5 false-positive failure modes while keeping 100% of real secret-redaction, SSRF, shell-bypass, prompt-injection-override, and off-host exfiltration gates intact and green.
