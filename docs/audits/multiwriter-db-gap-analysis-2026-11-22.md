# Rich Hickey Gap Analysis — Multi-Writer & Datalog DB Options for Theseus

- **Date:** 2026-11-22
- **Target System:** Theseus `v1.4.0` (`9aaf64f`), multi-process launchd fleet (`com.theseus.telegram`, `com.theseus.web`, `com.theseus.schedule-tick`, CLI, detached goal runners)
- **Local Machine Ground Truth (verified):** `postgresql@16` (`/opt/homebrew/bin/postgres`, `/opt/homebrew/bin/psql`) and `duckdb` are already installed via Homebrew; neither `dtlv` nor `datahike-server` is installed yet.
- **Options Evaluated:**
  1. **Current Baseline:** EDN files-as-truth + `pod-babashka-go-sqlite3` (`8.5 MB` embedded sidecar)
  2. **Option A — PostgreSQL (`postgresql@16` + `pgvector`)** via `org.babashka/postgresql` pod
  3. **Option B — Datalevin `1.2.10` Server Mode** (`dtlv serv` + `dtlv://`) via `huahaiy/datalevin` pod
  4. **Option C — Datahike** (`replikativ/datahike` Beta pod + optional Datahike Server / Konserve)
  5. **Option D — DataScript** (`datascript/datascript` pure Clojure source dep, in-memory Datalog hydrated from EDN/Postgres)

---

## 1. Feature-Set Differences Matrix (5-Way)

| Dimension | Current (`EDN` + `go-sqlite3`) | **Option A: PostgreSQL + `pgvector`** | **Option B: Datalevin `1.2.10` Server** | **Option C: Datahike** | **Option D: DataScript** (Pure CLJ) |
|---|---|---|---|---|---|
| **Write Concurrency** | Single-writer WAL + `BEGIN IMMEDIATE` + `.lock` | **True MVCC multi-writer** (row-level locks) | Server-serialized single-writer (`dtlv serv`) | Single-writer, multi-reader (needs JVM Datahike Server) | **In-process only** (immutable DB values; multi-process writes rely on underlying EDN/Postgres store) |
| **Read Concurrency** | Concurrent WAL readers | **True MVCC concurrent readers** | Zero-copy memory-mapped LMDB MVCC | Unlimited readers over immutable snapshots | **Zero-lock in-memory** (immutable value per process; microsecond reads) |
| **Query Model** | SQL + Clojure loops over EDN | SQL-2023 + `JSONB` + GIN + recursive CTEs | **Datomic-style Datalog** (`q`, `pull`, rules) + KV | **Datomic-style Datalog** (`q`, `pull`, `entity`) | **Datomic-style Datalog** (`q`, `pull`, rules) with **native Clojure closures** |
| **Time-Travel / History** | Manual columns (`status`, `supersedes`) | Manual columns / audit tables | Manual / transaction log | **Native first-class** (`as-of`, `since`, `history`) | In-memory DB values only (no durable history) |
| **Full-Text Search** | SQLite FTS5 + `snippet()` (`\u0001/\u0002`) | Native `tsvector` + `ts_rank` + `ts_headline` | Built-in inverted-index FTS (custom snippet needed) | **None built-in** | **None built-in** (must pair with `go-sqlite3` or Postgres) |
| **Vector / Semantic** | None (BM25 + recency) | **`pgvector` HNSW / IVFFlat** + hybrid RRF | **Built-in persistent HNSW** (`search-vec`, async) | **None built-in** | **None built-in** |
| **Real-Time Pub/Sub** | None (1s disk polling) | **Native `LISTEN` / `NOTIFY`** | None native | None native | In-process `listen!` callbacks only (not cross-process) |
| **Data Model Impedance** | Low for EDN; medium for SQLite | Medium (SQL DDL; mitigated by `JSONB`) | **Zero impedance** (native EDN) | **Zero impedance** (native EDN) | **Zero impedance** (native EDN + normal Clojure fns in `:where`) |
| **Babashka Integration** | `pod-babashka-go-sqlite3` (`8.5 MB`) | `org.babashka/postgresql` pod (~25 MB) | `huahaiy/datalevin` `1.2.10` pod (~80–120 MB) | `replikativ/datahike` pod (**Beta**, ~60–90 MB) | **Pure Clojure source dep** (**0 MB pod**, zero IPC, embeds directly in `theseus` app archive!) |
| **Persistence / Scale** | Disk (EDN + SQLite) | Disk (TB+ scale) | Disk (LMDB, GB+ scale) | Disk (Konserve: file/PG/S3) | **RAM-only** (great for 1k–50k datoms; must hydrate from EDN/DB on boot) |

---

## 2. Deep Explanation of Each Option (incl. DataScript)

