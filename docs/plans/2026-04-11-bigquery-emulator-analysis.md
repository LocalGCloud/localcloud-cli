# BigQuery Emulator Analysis: Coverage, Limitations & Recommendations

**Date:** 2026-04-11
**Scope:** Evaluate how much the BigQuery emulator in LocalCloud covers compared to production BigQuery, identify gaps, and recommend next steps.

---

## 1. Current State

**Emulator:** [goccy/bigquery-emulator](https://github.com/goccy/bigquery-emulator) (Go, MIT license)
**Backend:** SQLite (via go-zetasqlite — translates Google ZetaSQL AST to SQLite queries)
**Maintainer:** Single individual (personal project)
**Open issues:** 145+ (~122 labeled bugs)

### How It Runs in LocalCloud

- Binary: `ghcr.io/goccy/bigquery-emulator:latest` (amd64-only)
- Ports: 9050 (REST), 9060 (gRPC)
- Persistence: SQLite file at `/var/lib/localcloud/bigquery-data/bigquery.db`
- Managed by supervisord inside the LocalCloud container
- On Apple Silicon: runs via QEMU emulation (significant performance overhead)

### What Our Demo Tests Exercise

The Python SDK demo (`examples/python-sdk-demo/services/bigquery_demo.py`) tests 12 operations:
- Create/delete datasets
- Create tables with schema
- Insert rows (tabledata.insertAll)
- SELECT with WHERE, GROUP BY, ORDER BY, LIMIT
- JOIN queries, subqueries, UNION

These all work. The problem is what's *not* tested — which is most of what production BigQuery does.

---

## 2. Limitations Matrix

### SQL Features

| Feature | Production BigQuery | Emulator | Gap |
|---------|-------------------|----------|-----|
| Standard SQL (SELECT, JOIN, WHERE, GROUP BY, HAVING) | Full | Full | None |
| Window functions (RANK, ROW_NUMBER, NTILE, etc.) | Full | Full | None |
| CTEs (WITH clause) | Recursive + non-recursive | Non-recursive only | Moderate |
| Subqueries (scalar, IN, EXISTS, correlated) | Full | Full | None |
| Set operations (UNION, INTERSECT, EXCEPT) | Full | Full | None |
| 200+ built-in functions | Full | ~85% | Minor — most common functions work |
| Legacy SQL | Supported (deprecated) | Not supported | Acceptable — should use Standard SQL |
| PIVOT / UNPIVOT | Full | Supported | None |
| TABLESAMPLE | Supported | Not supported | Minor |
| SEARCH function / search indexes | Full | Not supported | Moderate — if used |

### DDL (Data Definition Language)

| Feature | Production BigQuery | Emulator | Gap |
|---------|-------------------|----------|-----|
| CREATE/DROP TABLE | Full | Supported | None |
| CREATE/DROP VIEW | Full | Supported | None |
| CREATE/DROP FUNCTION (UDFs) | Full | Supported | None |
| ALTER TABLE/VIEW/FUNCTION | Full | **Not supported** | **Significant** |
| CREATE TABLE with PARTITION BY | Full | **Not supported** | **Critical** |
| CREATE TABLE with CLUSTER BY | Full | **Buggy** | **Critical** |
| CREATE MATERIALIZED VIEW | Full | Not supported | Significant |
| CREATE EXTERNAL TABLE | Full (GCS, Sheets, Drive, Bigtable) | Not supported | Significant |
| CREATE PROCEDURE | Full | Not supported | Significant |
| Table OPTIONS (description, expiration, labels) | Full | Not supported | Moderate |
| Table snapshots / clones | Full | Not supported | Minor |

### DML (Data Manipulation Language)

| Feature | Production BigQuery | Emulator | Gap |
|---------|-------------------|----------|-----|
| INSERT | Full | Works | None |
| DELETE | Full | Works | None |
| UPDATE | Full | **Broken in some cases** | **Significant** |
| MERGE | Full | **Errors reported** | **Significant** |
| TRUNCATE TABLE | Full | **Not working** | Significant |

### Scripting & Procedural SQL

| Feature | Production BigQuery | Emulator | Gap |
|---------|-------------------|----------|-----|
| BEGIN...END blocks | Full | Supported | None |
| IF / CASE conditionals | Full | Supported | None |
| DECLARE / SET variables | Full | **Not supported** | **Critical** |
| Loops (LOOP, WHILE, FOR...IN) | Full | Not supported | Significant |
| EXECUTE IMMEDIATE | Full | Not supported | Significant |
| Stored procedures (CALL) | Full | Not supported | Significant |
| Exception handling | Full | Not supported | Moderate |
| Transactions (BEGIN/COMMIT/ROLLBACK) | Full | COMMIT only, no ROLLBACK | Significant |

### APIs

| API | Production BigQuery | Emulator | Gap |
|-----|-------------------|----------|-----|
| Jobs API (query, insert, get, list, cancel) | Full | ~80% | Minor |
| Tables API (CRUD) | Full | ~80% | Minor (NumRows not populated) |
| Datasets API (CRUD) | Full | ~90% | Minor |
| Tabledata.insertAll (streaming inserts) | Full | Works (JSON bugs) | Moderate |
| Storage Read API (gRPC) | Full | ~50% — panics, schema bugs | **Significant** |
| Storage Write API (gRPC) | Full | **~10% — fundamentally broken** | **Critical** |
| IAM API | Full | Not emulated | Acceptable for dev |
| Reservations API | Full | Not emulated | Acceptable for dev |
| Data Transfer Service | Full | Not emulated | Acceptable for dev |
| INFORMATION_SCHEMA views | Full | Not documented | Moderate |

### Data Types

| Type | Production BigQuery | Emulator | Gap |
|------|-------------------|----------|-----|
| INT64, FLOAT64, BOOL, STRING, BYTES | Full | Full | None |
| NUMERIC, BIGNUMERIC | Full | Supported | None |
| DATE, TIME, DATETIME | Full | Supported | None |
| TIMESTAMP | Full | Buggy (returns as float, timezone issues) | **Moderate** |
| ARRAY | Full | Supported (nil value bugs) | Minor |
| STRUCT / RECORD | Full | Supported (wrong results in some queries) | Moderate |
| JSON | Full | **Buggy** (JSON_EXTRACT_SCALAR, JSON_VALUE broken) | **Significant** |
| GEOGRAPHY | Full | Minimal (3 functions only) | Significant |
| INTERVAL | Full | Supported | None |
| RANGE | Full | Not supported | Minor |

### Enterprise Features

| Feature | Production BigQuery | Emulator | Gap |
|---------|-------------------|----------|-----|
| BigQuery ML (BQML) | Full | **Not supported** | Critical if used |
| Materialized views | Full | Not supported | Significant |
| Row-level security | Full | Not supported | Acceptable for dev |
| Column-level security / data masking | Full | Not supported | Acceptable for dev |
| CMEK / encryption | Full | Not supported | Acceptable for dev |
| Authorized views / datasets | Full | Not supported | Acceptable for dev |
| Scheduled queries | Full | Not supported | Moderate |
| BI Engine | Full | Not supported | Acceptable for dev |

---

## 3. Impact Assessment

### What Works Well for Developers

The emulator covers the **happy path** for most development workflows:
- Write SQL queries with standard SELECT/JOIN/WHERE/GROUP BY — works
- Create datasets and tables — works
- Insert test data via tabledata.insertAll — works (mostly)
- Run queries via the Jobs API — works
- Use the Python/Java BigQuery client SDKs — works with minor config

**For developers writing and testing basic SQL queries, the emulator is functional.**

### What Breaks in Practice

| Scenario | What Fails | Developer Impact |
|----------|-----------|-----------------|
| Tables with partitioning | CREATE TABLE ... PARTITION BY fails | Can't test partition-aware queries locally |
| Tables with clustering | CREATE TABLE ... CLUSTER BY is buggy | Can't test clustered table behavior |
| Streaming pipelines | Storage Write API broken | Dataflow/Beam pipelines can't be tested |
| Complex ETL scripts | DECLARE/SET/loops not supported | Multi-step SQL scripts can't run |
| JSON processing | JSON functions return wrong results | Data transformation tests fail |
| Schema evolution | ALTER TABLE not supported | Can't test schema migrations |
| UPDATE/MERGE operations | Buggy or broken | Incremental load patterns can't be tested |
| Large datasets | SQLite backend, single-threaded | Performance testing impossible |
| Apple Silicon Macs | QEMU overhead | Noticeably slower for all developers on M-series |

### Risk: Single-Maintainer Dependency

The emulator is a personal project by one individual. This means:
- Bug fixes are slow (months to years)
- Feature requests may be closed as NOT_PLANNED
- No SLA, no support contract, no guarantee of continuity
- If the maintainer stops, we inherit an unmaintained dependency

---

## 4. Alternatives Evaluation

### Option A: Keep goccy/bigquery-emulator (Current)

**Pros:** Already integrated, works for basic queries, active (if slow) development, MIT licensed.
**Cons:** All limitations above. Single-maintainer risk. amd64-only.
**Best for:** Teams that only need basic SQL query testing.

### Option B: Google's Official BigQuery Emulator

Google does **not** provide an official BigQuery emulator (unlike Pub/Sub, Firestore, Spanner, Bigtable which all have official emulators). The closest option is `gcloud beta emulators`, which does not include BigQuery.

**Status:** Not available. Google recommends using BigQuery sandbox (free tier, 1 TB query/month) for development — which requires cloud access.

### Option C: DuckDB-Based Emulator

There is an open proposal (goccy/bigquery-emulator#274) to replace SQLite with DuckDB as the backend. DuckDB is:
- Columnar storage (closer to BigQuery's architecture)
- Supports window functions, CTEs, JSON natively
- Better performance for analytical queries
- Has native ARM64 support

**Status:** Proposed but not implemented. Would require forking the emulator and contributing the change ourselves, or building a separate translation layer.

### Option D: BigQuery Sandbox (Free Tier)

Google offers a free sandbox: 1 TB/month queries, 10 GB storage, no credit card required.

**Pros:** 100% feature parity (it IS BigQuery). Free for light usage.
**Cons:** Requires internet. Requires Google Cloud project setup. Shared quota. Not truly local. Doesn't eliminate the provisioning/permissions problem we're trying to solve.

### Option E: Build a Thin API Shim Over DuckDB

Build our own minimal BigQuery-compatible REST API that translates BigQuery SQL to DuckDB. Cover the 80% case (datasets, tables, query jobs, insertAll) with better type fidelity.

**Pros:** We control it. DuckDB gives us columnar performance and ARM64 native. Can add partitioning/clustering semantics.
**Cons:** Significant engineering investment. Must track BigQuery API surface changes. Maintenance burden shifts to us.

---

## 5. Recommendation

### Short Term: Accept Current Limitations + Document Them

The goccy/bigquery-emulator covers ~70-80% of what developers need for daily development. The gaps (partitioning, streaming, scripting, JSON) are real but don't block most query-writing workflows. **For the business case, the emulator is "good enough" to justify shifting dev BigQuery spend locally.**

Actions:
- Document known limitations in LocalCloud's README or developer guide
- Add a compatibility matrix so developers know what works and what doesn't
- Add integration tests that exercise the emulator's supported features

### Medium Term: Evaluate DuckDB Backend

The DuckDB proposal (issue #274) is the most promising path to close the gap. DuckDB natively supports:
- Partitioning and clustering semantics
- Full JSON support
- Window functions with no bugs
- ARM64 natively (no QEMU)
- Better performance for analytical queries

Actions:
- Prototype a DuckDB-backed BigQuery shim (REST API compatibility layer)
- Evaluate if we can fork goccy/bigquery-emulator and swap the backend
- Contribute upstream if feasible

### Long Term: Watch for Google Official Emulator

Google may eventually release an official BigQuery emulator (they've done so for most other services). If they do, it would replace both our current emulator and any custom solution.

Actions:
- Monitor Google Cloud announcements
- Track the `gcloud beta emulators` command list for BigQuery additions

---

## Summary

| Aspect | Status |
|--------|--------|
| **Basic SQL development** | Works — covers ~90% of query writing |
| **Table management (CRUD)** | Works — with limitations on DDL |
| **Streaming / Storage Write API** | Broken — can't test pipelines locally |
| **Partitioning / Clustering** | Not supported — DDL will fail |
| **Scripting / Procedures** | Not supported — multi-step SQL fails |
| **Enterprise features** | None — ML, security, BI Engine absent |
| **Overall developer coverage** | ~70-80% of daily workflows |
| **Risk level** | Moderate — single-maintainer, amd64-only |
| **Recommendation** | Accept for now, evaluate DuckDB medium-term |
