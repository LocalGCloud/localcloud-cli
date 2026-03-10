# Contract: Admin API

**Protocol**: REST (HTTP/1.1)
**Base Path**: `/_localcloud`
**Port**: 8080 (shared with REST gateway)

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
    "gcs": { "status": "running", "port": 8080, "protocol": "rest" },
    "pubsub": { "status": "running", "port": 9020, "protocol": "grpc" },
    "firestore": { "status": "running", "port": 9010, "protocol": "grpc" },
    "bigquery": { "status": "running", "port": 8080, "protocol": "rest" },
    "secretmanager": { "status": "running", "port": 8080, "protocol": "grpc" },
    "cloudtasks": { "status": "running", "port": 8080, "protocol": "grpc" },
    "spanner": { "status": "running", "port": 9030, "protocol": "grpc" },
    "bigtable": { "status": "running", "port": 9040, "protocol": "grpc" },
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
      "status": "running",
      "port": 8080,
      "protocol": "rest",
      "endpoint": "http://localhost:8080",
      "env_var": "STORAGE_EMULATOR_HOST",
      "env_value": "http://localhost:8080",
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
  "status": "success",
  "resources_created": {
    "gcs_buckets": 2,
    "gcs_objects": 5,
    "pubsub_topics": 3,
    "pubsub_subscriptions": 4,
    "firestore_documents": 10,
    "bigquery_datasets": 1,
    "bigquery_tables": 2,
    "secrets": 3
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
  "cleared": ["gcs", "pubsub", "firestore", "bigquery", "secretmanager", "cloudtasks", "spanner", "bigtable", "logging", "monitoring"],
  "seed_restored": true
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
export STORAGE_EMULATOR_HOST=http://localhost:8080
export PUBSUB_EMULATOR_HOST=localhost:9020
export FIRESTORE_EMULATOR_HOST=localhost:9010
export BIGTABLE_EMULATOR_HOST=localhost:9040
export SPANNER_EMULATOR_HOST=localhost:9030
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