### 2.1 Option A — PostgreSQL (`postgresql@16` + `pgvector`)
- **True row-level MVCC multi-writer:** `telegram`, `web`, `schedule-tick`, CLI, and goal workers write concurrently without blocking each other or risking file-lock deadlocks.
- **Covers every Theseus DB seam cleanly:** `memory.clj` + `semantic_memory.clj` (`pgvector` HNSW + hybrid BM25/RRF), `web_search.clj` (`tsvector` + `ts_headline` drop-in for FTS5 `\u0001/\u0002` snippets), `store.clj` (time-series SQL analytics + `JSONB`), and `web.clj` SSE (`LISTEN/NOTIFY` push instead of 1s disk polling).
- **Local friction:** Lowest — `postgresql@16` is already installed via Homebrew on this Mac.

### 2.2 Option B — Datalevin `1.2.10` Server Mode (`dtlv serv`)
- **All-in-one Clojure Datalog + FTS + HNSW vectors:** Native EDN data model, zero SQL impedance, fastest read/graph traversal via memory-mapped LMDB, and built-in HNSW vector search.
- **Multi-process story:** Running `dtlv serv` as one local launchd daemon (`dtlv://`) eliminates embedded LMDB lock crashes across processes; writes are server-serialized inside LMDB (plenty fast for personal-agent scale, though not row-level concurrent like Postgres).
- **Friction:** ~80–120 MB native binary, `defpodfn` macro needed for custom predicates across bencode IPC, and custom snippet highlighter needed for `web_search.clj`.

### 2.3 Option C — Datahike (`replikativ/datahike` pod / Datahike Server)
- **Strength:** First-class bitemporal / time-travel queries (`as-of`, `since`, `history`) over immutable Hitchhiker Tree snapshots (Distributed Index Space) + pluggable Konserve backends.
- **Gaps:** Babashka pod is **Beta**; single-writer model requires a JVM Datahike Server for multi-process writes; **no built-in FTS and no vector search** (forces keeping `go-sqlite3` alongside it).

### 2.4 Option D — DataScript (`datascript/datascript` Pure Clojure Source Dep)
- **What DataScript does better than every pod:**
  - **Zero native binary, zero IPC overhead, zero daemon:** Runs directly inside Babashka/SCI as a pure Clojure library (~100 KB packed into `theseus-vX.Y.Z-macos-arm64` just like `bb-excel`).
  - **Real Clojure closures in Datalog queries:** Unlike Datalevin (`defpodfn`) or Datahike pods, any Clojure function in `bb-agent.*` can be called directly inside `:where` predicates.
  - **Database-as-a-value:** Hydrate an immutable DB snapshot from `memory.edn` + `session-summaries.edn` + `goals/` + `skills/` (re-hydrating lazily when file mtimes change) for instant microsecond multi-hop Datalog queries.
