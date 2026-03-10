# Data Model: LocalCloud - GCP Local Emulator

**Date**: 2026-03-09
**Feature Branch**: `001-gcp-local-emulator`

## Core Platform Entities

### Project

Represents a GCP project context. All resources are scoped to a project.

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | Primary key, matches GCP naming rules (6-30 chars, lowercase, hyphens) |
| display_name | String | Optional human-readable name |
| created_at | Timestamp | Auto-set on creation |

### ServiceInstance

Tracks the lifecycle and configuration of each service emulator.

| Field | Type | Constraints |
|-------|------|------------|
| service_id | String | Primary key (e.g., "gcs", "pubsub", "firestore") |
| status | Enum | STARTING, RUNNING, STOPPED, ERROR |
| port | Integer | Assigned port number |
| protocol | Enum | REST, GRPC, BOTH |
| started_at | Timestamp | Nullable |
| request_count | Long | Running count of API requests handled |

### RequestLog

Ring buffer of recent API requests for debugging and dashboard display.

| Field | Type | Constraints |
|-------|------|------------|
| id | Long | Auto-increment |
| timestamp | Timestamp | Request time |
| service | String | Target service (e.g., "gcs", "pubsub") |
| method | String | HTTP method or gRPC method name |
| path | String | Request path |
| status_code | Integer | Response status code |
| duration_ms | Long | Processing time |
| request_size | Long | Request body size in bytes |
| response_size | Long | Response body size in bytes |

---

## Cloud Storage (GCS) Entities

### Bucket

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| name | String | Primary key, globally unique, 3-63 chars |
| location | String | Default "US" |
| storage_class | String | Default "STANDARD" |
| labels | JSON | Key-value pairs |
| created_at | Timestamp | Auto-set |
| versioning_enabled | Boolean | Default false |

### StorageObject

| Field | Type | Constraints |
|-------|------|------------|
| bucket | String | FK → Bucket |
| name | String | Object key (path) |
| generation | Long | Auto-increment per object name |
| content_type | String | MIME type |
| size | Long | Bytes |
| md5_hash | String | Content hash |
| metadata | JSON | Custom metadata key-value pairs |
| blob_path | String | Filesystem path to actual data |
| created_at | Timestamp | Auto-set |
| updated_at | Timestamp | Auto-set |

**Primary Key**: (bucket, name, generation)

---

## Pub/Sub Entities

### Topic

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| name | String | Topic short name |
| labels | JSON | Key-value pairs |
| message_retention | Duration | Optional, default none |

**Primary Key**: (project_id, name)
**Full resource name**: `projects/{project_id}/topics/{name}`

### Subscription

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| name | String | Subscription short name |
| topic | String | FK → Topic (full resource name) |
| ack_deadline_seconds | Integer | Default 10, range 10-600 |
| push_endpoint | String | Nullable (pull if null) |
| filter | String | Optional CEL filter expression |
| dead_letter_topic | String | Nullable FK → Topic |
| max_delivery_attempts | Integer | Default 5 (when dead letter configured) |

**Primary Key**: (project_id, name)

### Message

| Field | Type | Constraints |
|-------|------|------------|
| message_id | String | Auto-generated UUID |
| topic | String | FK → Topic |
| data | Bytes | Message payload (base64-encoded) |
| attributes | JSON | String-to-string map |
| publish_time | Timestamp | Auto-set on publish |
| ordering_key | String | Optional |

### MessageDelivery

Tracks delivery state per subscription.

| Field | Type | Constraints |
|-------|------|------------|
| message_id | String | FK → Message |
| subscription | String | FK → Subscription |
| delivery_attempt | Integer | Incremented on each redeliver |
| ack_status | Enum | PENDING, ACKED, NACKED, EXPIRED |
| ack_deadline | Timestamp | When ack expires |

---

## Firestore Entities

### FirestoreDocument

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| database_id | String | Default "(default)" |
| path | String | Full document path (e.g., "users/user-001") |
| data | JSON | Document fields |
| create_time | Timestamp | Auto-set |
| update_time | Timestamp | Auto-set on modification |

**Primary Key**: (project_id, database_id, path)

**State transitions**: Created → Updated (repeatable) → Deleted

---

## BigQuery Entities

### Dataset

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| dataset_id | String | Alphanumeric + underscores, max 1024 chars |
| location | String | Default "US" |
| description | String | Optional |
| labels | JSON | Key-value pairs |
| created_at | Timestamp | Auto-set |

**Primary Key**: (project_id, dataset_id)

### Table

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| dataset_id | String | FK → Dataset |
| table_id | String | Table name |
| schema | JSON | Array of field definitions (name, type, mode, nested fields) |
| description | String | Optional |
| created_at | Timestamp | Auto-set |
| row_count | Long | Running count |

**Primary Key**: (project_id, dataset_id, table_id)

### BigQueryJob

| Field | Type | Constraints |
|-------|------|------------|
| job_id | String | Auto-generated UUID |
| project_id | String | FK → Project |
| job_type | Enum | QUERY, LOAD, EXTRACT |
| state | Enum | PENDING, RUNNING, DONE |
| query | String | SQL query text (for QUERY jobs) |
| destination_table | String | Nullable target table reference |
| created_at | Timestamp | Auto-set |
| completed_at | Timestamp | Nullable |
| error | JSON | Nullable error details |

**State transitions**: PENDING → RUNNING → DONE

---

## Secret Manager Entities

### Secret

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| secret_id | String | Secret name |
| labels | JSON | Key-value pairs |
| created_at | Timestamp | Auto-set |

**Primary Key**: (project_id, secret_id)
**Full resource name**: `projects/{project_id}/secrets/{secret_id}`

