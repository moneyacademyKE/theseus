# Rich Hickey Gap Analysis — Security vs. Robustness in Theseus

- **Date:** 2026-11-24
- **Target System:** Theseus `v1.5.0` (`a6a0b7f`)
- **Guiding Principle (Rich Hickey):** Separate *essential* security invariants (never leak real secrets, never execute unapproved destructive commands, never obey prompt-injection overrides) from *incidental* over-eager heuristics that break legitimate workflows, false-positive on normal code/URLs/models, and collapse robustness.

---

## 1. Security-vs-Robustness Collisions Matrix

| # | Seam & Location | Current Security Behavior | How It Lowers Robustness (Real Failure Mode) | Robustness-First Redesign | Essential Security Preserved? |
|---|---|---|---|---|---|
| **1** | **CSS/Markdown taint storm** (`security.clj:55-56, 194-230`) | `scan-injection` flags `display: none`, `opacity: 0`, `visibility: hidden` on *every* `source-tools` call (including local `read_file`/`search`), setting `:suspicious? true` and **blocking all subsequent `edit_file`/`write_file`/`shell`**. | Reading ANY CSS/HTML file (`web/css/deck.css`, Tailwind, etc.) bricks the entire coding turn with `:taint` denials. | Split signals into **hard-blocking** (actual override/impersonation/secrecy/coercion/zero-width/malicious HTML comments) vs **advisory spotlight-only** (`:css-hidden`, `:md-comment`), and only scan external sources for hidden markup. | **Yes.** Real injection overrides still hard-gate sinks; CSS/markdown comments still emit advisory spotlight without bricking turns. |
| **2** | **Model allowlist fail-closed** (`security.clj:30-46, 232-236`) | `strong-model?` uses a hardcoded frontier allowlist; every unrecognized model/alias returns `false` and blocks `control-tools` after any `web_search`. | Breaks your own live models (`infered/auto`, `zai/glm-5.9`, `qwen3`, `kimi-k2`, future Claude/GPT/Gemini releases) after any web search. | Invert to an explicit **weak-model denylist** (`mini`, `nano`, `flash-lite`, `haiku`, `3b`, `7b`, `8b`, `tiny`, `small`, `fake`); all non-blank, non-weak models pass. | **Yes.** Explicitly tiny/weak models still require approval after external reads; new frontier models & router aliases stop breaking. |
| **3** | **Quoted-string shell false positives** (`tool/process.clj:14-25`) | `dangerous-cmd-re` runs on raw command text, matching `\nrm` or `(rm` anywhere — even inside quoted strings or `rg` regexes. | Blocks harmless `rg -n "(rm\|dd)" src/`, `git commit -m "clean\nrm old"`, and Clojure `bb -e '(prn "sudo")'`. | Strip single- and double-quoted string literals before running `dangerous-cmd-re`, and only treat `(` as subshell in command/substitution position (`$(`, `(` after separator). | **Yes.** Every real `rm`, `/bin/rm`, `\rm`, `env rm`, `nohup rm`, `echo hi\nrm`, `echo $(rm ...)` stays 100% blocked. |
| **4** | **`dense-encoded-blob?` & local `curl` false positives** (`security.clj:121-171`) | Flags any token len >= 24 with Shannon entropy >= 3.6 (including pure-letter CamelCase like `ConcurrentModificationException`!) and blocks `curl -d` to `127.0.0.1`/`localhost`. | `web_search` for `"ConcurrentModificationException"` or `"IllegalArgumentException"` is **denied as exfiltration**; local dev `curl -d` to `127.0.0.1` is blocked. | Require `len >= 32`, `entropy >= 4.2`, **mixed character classes (letters + digits)**, and not a standard UUID (`8-4-4-4-12`). Allow `curl -d` when all target URLs are loopback (`127.0.0.1`/`localhost`/`[::1]`). | **Yes.** Real API keys (`sk-`, `ghp_`, `AKIA`, `Bearer`, PEM), base64/high-entropy exfil blobs, and off-host `curl -d` uploads stay 100% blocked. |
| **5** | **`browser_read` `#fragment` rejection & close-error discard** (`browser_tool.clj:39-48, 76-84`) | Rejects every HTTPS URL containing a `#fragment` (`https://docs...#section`) and discards a successful page read if tab-close fails. | Fails on half of all real docs/GitHub/MDN anchor links; throws away valid content on transient tab-close errors. | Allow `#fragment` in `valid-url?` (fragments are client-side only and never sent to the server); return `:ok` with `:warning` if page read succeeded and only tab-close failed. | **Yes.** HTTPS-only, no userinfo, and full private/loopback/link-local/CGNAT SSRF blocking remain 100% intact. |
| **6** | **`a2a_client` strict `:follow-redirects :never`** (`a2a_client.clj:22-23`) | Rejects all 301/302/307/308 redirects, including same-origin trailing-slash redirects. | Reverse-proxied A2A servers returning 308 `/a2a/v1` → `/a2a/v1/` fail. | Keep `:never` for now (low frequency, zero active breakage; revisit only if a real A2A peer needs same-origin redirect following). | **Yes.** Prevents cross-host Bearer token leakage. |

