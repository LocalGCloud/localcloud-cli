## Why

Three of LocalCloud's database services — Firestore, Pub/Sub, and Bigtable — lose all data on Docker restart. They use Google's official gcloud emulators which are closed-source binaries with no persistence support. This forces developers to re-seed data after every container restart, breaking the developer workflow and contradicting Constitution Principle II ("State MUST persist across container restarts via Docker volume mounts by default").

After enabling persistence for Spanner (LevelDB fork) and BigQuery (SQLite `--database` flag), these three services are the remaining gaps. All other services (GCS, Secret Manager, Cloud Tasks, Logging, Monitoring, Memorystore) already persist via filesystem or PostgreSQL.

## What Changes

This proposal documents all researched options for each service with a recommended approach. **Implementation is deferred** — this proposal captures the research and recommendations for future execution.

---

### Firestore — 5 Options Evaluated

| # | Option | Effort | Persistence | SDK Compat | Recommendation |
|---|--------|--------|-------------|------------|----------------|
| 1 | Firebase Emulator Suite (`--export-on-exit` / `--import`) | Medium | Binary export | Full | Short-term |
| 2 | Undocumented JAR flag `--seed_from_export` | Low | Import only | Full | Quick win (import half) |
| 3 | Third-party emulators | N/A | N/A | N/A | None viable |
| 4 | REST wrapper (periodic export/import via Firestore REST API) | Medium | JSON on disk | Full | Pragmatic |
| 5 | Java facade (PostgreSQL-backed, 12 RPCs) | High (2-3 wks) | Native PostgreSQL | ~90% | Long-term |

**Key finding**: The gcloud Firestore emulator JAR (`cloud-firestore-emulator.jar`) accepts an undocumented `--seed_from_export <path>` flag (discovered in Firebase CLI source code `downloadableEmulators.ts`). This natively loads previously exported data on startup. The emulator also exposes an internal REST API for export, though the exact endpoint is undocumented.

**Recommended approach**: **Option 2+4 (Hybrid)** — Use the REST API to periodically export Firestore documents to a directory on the Docker volume, then pass `--seed_from_export` on startup. This provides persistence with ~1 week of effort and zero risk to SDK compatibility.

**Long-term**: Option 5 (PostgreSQL facade) aligns with the architecture for Secret Manager, Cloud Tasks, etc. 12 RPCs needed for basic CRUD + queries. The `StructuredQuery`-to-SQL translation is the hardest part (~600 lines). Total estimate: ~4,000 lines, 2-3 weeks.

---

### Pub/Sub — 5 Options Evaluated

| # | Option | Effort | Config Persistence | Message Persistence | SDK Compat | Recommendation |
|---|--------|--------|--------------------|---------------------|------------|----------------|
| 1 | Official emulator flags | N/A | No | No | N/A | Not possible |
| 2 | Third-party emulators (deltio, pstest, etc.) | N/A | No | No | Partial | None viable |
| 3 | REST wrapper (export topics/subs, re-create on startup) | 2-3 days | Yes | No | 100% | Short-term |
| 4 | Full Java facade (25 RPCs, PostgreSQL-backed) | 3-5 wks | Yes | Yes | Must verify | Long-term |
| 5 | Hybrid (keep gcloud emulator + PostgreSQL config sync) | 3 days | Yes | No | 100% | **Recommended** |

**Key finding**: No Pub/Sub emulator — official or third-party — supports persistence. The landscape was exhaustively surveyed: `deltio` (Rust, 28 stars), Google's own `pstest` (Go, in-process only), and several Docker wrappers. All are in-memory only.

**Recommended approach**: **Option 5 (Hybrid)** — Keep the gcloud Pub/Sub emulator for message processing (it's battle-tested for gRPC including StreamingPull). Add PostgreSQL tables for topic/subscription configuration. A background sync service periodically mirrors emulator state to PostgreSQL, and on startup restores topics/subscriptions before the gateway opens for traffic. Messages remain ephemeral (acceptable for local dev — they're transient by nature). Effort: 3 days.

**Long-term**: Option 4 (full facade) would provide message persistence too, but StreamingPull (bidirectional streaming with flow control, ack deadline management) is extremely complex — the reference implementation in Google's `pstest` is ~2,500 lines of Go. Estimate: 3,000-4,000 lines Java, 3-5 weeks.

---

### Bigtable — 5 Options Evaluated

