## Context

The LocalCloud Data Browser currently provides read-only browsing across 9 services (GCS, Pub/Sub, BigQuery, Spanner, Secret Manager, Cloud Tasks, Logging, Monitoring, Memorystore). Firestore and Bigtable show only connection info. The seed.yaml has minimal data (3 rows per table) and missing coverage for Firestore and Bigtable.

The system follows a proxy architecture: the frontend calls Flask backend endpoints (`/api/browse/*`), which proxy to Java admin API endpoints (`/_localcloud/browse/*`), which in turn call the actual emulator APIs or query PostgreSQL directly for facade services.

## Goals / Non-Goals

**Goals:**
- Add CRUD (Create, Update, Delete) operations to the Data Browser for all browsable services
- Expand seed data to 10-20 rows per table across all database services, add Firestore and Bigtable seed data
- Promote Firestore and Bigtable from "connection only" to full data browsing
- Validate Spanner persistence across Docker restarts
- All mutations go through emulator APIs, not direct DB writes

**Non-Goals:**
- Schema modification (ALTER TABLE, CREATE TABLE) from the UI
- Bulk import/export
- Transaction support in the UI
- Real-time data sync / live updates
- Advanced query builder (beyond the existing SELECT * LIMIT 50)

## Decisions

### D1: Mutation routing through emulator APIs, not direct PostgreSQL

All CRUD operations will go through the emulator's own API surface (Spanner REST, GCS REST, BigQuery REST, etc.) rather than writing directly to PostgreSQL tables. This ensures data consistency with the emulator's internal state and validates the same code path as SDK users.

**Alternative considered**: Direct PostgreSQL writes for facade services (Secret Manager, Cloud Tasks, Memorystore). Rejected because it bypasses validation logic and could desync in-memory state from DB state.

**Exception**: Memorystore mutations go through the Redis RESP protocol (port 6379), not PostgreSQL, maintaining consistency with how the emulator works.

### D2: New MutateService class parallel to BrowseService

Create a new `MutateService.java` class registered at `/_localcloud/mutate` prefix, following the same pattern as BrowseService but handling POST/PUT/DELETE requests. This keeps read and write concerns separated.

```
/_localcloud/browse/{service}/...   → BrowseService (GET, existing)
/_localcloud/mutate/{service}/...   → MutateService (POST/PUT/DELETE, new)
```

**Alternative considered**: Adding write methods to BrowseService. Rejected to maintain single-responsibility and keep the existing read paths unchanged.

### D3: Firestore browsing via REST API

Firestore emulator exposes a REST API alongside gRPC. Use the REST API at `http://localhost:8086/v1/projects/{projectId}/databases/(default)/documents/{collection}` for both browsing and mutations.

The Firestore REST API supports:
- `GET /documents/{collection}` — list documents
- `GET /documents/{collection}/{docId}` — get document
- `POST /documents/{collection}` — create document
- `PATCH /documents/{collection}/{docId}` — update document
- `DELETE /documents/{collection}/{docId}` — delete document

### D4: Bigtable browsing via REST API (Admin + Data)

Bigtable emulator supports gRPC only. For browsing, use the Bigtable Admin v2 REST API for listing tables and the Data v2 REST API for reading rows. The Java `HttpClient` will call these endpoints.

- Admin: `GET /v2/projects/{project}/instances/{instance}/tables` — list tables
- Data: `POST /v2/projects/{project}/instances/{instance}/tables/{table}:readRows` — read rows

If the Bigtable emulator doesn't support REST, fall back to implementing a lightweight gRPC client within BrowseService using the existing proto dependencies.

### D5: Frontend CRUD UI pattern

Use a consistent modal-based pattern across all services:
- **Add**: Modal form with fields based on service schema. "Add Row" / "Add Key" / "Upload Object" button above the data table.
- **Edit**: Inline edit or modal pre-filled with current values. "Edit" icon per row.
- **Delete**: Confirmation dialog with record details. "Delete" icon per row.

The DataBrowser.jsx component will gain a shared `CrudModal` component that adapts its fields based on the active service and operation.

### D6: Seed data expansion strategy

Expand seed.yaml with a schema.org Person-based dataset (consistent with existing data) plus domain-specific data:
- **Spanner**: Add `orders_db` database with `Orders`, `OrderItems` (interleaved), `Products` tables. 10-15 rows each.
- **Firestore**: Add `users` and `products` collections with 10+ documents each, including nested fields.
- **Bigtable**: Add `user-activity` table with column families `profile` and `activity`. 10+ row keys.
- **BigQuery**: Expand `page_views` to 15 rows, add `orders` table with 10 rows.
- **Memorystore**: Add lists, sets, sorted sets in addition to existing strings and hashes.

### D7: Firestore and Bigtable seed implementation

**Firestore**: Add seeding via Firestore REST API (`POST /documents/{collection}`) in SeedService.java. The seed.yaml format:
```yaml
firestore:
  collections:
    - name: "users"
      documents:
        - id: "user-1"
          fields:
            name: "Jay Senjaliya"
            email: "JaySen@apache.com"
```

**Bigtable**: Add seeding via Bigtable gRPC MutateRows API or REST equivalent. The seed.yaml format:
```yaml
bigtable:
  instances:
    - name: "local-instance"
      tables:
        - name: "user-activity"
          columnFamilies: ["profile", "activity"]
          rows:
            - key: "user#1"
              cells:
                profile:name: "Jay Senjaliya"
                activity:last_login: "2026-04-08"
```

## Risks / Trade-offs

**Firestore REST API availability** → The Firestore emulator may not expose REST endpoints on port 8086 (it's primarily gRPC). Mitigation: Test REST endpoint availability first; if unavailable, use a lightweight gRPC client with existing proto dependencies.

**Bigtable emulator REST support** → The Bigtable emulator is gRPC-only. Mitigation: Implement browsing/mutations via gRPC client using existing `com.google.bigtable.v2` protos already in the dependency tree.

**Data consistency on mutation** → After a mutation, the Data Browser needs to refresh to show updated data. Risk of stale reads if caching is aggressive. Mitigation: Force-refresh the data table after any mutation completes.

**Spanner persistence dependency** → Validating Spanner persistence requires the forked emulator binary. If it's not available in the Docker image, persistence tests will be skipped. Mitigation: Document the build dependency clearly; use conditional test logic.

**BigQuery row deletion** → BigQuery doesn't support simple row DELETE without a WHERE clause on a primary key, and the emulator may not support DML DELETE. Mitigation: If DELETE is unsupported, disable the delete button for BigQuery rows and show an informational message.

**Seed data idempotency** → Expanded seed data must be idempotent (safe to re-run). Spanner already uses `insertOrUpdate` mutations. Firestore and Bigtable seeding must follow the same pattern. GCS bucket creation already ignores 409 conflicts.
