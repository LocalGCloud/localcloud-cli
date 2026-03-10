# Tasks: LocalCloud - GCP Local Emulator

**Input**: Design documents from `/specs/001-gcp-local-emulator/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not explicitly requested. Test tasks omitted. Add via TDD approach if desired.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Java server**: `localcloud-server/src/main/java/com/localcloud/`
- **Python CLI**: `localcloud-cli/src/localcloud/`
- **Docker**: repository root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize both Java and Python projects with all dependencies

- [x] T001 Create Java project with Gradle build file including Armeria, proto-google-cloud-*, H2, HikariCP dependencies in `localcloud-server/build.gradle`
- [x] T002 [P] Create Python CLI project with pyproject.toml including Click, docker, pyyaml, google-cloud-storage dependencies in `localcloud-cli/pyproject.toml`
- [x] T003 [P] Create directory structure for Java server per plan.md: `localcloud-server/src/main/java/com/localcloud/{gateway,admin,persistence,emulators,events,config}/`
- [x] T004 [P] Create directory structure for Python CLI per plan.md: `localcloud-cli/src/localcloud/{commands}/`
- [x] T005 [P] Create Dockerfile with eclipse-temurin:21-jre-jammy base, JVM tuning flags, volume mount, port exposure (8080, 9010, 9020, 9030, 9040) in `Dockerfile`
- [x] T006 [P] Create reference docker-compose.yml with LocalCloud service, ports, volumes, healthcheck, and example app service in `docker-compose.yml`
- [x] T007 [P] Create example seed.yaml with sample GCS buckets, Pub/Sub topics, Firestore documents, and secrets in `seed.yaml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T008 Implement configuration loading from environment variables and YAML in `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- [x] T009 Implement H2 embedded database setup with HikariCP connection pool, file-backed persistence to `/var/lib/localcloud/db/` in `localcloud-server/src/main/java/com/localcloud/persistence/H2DataSource.java`
- [x] T010 Implement schema manager with DDL initialization for all service tables (buckets, topics, documents, datasets, secrets, queues, log_entries, time_series, etc.) in `localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java`
- [x] T011 Implement filesystem blob store for GCS object data with read/write/delete operations in `localcloud-server/src/main/java/com/localcloud/persistence/BlobStore.java`
- [x] T012 Implement EmulatorBase interface with lifecycle methods (start, stop, healthCheck, getName, getPort, getProtocol) in `localcloud-server/src/main/java/com/localcloud/emulators/EmulatorBase.java`
- [x] T013 Implement request logger with ring buffer (last 1000 requests) and query methods in `localcloud-server/src/main/java/com/localcloud/gateway/RequestLogger.java`
- [x] T014 Implement internal event bus for cross-service event wiring (GCS→Pub/Sub, Pub/Sub→Functions) in `localcloud-server/src/main/java/com/localcloud/events/EventBus.java`
- [x] T015 Implement Armeria server bootstrap with multi-port binding (8080 REST, 9010-9040 gRPC), service registration, and graceful shutdown in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- [x] T016 Implement API gateway with path-based routing for REST services and gRPC service dispatch in `localcloud-server/src/main/java/com/localcloud/gateway/ApiGateway.java`

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Start Local GCP Environment (Priority: P1) MVP

**Goal**: Developer can start, check status, and stop the emulation platform with a single command

**Independent Test**: Run `localcloud start`, verify health endpoint responds, run `localcloud status`, run `localcloud stop`

### Implementation for User Story 1

- [x] T017 [US1] Implement health check endpoint returning service statuses, ports, project_id, uptime at `GET /_localcloud/health` in `localcloud-server/src/main/java/com/localcloud/gateway/HealthCheckService.java`
- [x] T018 [US1] Implement service list endpoint returning all running services with endpoints and env var names at `GET /_localcloud/services` in `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`
- [x] T019 [US1] Implement environment variable export endpoint (shell, docker-compose, json formats) at `GET /_localcloud/env` in `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`
- [x] T020 [US1] Implement configurable service selection so only requested services start (via `LOCALCLOUD_SERVICES` env var) in `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- [x] T021 [US1] Implement Docker manager wrapper with methods for pull, create, start, stop, inspect, logs in `localcloud-cli/src/localcloud/docker_manager.py`
- [x] T022 [US1] Implement `localcloud start` command with --services, --seed, --port, --project, --data-dir options in `localcloud-cli/src/localcloud/commands/start.py`
- [x] T023 [P] [US1] Implement `localcloud stop` command with --name and --rm options in `localcloud-cli/src/localcloud/commands/stop.py`
- [x] T024 [P] [US1] Implement `localcloud status` command with table and json output formats in `localcloud-cli/src/localcloud/commands/status.py`
- [x] T025 [US1] Implement Click CLI entry point with global options (--project, --name) and command group registration in `localcloud-cli/src/localcloud/cli.py`
- [x] T026 [US1] Implement `localcloud env` command with shell/docker-compose/json format output in `localcloud-cli/src/localcloud/commands/env.py`
- [x] T027 [US1] Implement `localcloud logs` command with --follow and --tail options in `localcloud-cli/src/localcloud/commands/logs.py`

