# ADR 0009 — Zero-Daemon Babashka-Native Datalog Engine (`bb-agent.datalog`)

- **Date:** 2026-11-22
- **Status:** Accepted
- **Gap Analysis:** [`docs/audits/multiwriter-db-gap-analysis-2026-11-22.md`](../../audits/multiwriter-db-gap-analysis-2026-11-22.md)

## Context

Following the 5-way multi-writer and Datalog database gap analysis (`EDN + go-sqlite3`, PostgreSQL + `pgvector`, Datalevin `1.2.10` server, Datahike, DataScript), the owner selected the **zero-daemon upgrade path**: keep Theseus's EDN files-as-truth + `pod-babashka-go-sqlite3` (`8.5 MB`) for persistence and FTS5, and add an in-memory Datalog query engine hydrated from Theseus's EDN state.

When verifying `datascript/datascript 1.8.1` live under `babashka v1.13.222` (`bb -Sdeps`), DataScript failed to load under SCI because `datascript/lru.cljc` declares a `deftype` without `IPersistentMap`.

## Decision

Implement a **zero-dependency, pure-map Babashka-native EAV Datalog engine** in `src/bb_agent/datalog.clj` (honoring `AGENTS.md` Rule 19 — zero new dependencies):

1. **Immutable EAV / AEV / AVE Store (`empty-db`, `db-with`, `resolve-eid`):**
   - Pure Clojure persistent maps (no `deftype`, 100% SCI and GraalVM native-image safe).
   - Supports `:db.unique/identity`, `:db.type/ref`, `:db.cardinality/one` (default, replaces prior value on upsert) & `:db.cardinality/many`, lookup refs `[:attr val]`, and `[:db/add | :db/retract eid-or-ref attr val]`.
2. **Datalog Query Engine (`q`) & `pull` API:**
   - `:find` relation, collection (`[?x ...]`), scalar (`?x .`), and aggregates (`count`, `sum`, `min`, `max`).
   - `:in` parameter bindings, multi-hop joins, real Clojure predicates inside `:where`, and `(not ...)` negation.
   - `pull` with explicit attribute specs, wildcard `:*`, and nested ref traversal maps.
3. **Zero-Daemon Mtime-Cached Hydrator (`hydrate-home!`, `theseus-db`) & CLI (`bb datalog:query`):**
   - Hydrates `state/memory.edn` (including `:memory/supersedes` ref edges), `state/session-summaries.edn`, and `goals/*/project.edn` into an immutable DB value.
   - Caches by `[home memory-mtime summaries-mtime goals-project-mtimes]`; returns the identical immutable DB value when unchanged and re-hydrates automatically when any EDN file changes.
   - Exposed via `bb datalog:query '<edn-query>'` in `bb.edn`.

## Consequences

- **Zero operational tax:** No extra daemon, no native pod binary, zero IPC serialization, embeds directly into the standalone `theseus` binary.
- **Honest scope:** This solves zero-daemon Datalog graph querying over Theseus's EDN state. If true cross-process MVCC multi-writer durability, `pgvector` HNSW search, or `LISTEN/NOTIFY` push is later required, Option A (`postgresql@16` + `pgvector`) remains the documented upgrade path in `docs/audits/multiwriter-db-gap-analysis-2026-11-22.md`.