---

## 2. Benefits vs. Tradeoffs

| Change | Robustness Benefit | Security Tradeoff & Mitigation |
|---|---|---|
| **1. Hard vs Advisory Injection Signals** | Eliminates the #1 coding-turn killer (reading CSS/HTML/code no longer bricks `edit_file`/`shell`). | Hidden CSS on a malicious webpage still emits `[SECURITY SPOTLIGHT ...]` advisory; actual override/coercion/zero-width text still hard-blocks sinks. |
| **2. Denylist `strong-model?`** | Zero maintenance breakage as models evolve (`infered/auto`, `glm-5.9`, `qwen3`, `gpt-6`, etc.). | An unlisted weak model without a weak marker in its name would not trigger the weak-model gate — mitigated because high-signal injection + exfil gates still run on *all* models regardless of strength. |
| **3. Quote-Aware Shell Floor** | `rg`, `git commit -m`, and `bb -e` containing `"rm"`/`"sudo"` stop false-failing. | Real command substitution (`$(rm ...)`) inside double quotes is still preserved and caught because `$(...)` is scanned before quote stripping! |
| **4. Calibrated `dense-encoded-blob?` + Loopback `curl`** | Normal Java/Clojure exception names, UUIDs, and local `127.0.0.1` API tests work cleanly. | Short (<32 char) pure-alpha blobs wouldn't trigger entropy heuristic — mitigated by explicit `exfil-secret-re` catching `sk-`, `ghp_`, `AKIA`, `Bearer`, and PEM keys at any length. |
| **5. `browser_read` `#fragment` + Graceful Close** | Standard documentation/GitHub anchor URLs work; transient tab-close hiccups don't waste a completed read. | Zero security tradeoff: URI fragments are never transmitted over HTTP. |

---

## 3. Complexity vs. Utility Matrix (Weighted)

Weights: **Robustness Gain (40%)**, **Security Preservation (30%)**, **Implementation Simplicity (20%)**, **Zero-Regression Risk (10%)**.

| Item | Robustness (40%) | Security Preserved (30%) | Simplicity (20%) | Low Risk (10%) | **Weighted Score (/10)** |
|---|---:|---:|---:|---:|---:|
| **#1 Hard vs Advisory Injection Signals** | **10.0** | 9.2 | 9.5 | 9.5 | **9.61** |
| **#2 Weak-Model Denylist (`strong-model?`)** | **9.8** | 8.8 | 9.8 | 9.5 | **9.47** |
| **#4 Calibrated Exfil Detector + Loopback `curl`** | **9.5** | 9.2 | 9.0 | 9.2 | **9.28** |
| **#5 `browser_read` `#fragment` + Graceful Close** | **9.0** | **10.0** | 9.8 | 9.8 | **9.54** |
| **#3 Quote-Aware Shell Floor** | **9.2** | 9.4 | 8.8 | 9.2 | **9.18** |
| **#6 A2A Same-Origin Redirects** | 5.0 | 8.5 | 6.5 | 7.5 | **6.60** |

---

## 4. Actionable Recommendation (Rich Hickey Path)

Implement **#1 through #5** immediately (all score **> 9.1 / 10**) via Red/Green TDD, and skip **#6** (YAGNI — no active A2A redirect failure). Keep every existing security test green while adding regression tests proving the 5 robustness failures are eliminated.
