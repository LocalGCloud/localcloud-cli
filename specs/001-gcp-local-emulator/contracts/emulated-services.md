# Contract: Emulated GCP Service APIs

**Principle**: Each emulated service implements a subset of the official GCP API. The subset covers the most commonly used operations (~80% of developer use cases).

## Cloud Storage (GCS)

**Protocol**: REST (JSON API v1)
**Base Path**: `/storage/v1`
**Env Var**: `STORAGE_EMULATOR_HOST=http://localhost:8080`

### Supported Operations

| Operation | Method | Path |
|-----------|--------|------|
| List buckets | GET | `/storage/v1/b?project={project}` |
| Create bucket | POST | `/storage/v1/b?project={project}` |
| Get bucket | GET | `/storage/v1/b/{bucket}` |
| Delete bucket | DELETE | `/storage/v1/b/{bucket}` |
| List objects | GET | `/storage/v1/b/{bucket}/o` |
| Upload object | POST | `/upload/storage/v1/b/{bucket}/o` |
| Get object metadata | GET | `/storage/v1/b/{bucket}/o/{object}` |
| Get object data | GET | `/storage/v1/b/{bucket}/o/{object}?alt=media` |
| Delete object | DELETE | `/storage/v1/b/{bucket}/o/{object}` |
| Copy object | POST | `/storage/v1/b/{srcBucket}/o/{srcObject}/copyTo/b/{dstBucket}/o/{dstObject}` |

### Not Supported (v1)
- Object versioning beyond basic generation tracking
- Bucket lifecycle management execution (rules stored but not enforced)
- Cross-bucket notifications (Pub/Sub notifications supported)
- Customer-managed encryption keys (CMEK)

---

## Pub/Sub

**Protocol**: gRPC
**Port**: 9020
**Env Var**: `PUBSUB_EMULATOR_HOST=localhost:9020`

### Supported gRPC Services

- `google.pubsub.v1.Publisher` - CreateTopic, GetTopic, ListTopics, DeleteTopic, Publish
- `google.pubsub.v1.Subscriber` - CreateSubscription, GetSubscription, ListSubscriptions, DeleteSubscription, Pull, StreamingPull, Acknowledge, ModifyAckDeadline

### Not Supported (v1)
- Schema validation
- BigQuery subscriptions
- Cloud Storage subscriptions
- Exactly-once delivery guarantees

---

## Firestore

**Protocol**: gRPC
**Port**: 9010
**Env Var**: `FIRESTORE_EMULATOR_HOST=localhost:9010`

### Supported gRPC Services

- `google.firestore.v1.Firestore` - GetDocument, ListDocuments, CreateDocument, UpdateDocument, DeleteDocument, BatchGetDocuments, BatchWrite, RunQuery, Listen (real-time)

### Not Supported (v1)
- Composite index enforcement (all queries succeed)
- Transaction isolation levels (basic optimistic locking)
- Aggregation queries (COUNT, SUM, AVG)

---

## BigQuery

**Protocol**: REST (Discovery API v2)
**Base Path**: `/bigquery/v2`
**Env Var**: `BIGQUERY_EMULATOR_HOST=http://localhost:8080`

### Supported Operations

| Operation | Method | Path |
|-----------|--------|------|
| List datasets | GET | `/bigquery/v2/projects/{project}/datasets` |
| Create dataset | POST | `/bigquery/v2/projects/{project}/datasets` |
| Get dataset | GET | `/bigquery/v2/projects/{project}/datasets/{dataset}` |
| Delete dataset | DELETE | `/bigquery/v2/projects/{project}/datasets/{dataset}` |
| List tables | GET | `/bigquery/v2/projects/{project}/datasets/{dataset}/tables` |
| Create table | POST | `/bigquery/v2/projects/{project}/datasets/{dataset}/tables` |
| Get table | GET | `/bigquery/v2/projects/{project}/datasets/{dataset}/tables/{table}` |
| Insert rows | POST | `/bigquery/v2/projects/{project}/datasets/{dataset}/tables/{table}/insertAll` |
| Create query job | POST | `/bigquery/v2/projects/{project}/jobs` |
| Get job | GET | `/bigquery/v2/projects/{project}/jobs/{job}` |
| Get query results | GET | `/bigquery/v2/projects/{project}/queries/{job}` |