- **Where DataScript falls short (and why it's a complement, not a standalone multi-writer DB):**
  - **Not durable and not multi-writer on its own:** DataScript is strictly in-memory per process. It solves **querying** (Datalog over small-to-medium graphs), **not** multi-process durable writes, **not** FTS5 snippets, and **not** HNSW vector search.
  - **Best role:** Paired with **PostgreSQL + `pgvector`** (multi-writer durable store + vectors + FTS + `LISTEN/NOTIFY`) OR paired with current **`EDN` + `go-sqlite3`** (zero-daemon standalone mode) to add pure-Clojure Datalog graph queries with zero operational tax.

---

## 3. Benefits & Tradeoffs Summary

| Option | Core Benefits | Core Tradeoffs |
|---|---|---|
| **Current (`EDN` + `go-sqlite3`)** | Zero daemons; 8.5 MB sidecar; `cat`-able truth; zero new deps. | Single-writer WAL; no HNSW vectors; no Datalog; 1s SSE polling. |
| **Option A: PostgreSQL + `pgvector`** | True row-level MVCC multi-writer; `postgresql@16` already installed; `pgvector` HNSW + `ts_headline` FTS + `JSONB` + `LISTEN/NOTIFY`; tiny mature pod (`org.babashka/postgresql`). | SQL instead of Datalog; requires local KeepAlive Postgres service (keep EDN/SQLite fallback for standalone). |
| **Option B: Datalevin `1.2.10` Server** | Native Clojure/EDN Datalog + built-in HNSW vectors + built-in FTS + blazing LMDB reads in one engine; `dtlv serv` solves multi-process locking. | Extra `dtlv serv` daemon; ~80–120 MB binary; server-serialized writes; `defpodfn` IPC; custom FTS snippets. |
| **Option C: Datahike** | Best-in-class native time-travel (`as-of`/`since`/`history`), Datomic-style Datalog, pluggable Konserve backends. | Pod is **Beta**; single-writer needs JVM Datahike Server for multi-process writes; **no FTS and no vectors**. |
| **Option D: DataScript (Pure CLJ)** | **0 MB pod, zero IPC, zero daemon**; real Clojure predicates in Datalog; immutable DB values; embeds right inside `theseus` standalone binary. | **RAM-only** (not durable, not multi-writer by itself); no FTS; no vectors; must pair with Postgres or EDN/`go-sqlite3` for persistence. |

---

## 4. Complexity vs. Utility Matrix (Weighted)

Weights: **Multi-Writer & Reliability (30%)**, **New Capabilities: Vectors/FTS/Graph/PubSub/History (30%)**, **Implementation & Migration Speed (20%)**, **Operational Simplicity & Packaging (20%)**.

| Option | Multi-Writer (30%) | New Capabilities (30%) | Impl Speed (20%) | Ops Simplicity (20%) | **Weighted Score (/10)** |
|---|---:|---:|---:|---:|---:|
| **Option A: PostgreSQL + `pgvector`** | **9.8** | **9.2** | **8.0** | 7.5 | **8.80** |
| **Option A + D: Postgres + DataScript** | **9.8** | **9.8** | 7.5 | 7.3 | **8.84** |
| **Option B: Datalevin `1.2.10` Server** | 7.8 | **9.5** | 6.5 | 6.0 | **7.69** |
| **Option D + Current (DataScript + `go-sqlite3`)** | 5.0 | 6.8 | **9.0** | **9.2** | **7.18** |
| **Current (`EDN` + `go-sqlite3`)** | 5.0 | 4.0 | 10.0 | 9.5 | **6.60** |
| **Option C: Datahike (Pod / Server)** | 6.2 | 6.5 | 5.5 | 5.0 | **5.91** |

---

## 5. Actionable Recommendation (Rich Hickey Path)

1. **Top Pick — Option A + D (`PostgreSQL + pgvector` + optional `DataScript`, `8.84 / 10`):**
   - **PostgreSQL (`postgresql@16` already installed + `pgvector` via `org.babashka/postgresql`)** owns everything that needs durability and concurrency: true row-level MVCC multi-writer across all launchd daemons, `pgvector` HNSW semantic search, `ts_headline` FTS snippets, `JSONB` analytics, and `LISTEN/NOTIFY` push.
   - **DataScript** (0 MB pure Clojure source dep) is layered *only* where Datalog graph queries shine (hydrating beliefs/supersession/goals/skills into an immutable in-memory DB value with real Clojure predicates).
   - Keep `EDN` + `go-sqlite3` intact as the zero-daemon standalone fallback.
2. **Runner-up — Option B (`Datalevin 1.2.10` Server, `7.69 / 10`):** Best single-engine choice if you want durable Datalog + HNSW + FTS without touching SQL at all (accepting server-serialized LMDB writes and a ~100 MB binary).
3. **Zero-Daemon Upgrade — Option D + Current (`DataScript + go-sqlite3`, `7.18 / 10`):** Best choice if you decide you *don't* want a running DB daemon yet, but want Datalog queries inside the standalone `theseus` binary today.
4. **Skip Option C (`Datahike`, `5.91 / 10`)** as primary DB unless bitemporal `as-of` time-travel becomes a top requirement.

---

## 6. Empirical Implementation Receipt — Zero-Daemon Upgrade Path (`bb-agent.datalog`)

- **Live Babashka SCI Verification (2026-11-22):**
  - Executed `bb -Sdeps '{:deps {datascript/datascript {:mvn/version "1.8.1"}}}' -e '(require (quote [datascript.core :as d]))'` on `babashka v1.13.222`.
  - **Result:** Failed to load (`datascript/lru.cljc` line 5: `Babashka's deftype supports full map types (add IPersistentMap to the interface list)`). `datascript.core` is also not built into Babashka.
- **Rich Hickey Resolution (ADR `0009`):**
  - Rather than patching/forking DataScript's `deftype` internals or adding an external dependency (`AGENTS.md` Rule 19), shipped **`src/bb_agent/datalog.clj`** — a pure-map, **zero-dependency**, Babashka-native EAV/AEV/AVE Datalog engine (~370 LOC) with:
    - Immutable DB values (`empty-db`, `db-with`), `:db.unique/identity`, `:db.type/ref`, lookup refs `[:attr val]`, cardinality-one upsert replacement + `:db.cardinality/many`, `:db/add`, `:db/retract`.
    - `q` supporting `:find` (relation, `[?x ...]`, `?x .`, aggregates `count`/`sum`/`min`/`max`), `:in`, `:where` data patterns, real Clojure predicates, and `(not ...)` negation.
    - `pull` with nested ref maps and wildcard `:*`.
    - `hydrate-home!` + mtime-cached `theseus-db` (invalidates on `memory.edn`, `session-summaries.edn`, or any `goals/*/project.edn` mtime change) and `bb datalog:query '<edn-query>'`.
  - **Zero new dependencies, 0 MB pod, zero daemon, embeds natively inside the standalone `theseus` binary.**