**Checkpoint**: `localcloud start` → health check → `localcloud status` → `localcloud stop` works end-to-end

---

## Phase 4: User Story 2 - Cloud Storage (Priority: P1)

**Goal**: Developer can create buckets, upload/download/list/delete objects via Google Cloud Storage client library

**Independent Test**: Create a bucket, upload a file, list objects with prefix filter, download and verify content, delete object, verify 404

### Implementation for User Story 2

- [x] T028 [US2] Implement Bucket and StorageObject H2 table DDL and DAO methods (create, get, list, delete) in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageDao.java`
- [x] T029 [US2] Implement GCS REST handlers for bucket CRUD (POST/GET/DELETE `/storage/v1/b`) in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageEmulator.java`
- [x] T030 [US2] Implement GCS REST handlers for object upload (POST `/upload/storage/v1/b/{bucket}/o`) with multipart and media upload support in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageEmulator.java`
- [x] T031 [US2] Implement GCS REST handlers for object get (GET `/storage/v1/b/{bucket}/o/{object}` metadata and `?alt=media` data), list (GET `/storage/v1/b/{bucket}/o` with prefix/delimiter), delete, and copy in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageEmulator.java`
- [x] T032 [US2] Implement GCS error responses matching real GCP format (404 Not Found, 409 Conflict, 400 Bad Request) in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageEmulator.java`
- [x] T033 [US2] Register GCS emulator with API gateway on REST path `/storage/v1` and `/upload/storage/v1` in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud Storage Python/Java client library can create buckets, upload, list, download, and delete objects against localhost:8080

---

## Phase 5: User Story 3 - Pub/Sub (Priority: P1)

**Goal**: Developer can create topics/subscriptions, publish messages, and pull/acknowledge messages via Pub/Sub client library

**Independent Test**: Create topic, create subscription, publish message, pull message, acknowledge, verify no redeliver

### Implementation for User Story 3

- [x] T034 [US3] Implement Topic, Subscription, Message, MessageDelivery in-memory stores with H2 persistence for topics/subscriptions in `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PubSubStore.java`
- [x] T035 [US3] Implement Publisher gRPC service (CreateTopic, GetTopic, ListTopics, DeleteTopic, Publish) extending `PublisherGrpc.PublisherImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PubSubEmulator.java`
- [x] T036 [US3] Implement Subscriber gRPC service (CreateSubscription, GetSubscription, ListSubscriptions, DeleteSubscription, Pull, Acknowledge, ModifyAckDeadline) extending `SubscriberGrpc.SubscriberImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PubSubEmulator.java`
- [x] T037 [US3] Implement StreamingPull with bidirectional streaming, ack deadline management, and message redelivery on timeout in `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PubSubEmulator.java`
- [x] T038 [US3] Implement push subscription delivery (HTTP POST to push endpoint when message is published) in `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PushDeliveryService.java`
- [x] T039 [US3] Register Pub/Sub gRPC service on dedicated port 9020 in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud Pub/Sub client library can publish and pull messages against localhost:9020

---

## Phase 6: User Story 4 - Firestore (Priority: P1)

**Goal**: Developer can create, read, update, delete, and query Firestore documents via client library

**Independent Test**: Create document, read by path, query collection with filters, update fields, batch write, verify atomicity

### Implementation for User Story 4

- [x] T040 [US4] Implement FirestoreDocument H2 storage with JSON data column, path-based lookups, and collection queries in `localcloud-server/src/main/java/com/localcloud/emulators/firestore/FirestoreStore.java`
- [x] T041 [US4] Implement Firestore gRPC service (GetDocument, CreateDocument, UpdateDocument, DeleteDocument, ListDocuments) extending `FirestoreGrpc.FirestoreImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/firestore/FirestoreEmulator.java`
- [x] T042 [US4] Implement RunQuery with WHERE filter evaluation (equality, less-than, greater-than, in, array-contains), ORDER BY, and LIMIT against JSON document fields in `localcloud-server/src/main/java/com/localcloud/emulators/firestore/FirestoreEmulator.java`
- [x] T043 [US4] Implement BatchGetDocuments and BatchWrite with atomic success/failure semantics in `localcloud-server/src/main/java/com/localcloud/emulators/firestore/FirestoreEmulator.java`
- [x] T044 [US4] Implement Listen (real-time document change notifications) using gRPC server streaming in `localcloud-server/src/main/java/com/localcloud/emulators/firestore/FirestoreEmulator.java`
- [x] T045 [US4] Register Firestore gRPC service on dedicated port 9010 in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud Firestore client library can perform CRUD, queries, batch writes, and real-time listeners against localhost:9010

---

## Phase 7: User Story 9 - SDK & CLI Compatibility (Priority: P1)

**Goal**: Google Cloud client libraries work with zero code changes via environment variable override; CLI wrapper provides `gcloud`-style commands

**Independent Test**: Set `STORAGE_EMULATOR_HOST`, run standard SDK code, verify it works; run `eval $(localcloud env)`, verify all vars set

**Depends on**: US1 (platform running), US2 (GCS to test against)

### Implementation for User Story 9

- [x] T046 [US9] Implement credential bypass: accept all requests without credentials, accept any Authorization header, support NoCredentials in `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- [x] T047 [US9] Implement GCP project resource naming validation (projects/{project}/...) in request routing in `localcloud-server/src/main/java/com/localcloud/gateway/ApiGateway.java`
- [x] T048 [US9] Implement `localcloud env --format=docker-compose` generating a docker-compose.override.yml snippet with all service environment variables in `localcloud-cli/src/localcloud/commands/env.py`
- [x] T049 [US9] Create docker-compose.override.yml example file showing how to configure sibling application containers in `docker-compose.override.yml`