### SQL Support
- SELECT, FROM, WHERE, GROUP BY, ORDER BY, LIMIT, OFFSET
- JOIN (INNER, LEFT, RIGHT, FULL, CROSS)
- Subqueries
- Common aggregate functions (COUNT, SUM, AVG, MIN, MAX)
- Basic string functions, date functions, math functions
- UNION, INTERSECT, EXCEPT

### Not Supported (v1)
- Wildcard table queries
- Partitioned/clustered tables
- Materialized views
- ML models (BQML)
- Scripting and stored procedures
- Streaming buffer semantics (inserts are immediately queryable)

---

## Secret Manager

**Protocol**: gRPC
**Port**: 8080 (via gateway)
**Env Var**: Set endpoint to `localhost:8080` with plaintext channel

### Supported gRPC Services

- `google.cloud.secretmanager.v1.SecretManagerService` - CreateSecret, GetSecret, ListSecrets, DeleteSecret, AddSecretVersion, GetSecretVersion, ListSecretVersions, AccessSecretVersion, DisableSecretVersion, EnableSecretVersion, DestroySecretVersion

### Not Supported (v1)
- Secret rotation notifications
- Customer-managed encryption keys
- IAM policy binding on individual secrets (use platform IAM mode)

---

## Cloud Tasks

**Protocol**: gRPC
**Port**: 8080 (via gateway)
**Env Var**: Set endpoint to `localhost:8080` with plaintext channel

### Supported gRPC Services

- `google.cloud.tasks.v2.CloudTasks` - CreateQueue, GetQueue, ListQueues, DeleteQueue, PauseQueue, ResumeQueue, CreateTask, GetTask, ListTasks, DeleteTask, RunTask

### Task Dispatch Behavior
- HTTP tasks are dispatched to the target URL from within the container
- For targets on the host machine, use `host.docker.internal` as the hostname
- Retry behavior follows queue configuration (max attempts, backoff)
- Failed tasks are retried on configurable schedule

### Not Supported (v1)
- App Engine tasks (only HTTP tasks)
- OAuth/OIDC token generation for task dispatch

---

## Spanner

**Protocol**: gRPC
**Port**: 9030
**Env Var**: `SPANNER_EMULATOR_HOST=localhost:9030`

### Supported gRPC Services

- `google.spanner.admin.instance.v1.InstanceAdmin` - CreateInstance, GetInstance, ListInstances, DeleteInstance
- `google.spanner.admin.database.v1.DatabaseAdmin` - CreateDatabase, GetDatabase, ListDatabases, DropDatabase, UpdateDatabaseDdl, GetDatabaseDdl
- `google.spanner.v1.Spanner` - CreateSession, GetSession, DeleteSession, ExecuteSql, Read, BeginTransaction, Commit, Rollback

### Not Supported (v1)
- Partitioned DML
- Change streams
- Foreign keys enforcement
- Multi-region configuration (all data local)

---

## Bigtable

**Protocol**: gRPC
**Port**: 9040
**Env Var**: `BIGTABLE_EMULATOR_HOST=localhost:9040`

### Supported gRPC Services

- `google.bigtable.admin.v2.BigtableTableAdmin` - CreateTable, GetTable, ListTables, DeleteTable, ModifyColumnFamilies
- `google.bigtable.v2.Bigtable` - ReadRows, MutateRow, MutateRows, CheckAndMutateRow, ReadModifyWriteRow, SampleRowKeys

### Not Supported (v1)
- Instance/cluster management (BigtableInstanceAdmin)
- Backup and restore
- Garbage collection policy enforcement (policies stored but not executed)

---

## Cloud Logging (Sink Mode)

**Protocol**: gRPC
**Port**: 8080 (via gateway)

### Supported gRPC Services

- `google.logging.v2.LoggingServiceV2` - WriteLogEntries, ListLogEntries, ListLogs

### Behavior
- All log entries are accepted and stored locally
- Entries are queryable via the admin browse API and dashboard
- No log-based metrics, sinks, or exclusions

---

## Cloud Monitoring (Sink Mode)

**Protocol**: gRPC
**Port**: 8080 (via gateway)

### Supported gRPC Services

- `google.monitoring.v3.MetricService` - CreateTimeSeries, ListTimeSeries, GetMetricDescriptor, ListMetricDescriptors, CreateMetricDescriptor

### Behavior
- All time series data is accepted and stored locally
- Metrics are queryable via the admin browse API and dashboard
- No alerting policies, uptime checks, or dashboards