| # | Option | Effort | Persistence | Filter Compat | Architecture Fit | Recommendation |
|---|--------|--------|-------------|---------------|-----------------|----------------|
| 1 | Official emulator flags | N/A | None | N/A | N/A | Not possible |
| 2a | `little_bigtable` (Go, SQLite) | 1-2 days | SQLite file | Same as cbtemulator | Poor (breaks PG pattern) | **Short-term** |
| 3 | Full Java facade (10 core RPCs, 81 total) | 3-5 wks | PostgreSQL | Partial (filters hard) | Excellent | Long-term |
| 4a | gRPC proxy wrapper | 2-3 wks | Excellent | Full | Medium | Complex |
| 4b | Polling sidecar | 1 wk | Eventual | Full | Medium | Medium |

**Key finding**: [`bitly/little_bigtable`](https://github.com/bitly/little_bigtable) is an open-source Go fork of Google's `bttest` package that adds **SQLite3 persistence**. It's a drop-in replacement for `cbtemulator` — same gRPC API, same `BIGTABLE_EMULATOR_HOST` env var, just backed by a SQLite file instead of RAM. Apache 2.0 licensed, 22 stars, 95 commits, latest release v0.1.1 (May 2024).

**Recommended approach**: **Option 2a (little_bigtable)** — Replace `cbtemulator` with `little_bigtable` in the Dockerfile. Add `--db-path=/var/lib/localcloud/bigtable-data/bigtable.db` flag. This is a 1-2 day change that provides immediate persistence. The SQLite file lives on the Docker volume and survives restarts.

**Trade-off**: Data lives in SQLite instead of PostgreSQL, which breaks the single-database pattern. The existing `bigtable_data` PostgreSQL table (used by Data Browser browse/mutate) would become disconnected from the actual emulator state. Mitigation: either remove the PostgreSQL browse/mutate and proxy directly to the emulator's gRPC API, or add a sync layer.

**Long-term**: Option 3 (full Java facade) would unify Bigtable with the other facade services. The existing `bigtable_data` PostgreSQL table and browse/mutate endpoints are already 60% of the storage layer. The missing piece is the gRPC service implementation (10 core RPCs). The `ReadRows` filter engine (~20 filter types with composition) is the hardest part. Estimate: 3-5 weeks.

---

## Phased Implementation Recommendation

### Phase 1 — Quick Wins (1-2 weeks)

| Service | Approach | Effort | Result |
|---|---|---|---|
| Firestore | `--seed_from_export` + REST export wrapper | 1 week | Config + document persistence |
| Pub/Sub | Hybrid (PostgreSQL config sync + gcloud emulator) | 3 days | Topic/subscription persistence |
| Bigtable | Replace with `little_bigtable` (SQLite) | 1-2 days | Full data persistence |

After Phase 1: **All 11 database services persist across Docker restarts.**

### Phase 2 — Architecture Alignment (optional, 6-10 weeks)

| Service | Approach | Effort | Result |
|---|---|---|---|
| Firestore | Java facade (12 RPCs, PostgreSQL) | 2-3 weeks | Native persistence, consistent architecture |
| Pub/Sub | Java facade (10+ RPCs, PostgreSQL) | 3-5 weeks | Message persistence, full control |
| Bigtable | Java facade (10 RPCs, PostgreSQL) | 3-5 weeks | Single-database pattern, full control |

Phase 2 replaces closed-source binaries with in-house facade emulators, matching the architecture of Secret Manager, Cloud Tasks, Logging, Monitoring, and Memorystore. This eliminates all dependencies on Google's closed-source emulator binaries.

## Capabilities

### New Capabilities

- `firestore-persistence`: Persist Firestore documents across Docker restarts
- `pubsub-persistence`: Persist Pub/Sub topic and subscription configuration across Docker restarts
- `bigtable-persistence`: Persist Bigtable tables and row data across Docker restarts

### Modified Capabilities

_None_

## Impact

### Phase 1
- **Dockerfile**: Add `little_bigtable` binary, add Firestore export directory
- **supervisord.conf**: Replace `cbtemulator` with `little_bigtable`, add `--seed_from_export` to Firestore
- **Java server**: Add `PubSubPersistenceSync` class, add Firestore REST export wrapper
- **PostgreSQL schema**: Add `pubsub_topics` and `pubsub_subscriptions` tables

### Phase 2
- **Java server**: 3 new facade emulators (~10,000 lines total)
- **build.gradle**: Add proto-google-cloud-firestore-v1, proto-google-cloud-bigtable-v2 dependencies
- **supervisord.conf**: Remove 3 external emulator entries
- **services.yaml**: Change 3 services from `type: external` to `type: facade`