**Checkpoint**: Standard Google Cloud SDK code works against LocalCloud with only `eval $(localcloud env)` or env var changes

---

## Phase 8: User Story 5 - BigQuery (Priority: P2)

**Goal**: Developer can create datasets/tables, insert rows, and run SQL queries via BigQuery client library

**Independent Test**: Create dataset, create table with schema, insert rows, run SELECT/JOIN/GROUP BY queries, verify results

### Implementation for User Story 5

- [x] T050 [P] [US5] Implement Dataset and Table H2 storage with schema JSON column and row storage using dynamic H2 tables in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/BigQueryStore.java`
- [x] T051 [P] [US5] Implement BigQueryJob H2 storage with state transitions (PENDING→RUNNING→DONE) in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/BigQueryStore.java`
- [x] T052 [US5] Implement BigQuery REST handlers for dataset CRUD (POST/GET/DELETE `/bigquery/v2/projects/{project}/datasets`) in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/BigQueryEmulator.java`
- [x] T053 [US5] Implement BigQuery REST handlers for table CRUD and row insert (`/bigquery/v2/projects/{project}/datasets/{dataset}/tables`) in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/BigQueryEmulator.java`
- [x] T054 [US5] Implement BigQuery SQL query execution via H2: translate BigQuery SQL to H2 SQL, execute against dynamic tables, format results as BigQuery job response in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/SqlQueryEngine.java`
- [x] T055 [US5] Implement BigQuery job endpoints (POST create job, GET job status, GET query results) with async execution model in `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/BigQueryEmulator.java`
- [x] T056 [US5] Register BigQuery emulator with API gateway on REST path `/bigquery/v2` in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud BigQuery client library can create datasets/tables, insert data, and execute SQL queries against localhost:8080

---

## Phase 9: User Story 6 - Secret Manager (Priority: P2)

**Goal**: Developer can create secrets, add versions, and access secret values via Secret Manager client library

**Independent Test**: Create secret, add version, access "latest", add another version, access "latest" returns newest, disable version, verify error

### Implementation for User Story 6

- [x] T057 [P] [US6] Implement Secret and SecretVersion H2 storage with version numbering and state management in `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerStore.java`
- [x] T058 [US6] Implement SecretManagerService gRPC service (CreateSecret, GetSecret, ListSecrets, DeleteSecret, AddSecretVersion, GetSecretVersion, ListSecretVersions, AccessSecretVersion, DisableSecretVersion, EnableSecretVersion, DestroySecretVersion) extending `SecretManagerServiceGrpc.SecretManagerServiceImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java`
- [x] T059 [US6] Register Secret Manager gRPC service on gateway port 8080 (via Armeria gRPC transcoding) in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud Secret Manager client library can create/access/manage secrets against localhost:8080

---

## Phase 10: User Story 7 - Cloud Tasks (Priority: P2)

**Goal**: Developer can create queues, enqueue tasks with HTTP targets, and tasks are automatically dispatched

**Independent Test**: Create queue, create task with HTTP target pointing to a local test server, verify HTTP request dispatched, test retry on failure

### Implementation for User Story 7

- [x] T060 [P] [US7] Implement Queue and Task in-memory stores with H2 persistence for queue configuration in `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java`
- [x] T061 [US7] Implement CloudTasks gRPC service (CreateQueue, GetQueue, ListQueues, DeleteQueue, PauseQueue, ResumeQueue, CreateTask, GetTask, ListTasks, DeleteTask, RunTask) extending `CloudTasksGrpc.CloudTasksImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java`
- [x] T062 [US7] Implement task dispatcher: scheduled executor that dispatches HTTP requests to task target URLs with retry logic (exponential backoff per queue config) in `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/TaskDispatcher.java`
- [x] T063 [US7] Register Cloud Tasks gRPC service on gateway port 8080 in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Google Cloud Tasks client library can create queues, enqueue tasks, and tasks are dispatched to HTTP endpoints

---

## Phase 11: User Story 8 - Cloud Functions / Cloud Run (Priority: P2)

**Goal**: Event-driven functions are triggered automatically when emulated services generate events (GCS upload → Pub/Sub → Function)

**Independent Test**: Register a function with Pub/Sub trigger, publish message, verify function invoked; upload to GCS, verify GCS-triggered function invoked

**Depends on**: US3 (Pub/Sub), US2 (GCS)

### Implementation for User Story 8

- [x] T064 [US8] Implement trigger registration: API to register local function URLs with trigger type (Pub/Sub topic, GCS bucket, HTTP) in `localcloud-server/src/main/java/com/localcloud/emulators/functions/TriggerRegistry.java`
- [x] T065 [US8] Implement GCS→Pub/Sub event wiring: on object create/delete in GCS, publish notification to configured Pub/Sub topic via EventBus in `localcloud-server/src/main/java/com/localcloud/emulators/gcs/StorageEmulator.java`
- [x] T066 [US8] Implement Pub/Sub→Function trigger: when message published to a trigger topic, dispatch HTTP POST to registered function URL in `localcloud-server/src/main/java/com/localcloud/emulators/functions/FunctionTriggerService.java`
- [x] T067 [US8] Implement admin endpoints for trigger management (POST/GET/DELETE `/_localcloud/triggers`) in `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`

**Checkpoint**: GCS upload triggers Pub/Sub notification which invokes registered Cloud Function endpoint end-to-end

---

## Phase 12: User Story 10 - Spanner + Bigtable (Priority: P3)

**Goal**: Developer can use Spanner DDL/DML and Bigtable read/write operations via client libraries

**Independent Test**: Spanner: create instance, database, table, insert rows, query. Bigtable: create table, write row, read row

### Implementation for User Story 10

- [x] T068 [P] [US10] Implement SpannerInstance and SpannerDatabase H2 storage with DDL tracking (inline in SpannerEmulator) in `localcloud-server/src/main/java/com/localcloud/emulators/spanner/SpannerEmulator.java`
- [x] T069 [P] [US10] Implement BigtableTable and BigtableCell H2 storage with row key indexing (inline in BigtableEmulator) in `localcloud-server/src/main/java/com/localcloud/emulators/bigtable/BigtableEmulator.java`
- [x] T070 [US10] Implement Spanner gRPC services: InstanceAdmin (CreateInstance, GetInstance, ListInstances, DeleteInstance), DatabaseAdmin (CreateDatabase, GetDatabase, ListDatabases, DropDatabase, UpdateDatabaseDdl, GetDatabaseDdl), Spanner (CreateSession, ExecuteSql, Read, BeginTransaction, Commit, Rollback) in `localcloud-server/src/main/java/com/localcloud/emulators/spanner/SpannerEmulator.java`
- [x] T071 [US10] Implement Spanner DDL execution: translate CREATE TABLE/ALTER TABLE/DROP TABLE to H2 DDL scoped by database (inline in SpannerEmulator) in `localcloud-server/src/main/java/com/localcloud/emulators/spanner/SpannerEmulator.java`
- [x] T072 [US10] Implement Bigtable gRPC services: BigtableTableAdmin (CreateTable, GetTable, ListTables, DeleteTable, ModifyColumnFamilies), Bigtable (ReadRows, MutateRow, MutateRows, CheckAndMutateRow, ReadModifyWriteRow, SampleRowKeys) in `localcloud-server/src/main/java/com/localcloud/emulators/bigtable/BigtableEmulator.java`
- [x] T073 [US10] Register Spanner gRPC service on dedicated port 9030 and Bigtable gRPC service on dedicated port 9040 in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Spanner and Bigtable client libraries work against localhost:9030 and localhost:9040 respectively

---

## Phase 13: User Story 11 - IAM + Logging + Monitoring (Priority: P3)

**Goal**: Basic IAM permissive mode works; Cloud Logging and Monitoring APIs accept and store data locally

**Independent Test**: IAM: make API call without credentials, verify accepted. Logging: write log entry, list entries. Monitoring: create time series, list.

### Implementation for User Story 11

- [x] T074 [P] [US11] Implement LogEntry H2 storage with severity filtering and listing (inline in LoggingEmulator) in `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java`
- [x] T075 [P] [US11] Implement TimeSeries and MetricPoint H2 storage with time-range queries (inline in MonitoringEmulator) in `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java`
- [x] T076 [US11] Implement Cloud Logging gRPC service (WriteLogEntries, ListLogEntries, ListLogs) extending `LoggingServiceV2Grpc.LoggingServiceV2ImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java`
- [x] T077 [US11] Implement Cloud Monitoring gRPC service (CreateTimeSeries, ListTimeSeries, GetMetricDescriptor, ListMetricDescriptors, CreateMetricDescriptor) extending `MetricServiceGrpc.MetricServiceImplBase` in `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java`
- [x] T078 [US11] Implement IAM permissive mode middleware: accept all requests regardless of credentials, log auth bypass in `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- [x] T079 [US11] Implement IAM strict mode: load local policy config, evaluate role-based access for each API call in `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- [x] T080 [US11] Register Logging and Monitoring gRPC services on gateway port 8080 in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Checkpoint**: Logging/Monitoring APIs accept data; IAM permissive mode allows all calls without credentials

---

## Phase 14: Polish & Cross-Cutting Concerns

**Purpose**: Dashboard, seed files, Docker packaging, and final integration

### Web Dashboard

- [x] T081 [P] Implement admin browse endpoints for each service (list buckets, topics, documents, datasets, secrets, queues, log entries, metrics) at `GET /_localcloud/browse/{service}/*` in `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- [x] T082 [P] Implement request log query endpoint at `GET /_localcloud/requests` with filters (service, since, limit) in `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`
- [x] T083 Create lightweight web dashboard SPA (HTML/JS/CSS) with service status panel, request log viewer, and data browser in `localcloud-server/src/main/resources/dashboard/index.html`, `app.js`, `style.css`
- [x] T084 Serve dashboard static files from Armeria at `/_localcloud/dashboard/` in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

### Seed & Reset

- [x] T085 Implement seed YAML parsing and validation in `localcloud-cli/src/localcloud/seed_processor.py`
- [x] T086 Implement seed loading via HTTP POST to server's /_localcloud/seed endpoint in `localcloud-cli/src/localcloud/seed_processor.py`
- [x] T087 Implement seed upload endpoint at `POST /_localcloud/seed` that accepts YAML and delegates to emulators in `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`
- [x] T088 Implement reset endpoint at `POST /_localcloud/reset` that clears all service state and optionally restores seed in `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`
- [x] T089 Implement `localcloud seed` CLI command in `localcloud-cli/src/localcloud/commands/seed.py`
- [x] T090 Implement `localcloud reset` CLI command with --seed and --yes options in `localcloud-cli/src/localcloud/commands/reset.py`

### Docker & Packaging

- [x] T091 Configure Gradle shadowJar plugin for fat JAR creation in `localcloud-server/build.gradle`
- [x] T092 Finalize Dockerfile with multi-stage build (build JAR, copy to runtime image) in `Dockerfile`
- [x] T093 Add Docker healthcheck and startup probe configuration in `Dockerfile`
- [x] T094 Create `__init__.py` with version info and `console_scripts` entry point for pip installation in `localcloud-cli/src/localcloud/__init__.py`

### Final Validation

- [ ] T095 Validate quickstart.md scenarios work end-to-end: install CLI, start platform, run Python SDK example, run Java SDK example, use Docker Compose
- [ ] T096 Verify all 10 services start within 60 seconds and consume under 2GB memory total
- [ ] T097 Verify state persistence: create resources, restart container, verify resources still exist

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational - establishes running platform
- **US2-US4 (Phases 4-6)**: Depend on Foundational; can run in parallel after Phase 2
- **US9 (Phase 7)**: Depends on US1 + at least one service emulator (US2)
- **US5-US7 (Phases 8-10)**: Depend on Foundational; can run in parallel
- **US8 (Phase 11)**: Depends on US2 (GCS) + US3 (Pub/Sub) for event wiring
- **US10 (Phase 12)**: Depends on Foundational; can run in parallel with other stories
- **US11 (Phase 13)**: Depends on Foundational; can run in parallel with other stories
- **Polish (Phase 14)**: Depends on US1 + at least P1 user stories complete

### User Story Dependencies

- **US1** (Start Environment): Independent - first MVP
- **US2** (Cloud Storage): Independent after foundation
- **US3** (Pub/Sub): Independent after foundation
- **US4** (Firestore): Independent after foundation
- **US5** (BigQuery): Independent after foundation
- **US6** (Secret Manager): Independent after foundation
- **US7** (Cloud Tasks): Independent after foundation
- **US8** (Functions/Run): Depends on US2 + US3 for trigger wiring
- **US9** (SDK Compatibility): Depends on US1 + US2 for validation
- **US10** (Spanner + Bigtable): Independent after foundation
- **US11** (IAM + Logging + Monitoring): Independent after foundation

### Within Each User Story

- Store/DAO before emulator service
- Emulator service before gateway registration
- Core operations before advanced features (e.g., basic CRUD before streaming)

### Parallel Opportunities

- T002-T007 (setup): all independent, can run in parallel
- T028-T033 (GCS) + T034-T039 (Pub/Sub) + T040-T045 (Firestore): all independent stories, can run in parallel
- T050-T056 (BigQuery) + T057-T059 (Secret Manager) + T060-T063 (Cloud Tasks): all independent, can run in parallel
- T068-T073 (Spanner/Bigtable) + T074-T080 (IAM/Logging/Monitoring): can run in parallel

---

## Parallel Example: P1 Service Emulators (after Phase 2)

```bash
# Launch all P1 emulators in parallel (different packages, no shared state):
Agent 1: "T028-T033 Cloud Storage emulator in emulators/gcs/"
Agent 2: "T034-T039 Pub/Sub emulator in emulators/pubsub/"
Agent 3: "T040-T045 Firestore emulator in emulators/firestore/"
```

## Parallel Example: P2 Service Emulators

```bash
# Launch all P2 emulators in parallel:
Agent 1: "T050-T056 BigQuery emulator in emulators/bigquery/"
Agent 2: "T057-T059 Secret Manager emulator in emulators/secretmanager/"
Agent 3: "T060-T063 Cloud Tasks emulator in emulators/cloudtasks/"
```

---

## Implementation Strategy

### MVP First (User Story 1 + Cloud Storage)

1. Complete Phase 1: Setup (T001-T007)
2. Complete Phase 2: Foundational (T008-T016)
3. Complete Phase 3: US1 - Start Environment (T017-T027)
4. Complete Phase 4: US2 - Cloud Storage (T028-T033)
5. **STOP and VALIDATE**: Start platform, upload/download files via SDK, verify persistence
6. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. US1 + US2 → Start platform, use GCS (MVP!)
3. US3 + US4 → Add Pub/Sub + Firestore (core P1 complete)
4. US9 → SDK compatibility validation
5. US5 + US6 + US7 → BigQuery, Secret Manager, Cloud Tasks (P2)
6. US8 → Cross-service event wiring
7. US10 + US11 → Spanner, Bigtable, IAM, Logging, Monitoring (P3)
8. Polish → Dashboard, seed files, Docker packaging

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: US1 (Platform startup) → US9 (SDK compat)
   - Developer B: US2 (GCS) → US5 (BigQuery) → US8 (Event wiring)
   - Developer C: US3 (Pub/Sub) → US6 (Secret Manager) → US10 (Spanner/Bigtable)
   - Developer D: US4 (Firestore) → US7 (Cloud Tasks) → US11 (IAM/Logging/Monitoring)
3. Final: Team converges on Polish phase

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Total: 97 tasks across 14 phases covering 11 user stories
