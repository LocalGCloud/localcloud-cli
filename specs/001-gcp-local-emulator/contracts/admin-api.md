# Contract: Admin API

**Protocol**: REST (HTTP/1.1)
**Base Path**: `/_localcloud`
**Port**: 8080 (shared with REST gateway)

**Note on Blob Storage**: GCS blob/object data is stored by the external GCS emulator process (fake-gcs-server). The admin API's data browsing endpoints proxy to the external emulator for GCS data. No separate BlobStore component exists in the Java server.

## Endpoints

### Health Check

```
GET /_localcloud/health
```

**Response** (200 OK):
```json
{
  "status": "healthy",
  "uptime_seconds": 1234,
  "services": {
    "gcs": { "status": "running", "port": 4443, "protocol": "rest" },
    "pubsub": { "status": "running", "port": 8085, "protocol": "grpc" },
    "firestore": { "status": "running", "port": 8086, "protocol": "grpc" },
    "bigquery": { "status": "running", "port": 9050, "protocol": "rest" },
    "secretmanager": { "status": "running", "port": 8080, "protocol": "grpc" },
    "cloudtasks": { "status": "running", "port": 8080, "protocol": "grpc" },
    "spanner": { "status": "running", "port": 9010, "protocol": "grpc" },
    "bigtable": { "status": "running", "port": 8087, "protocol": "grpc" },
    "logging": { "status": "running", "port": 8080, "protocol": "grpc" },
    "monitoring": { "status": "running", "port": 8080, "protocol": "grpc" }
  },
  "project_id": "local-project",
  "persistence": true,
  "data_dir": "/var/lib/localcloud"
}
```

### Service List

```
GET /_localcloud/services
```

**Response** (200 OK):
```json
{
  "services": [
    {
      "id": "gcs",
      "name": "Cloud Storage",
      "status": "healthy",
      "port": 4443,
      "protocol": "rest",
      "endpoint": "http://localhost:4443",
      "env_var": "STORAGE_EMULATOR_HOST",
      "env_value": "http://localhost:4443",
      "request_count": 42
    }
  ]
}
```

### Request Log

```
GET /_localcloud/requests?limit=100&service=gcs&since=2026-03-09T00:00:00Z
```

**Query Parameters**:
- `limit` (integer, default 100, max 1000): Number of entries to return
- `service` (string, optional): Filter by service ID
- `since` (ISO 8601 timestamp, optional): Only entries after this time

**Response** (200 OK):
```json
{
  "requests": [
    {
      "id": 1,
      "timestamp": "2026-03-09T10:30:00Z",
      "service": "gcs",
      "method": "PUT",
      "path": "/storage/v1/b/my-bucket/o/file.txt",
      "status_code": 200,
      "duration_ms": 12,
      "request_size": 1024,
      "response_size": 256
    }
  ],
  "total": 42,
  "has_more": false
}
```

### Data Browse - Generic

```
GET /_localcloud/browse/{service}
GET /_localcloud/browse/{service}/{resource_type}
GET /_localcloud/browse/{service}/{resource_type}/{resource_id}
```

**Examples**:
- `GET /_localcloud/browse/gcs` → list buckets
- `GET /_localcloud/browse/gcs/buckets/my-bucket` → list objects in bucket
- `GET /_localcloud/browse/pubsub` → list topics
- `GET /_localcloud/browse/firestore/documents/users` → list documents in collection
- `GET /_localcloud/browse/bigquery/datasets` → list datasets
- `GET /_localcloud/browse/secretmanager/secrets` → list secrets (values redacted)

**Response**: Service-specific JSON payload with read-only data.

### Seed

```
POST /_localcloud/seed
Content-Type: application/yaml

<seed file contents>
```

**Response** (200 OK):
```json
{
  "status": "seeded",
  "total_records": 16,
  "services": {
    "gcs": 7,
    "pubsub": 4,
    "bigquery": 3,
    "secretmanager": 2
  }
}
```

### Reset

```
POST /_localcloud/reset
Content-Type: application/json

{
  "restore_seed": true
}
```

**Response** (200 OK):
```json
{
  "status": "success",
  "seed_restored": true,
  "records_restored": 5
}
```

### Environment Variables

```
GET /_localcloud/env?format=shell
GET /_localcloud/env?format=docker-compose
GET /_localcloud/env?format=json
```