### SecretVersion

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Secret |
| secret_id | String | FK → Secret |
| version_number | Integer | Auto-increment per secret |
| data | Bytes | Encrypted/stored secret payload |
| state | Enum | ENABLED, DISABLED, DESTROYED |
| created_at | Timestamp | Auto-set |

**Primary Key**: (project_id, secret_id, version_number)
**State transitions**: ENABLED → DISABLED → DESTROYED (or ENABLED → DESTROYED)

---

## Cloud Tasks Entities

### Queue

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| location | String | e.g., "us-central1" |
| queue_id | String | Queue name |
| state | Enum | RUNNING, PAUSED |
| max_dispatches_per_second | Double | Rate limit |
| max_concurrent_dispatches | Integer | Concurrency limit |
| max_attempts | Integer | Retry limit |
| min_backoff | Duration | Minimum retry backoff |
| max_backoff | Duration | Maximum retry backoff |

**Primary Key**: (project_id, location, queue_id)

### Task

| Field | Type | Constraints |
|-------|------|------------|
| task_id | String | Auto-generated or user-specified |
| queue | String | FK → Queue (full resource name) |
| http_method | String | GET, POST, etc. |
| url | String | Target HTTP endpoint |
| headers | JSON | HTTP headers |
| body | Bytes | Request body |
| schedule_time | Timestamp | When to dispatch |
| dispatch_count | Integer | Number of dispatch attempts |
| response_count | Integer | Number of responses received |
| state | Enum | PENDING, DISPATCHED, COMPLETED, FAILED |
| created_at | Timestamp | Auto-set |

**State transitions**: PENDING → DISPATCHED → COMPLETED/FAILED (retry → DISPATCHED)

---

## Spanner Entities

### SpannerInstance

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| instance_id | String | Instance name |
| display_name | String | Human-readable name |
| node_count | Integer | Default 1 (simulated) |
| state | Enum | CREATING, READY |

### SpannerDatabase

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| instance_id | String | FK → SpannerInstance |
| database_id | String | Database name |
| dialect | Enum | GOOGLE_STANDARD_SQL, POSTGRESQL |
| ddl_statements | JSON | Array of applied DDL statements |

**Primary Key**: (project_id, instance_id, database_id)

*Spanner tables and data are stored as dynamic H2 tables scoped by database.*

---

## Bigtable Entities

### BigtableInstance

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| instance_id | String | Instance name |
| display_name | String | Human-readable name |

### BigtableTable

| Field | Type | Constraints |
|-------|------|------------|
| project_id | String | FK → Project |
| instance_id | String | FK → BigtableInstance |
| table_id | String | Table name |
| column_families | JSON | Map of column family name → GC rules |

### BigtableCell

| Field | Type | Constraints |
|-------|------|------------|
| table_ref | String | Composite reference (project/instance/table) |
| row_key | Bytes | Row identifier |
| column_family | String | Column family name |
| column_qualifier | Bytes | Column name |
| timestamp_micros | Long | Cell version timestamp |
| value | Bytes | Cell value |

**Primary Key**: (table_ref, row_key, column_family, column_qualifier, timestamp_micros)

---

## Cloud Logging Entities

### LogEntry

| Field | Type | Constraints |
|-------|------|------------|
| id | Long | Auto-increment |
| project_id | String | FK → Project |
| log_name | String | Log identifier |
| severity | Enum | DEFAULT, DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY |
| text_payload | String | Nullable (one of text/json/proto) |
| json_payload | JSON | Nullable |
| timestamp | Timestamp | Log entry time |
| resource | JSON | Monitored resource descriptor |
| labels | JSON | Key-value pairs |
| insert_id | String | Deduplication key |

---

## Cloud Monitoring Entities

### TimeSeries

| Field | Type | Constraints |
|-------|------|------------|
| id | Long | Auto-increment |
| project_id | String | FK → Project |
| metric_type | String | Metric descriptor type |
| metric_labels | JSON | Metric label key-value pairs |
| resource_type | String | Monitored resource type |
| resource_labels | JSON | Resource label key-value pairs |

### MetricPoint

| Field | Type | Constraints |
|-------|------|------------|
| time_series_id | Long | FK → TimeSeries |
| timestamp | Timestamp | Data point time |
| value_type | Enum | INT64, DOUBLE, DISTRIBUTION |
| int64_value | Long | Nullable |
| double_value | Double | Nullable |

---

## Configuration Entity

### PlatformConfig

| Field | Type | Constraints |
|-------|------|------------|
| key | String | Primary key (e.g., "project_id", "services", "iam_mode") |
| value | String | Configuration value |
| source | Enum | DEFAULT, ENV_VAR, CONFIG_FILE, SEED_FILE |

### SeedState

Tracks seed file state for reset functionality.

| Field | Type | Constraints |
|-------|------|------------|
| seed_hash | String | SHA-256 of the seed file content |
| loaded_at | Timestamp | When seed was last applied |
| seed_content | Text | Full seed file YAML for reset reference |

---

## Entity Relationships

```
Project 1──* Bucket 1──* StorageObject
Project 1──* Topic 1──* Subscription
                   1──* Message 1──* MessageDelivery
Project 1──* FirestoreDocument
Project 1──* Dataset 1──* Table
Project 1──* BigQueryJob
Project 1──* Secret 1──* SecretVersion
Project 1──* Queue 1──* Task
Project 1──* SpannerInstance 1──* SpannerDatabase
Project 1──* BigtableInstance 1──* BigtableTable 1──* BigtableCell
Project 1──* LogEntry
Project 1──* TimeSeries 1──* MetricPoint
```
