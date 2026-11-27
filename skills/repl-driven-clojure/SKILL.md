---
name: repl-driven-clojure
description: Enforce and accelerate REPL-driven Clojure/Babashka development in Theseus using repl_eval, .nrepl-port, edamame pre-write checks, and hard-won Babashka gotchas. Triggers on editing .clj/.cljc/.cljs/.bb files, debugging Clojure tests, or running REPL experiments.
globs: *.clj, *.cljc, *.cljs, *.bb
metadata:
  version: 0.1.0
  verified: 2026-11-27
---

# /repl-driven-clojure — REPL-Driven Development in Theseus

## The 5-Step Loop (Enforced Mechanically)

1. **Evaluate first (`repl_eval`):** Before editing any `.clj`/`.cljc`/`.cljs`/`.bb` file, run `repl_eval` to inspect state, test a pure helper, or verify a hypothesis. `:repl-gate` blocks Clojure file writes until at least one `repl_eval` succeeds in the turn.
2. **Red test first:** Write a failing test (`deftest`), run it via `repl_eval` (using `babashka.classpath/add-classpath "test"`) or `bb` task, and confirm it fails for the right reason.
3. **Edit cleanly:** `write_file` and `edit_file` automatically parse-check Clojure files with `edamame.core` before touching disk (unbalanced parens are refused without corrupting the file). Prefer whole-file writes for large structural edits; never run parallel edits on the same file.
4. **Reload & Green:** In a live nREPL (`bb repl:server`), reload with `(require '[ns :as alias] :reload)` and re-run the test. Without nREPL, `repl_eval` falls back transparently to bounded `bb -e` (`10s` default, `30s` max).
5. **Verify gates bare (never piped):** Run `bb lint` (`0 errors, 0 warnings`), `bb test-roster` (`never-run: 0`), and `bb test:fast` (isolated 16s gate).

## Copy-Paste `repl_eval` Patterns

```clojure
;; 1. Inspect a namespace / pure function
{:ns "bb-agent.datalog"
 :code "(q '[:find (count ?e) . :where [?e :goal/slug _]] (theseus-db))"}

;; 2. Run one test namespace WITH bb.edn deps intact (never use `bb -cp src:test`!)
{:code "(require '[babashka.classpath :as cp] '[clojure.test :as t])
        (cp/add-classpath \"test\")
        (require '[bb-agent.repl-test] :reload)
        (t/run-tests 'bb-agent.repl-test)"}

;; 3. Reload edited namespace in live nREPL
{:code "(require '[bb-agent.core :as core] :reload)"}
```

## Babashka & Theseus Gotchas (Avoid Every One)

- **Never use `bb -cp src:test` for namespaces that transitively load `bb-excel`** (`web.clj`, `telegram_intake.clj`): `-cp` bypasses `bb.edn` `:deps`. Use `bb <task>` or `(babashka.classpath/add-classpath "test")`.
- **No `deftype` without `IPersistentMap` in Babashka SCI:** Libraries like `datascript 1.8.1` fail to load under `bb`. Stick to pure maps and built-ins (`edamame.core`, `bencode.core`, `cheshire.core`, `bb-agent.datalog`).
- **Category-strict equality:** `(= 0.5 1/2)` is `false` — use `==` or `(double ...)`.
- **Explicit `nil` values in maps:** `(long (:k m 0))` throws NPE when `{:k nil}`; always write `(long (or (:k m) 0))`.
- **`babashka.http-client` uses `:timeout`**, not `:request-timeout`.
- **Tool return contract:** `common/ok-result` uses `:status :ok`; `common/error-result` uses `:status :error` + `:error/message` and defaults `:executed? true` unless `{:executed? false}` is passed.
- **No piped receipts:** `bb test | tail` masks exit 1 with exit 0. Always run bare or `cmd; echo "EXIT=$?"`.
- **Register new tests in `bb.edn`:** Add both `[ns]` in `:requires` and `'ns` in `t/run-tests`, then confirm `bb test-roster` shows `never-run: 0`.
- **`babashka.process/shell` arity bug:** `(p/shell {:opts} ["prog" "arg"])` stringifies the vector and fails; always pass varargs `(apply p/shell {:opts} "prog" "arg" rest)` (`p/process` takes a vector).
- **Never `load-file` side-effecting scripts to syntax-check:** parse with `edamame.core/parse-string-all` or `bb lint`. Also watch for docstrings ending in `.)` closing an `ns` form early.
- **Opt-out escape hatch:** If a non-coding turn ever needs to bypass `:repl-gate`, set `:repl/enforce false` in `config.edn` (recognized by `bb doctor`).