**Response** (200 OK, format=shell):
```bash
export STORAGE_EMULATOR_HOST=http://localhost:4443
export PUBSUB_EMULATOR_HOST=localhost:8085
export FIRESTORE_EMULATOR_HOST=localhost:8086
export BIGTABLE_EMULATOR_HOST=localhost:8087
export SPANNER_EMULATOR_HOST=localhost:9010
export BIGQUERY_EMULATOR_HOST=http://localhost:9050
export GOOGLE_CLOUD_PROJECT=local-project
```

### Cloud Logging Browse

```
GET /_localcloud/browse/logging/entries?severity=ERROR&limit=50
```

**Response** (200 OK):
```json
{
  "entries": [
    {
      "timestamp": "2026-03-09T10:30:00Z",
      "severity": "ERROR",
      "log_name": "projects/local-project/logs/my-app",
      "text_payload": "Connection timeout",
      "resource": { "type": "global" },
      "labels": { "module": "auth" }
    }
  ]
}
```

### Cloud Monitoring Browse

```
GET /_localcloud/browse/monitoring/timeseries?metric_type=custom.googleapis.com/my_metric
```

**Response** (200 OK):
```json
{
  "time_series": [
    {
      "metric_type": "custom.googleapis.com/my_metric",
      "metric_labels": { "instance": "web-1" },
      "points": [
        { "timestamp": "2026-03-09T10:30:00Z", "value": 42.5 }
      ]
    }
  ]
}
```

### Spanner Query History

```
GET /_localcloud/query-history?service=spanner&limit=50&offset=0
```

**Query Parameters**:
- `service` (string, optional): Filter by service (only `spanner` currently supported)
- `limit` (integer, default 50, max 500): Number of entries
- `offset` (integer, default 0): Pagination offset

**Response** (200 OK):
```json
{
  "entries": [
    {
      "id": 1,
      "sql": "SELECT * FROM Users WHERE id = @param",
      "success": true,
      "duration_ms": 15,
      "row_count": 1,
      "instance": "my-instance",
      "database": "my-db",
      "executed_at": "2026-05-20T10:30:00.123456Z",
      "error_message": null
    }
  ],
  "total": 42,
  "has_more": false
}
```

Queries are recorded automatically when executed via `POST /_localcloud/query`
or `POST /_localcloud/query/batch`. Storage is PostgreSQL-backed via
`QueryHistoryRepository`.

### Spanner Database Stats (System Insights)

```
GET /_localcloud/browse/spanner/instances/{instance}/{database}/stats
```

**Response** (200 OK):
```json
{
  "database": "my-db",
  "instance": "my-instance",
  "tableCount": 5,
  "indexCount": 7,
  "searchIndexCount": 1,
  "vectorIndexCount": 0,
  "totalObjects": 13,
  "details": [
    { "type": "TABLE", "name": "Users", "columnCount": 8, "hasInterleaved": false },
    { "type": "INDEX", "name": "UsersByName" },
    { "type": "SEARCH_INDEX", "name": "UsersSearchIdx" },
    { "type": "VECTOR_INDEX", "name": "ItemsEmbeddingIdx" }
  ]
}
```

Stats are computed by parsing the Spanner emulator's DDL response — no
additional storage required.

### GraphQL API

```
POST /graphql
Content-Type: application/json

{
  "query": "{ spanner { instances } }"
}
```

**Response** (200 OK):
```json
{
  "data": {
    "spanner": {
      "instances": [ ... ]
    }
  }
}
```

The GraphQL endpoint uses `armeria-graphql` (wrapping `graphql-java`) and
supports queries across Spanner, BigQuery, Logging, Monitoring, and Query
History. See the GraphQL schema for available types and fields. WebSocket
subscriptions are supported via graphql-protocol.

### Spanner IAM Stubs

Instance-level:
- `POST /v1/projects/{project}/instances/{instance}:setIamPolicy`
- `GET /v1/projects/{project}/instances/{instance}:getIamPolicy`
- `POST /v1/projects/{project}/instances/{instance}:testIamPermissions`

Database-level:
- `POST /v1/projects/{project}/instances/{instance}/databases/{database}:setIamPolicy`
- `GET /v1/projects/{project}/instances/{instance}/databases/{database}:getIamPolicy`
- `POST /v1/projects/{project}/instances/{instance}/databases/{database}:testIamPermissions`

All endpoints return permissive responses: `getIamPolicy` returns a default
bindings with `roles/spanner.admin`, and `testIamPermissions` grants every
requested permission. These stubs exist to unblock SDK code that requires IAM
checks before accessing Spanner resources.
