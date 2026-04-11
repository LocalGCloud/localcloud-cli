# LocalCloud Developer Guide

**Google Cloud Platform — in a box.**

LocalCloud emulates 14 GCP services inside a single Docker container so you can develop and test locally without cloud access, credentials, or costs. Your application code works against LocalCloud with **zero code changes** — just set environment variables to point SDKs at localhost.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Installation](#installation)
- [Starting LocalCloud](#starting-localcloud)
- [Connecting Your Application](#connecting-your-application)
- [Emulated Services](#emulated-services)
- [Seed Data](#seed-data)
- [Web Console](#web-console)
- [Admin API](#admin-api)
- [Configuration Reference](#configuration-reference)
- [Docker Compose Integration](#docker-compose-integration)
- [IAM Modes](#iam-modes)
- [Troubleshooting](#troubleshooting)
- [Docker Command Reference](#docker-command-reference)

---

## Quick Start

### Option A: Docker Compose (Recommended)

All service ports are pre-configured in `docker-compose.yml` (derived from `services.yaml`):

```bash
# Start all services — ports are configured automatically
docker compose up -d

# Configure your shell with emulator env vars
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"

# Use GCP SDKs as normal — they now point to localhost
python -c "
from google.cloud import storage
client = storage.Client()
bucket = client.create_bucket('my-bucket')
print(f'Created bucket: {bucket.name}')
"
```

### Option B: Docker Run

Service ports are defined in `services.yaml`. See the [Port Map](#port-map) for the complete list.

```bash
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Configure your shell
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"
```

---

## Installation

### Prerequisites

- **Docker** (Docker Desktop or Docker Engine)

### Pull the Docker Image

```bash
docker pull localcloud/localcloud:latest
```

---

## Starting LocalCloud

### Using Docker Compose (Recommended)

The `docker-compose.yml` has all ports and config pre-defined (matching `services.yaml`):

```bash
# Start with defaults
docker compose up -d

# Start with custom project ID
LOCALCLOUD_PROJECT=my-gcp-project docker compose up -d

# Start with specific services only
LOCALCLOUD_SERVICES=gcs,pubsub,firestore docker compose up -d

# Start with seed data (seed.yaml is mounted by default)
docker compose up -d
```

### Using Docker Run

When using `docker run` directly, you need to expose ports manually. See `services.yaml` for the authoritative port list, or use the [Port Map](#port-map) below.

```bash
# Start all default services
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Start with specific services (only expose ports you need)
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -m 4g \
  -e LOCALCLOUD_SERVICES=gcs,pubsub,firestore \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Start with seed data
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  -v ./seed.yaml:/etc/localcloud/seed.yaml:ro \
  localcloud/localcloud:latest
```

### Available Services

| Service | Env Flag | Default |
|---------|----------|---------|
| Cloud Storage | `LOCALCLOUD_ENABLE_GCS` | Enabled |
| Pub/Sub | `LOCALCLOUD_ENABLE_PUBSUB` | Enabled |
| Firestore | `LOCALCLOUD_ENABLE_FIRESTORE` | Enabled |
| Bigtable | `LOCALCLOUD_ENABLE_BIGTABLE` | Enabled |
| Spanner | `LOCALCLOUD_ENABLE_SPANNER` | Enabled |
| BigQuery | `LOCALCLOUD_ENABLE_BIGQUERY` | Enabled |
| Secret Manager | `LOCALCLOUD_ENABLE_SECRETMANAGER` | Enabled |
| Cloud Tasks | `LOCALCLOUD_ENABLE_CLOUDTASKS` | Enabled |
| Cloud Logging | `LOCALCLOUD_ENABLE_LOGGING` | Enabled |
| Cloud Monitoring | `LOCALCLOUD_ENABLE_MONITORING` | Enabled |
| Memorystore (Redis) | `LOCALCLOUD_ENABLE_MEMORYSTORE` | Enabled |
| GKE | `LOCALCLOUD_ENABLE_GKE` | Disabled |
| Compute Engine | `LOCALCLOUD_ENABLE_COMPUTE` | Disabled |
| Cloud Run | `LOCALCLOUD_ENABLE_CLOUDRUN` | Disabled |

```bash
# Enable GKE, Compute, and Cloud Run (disabled by default, require Docker socket)
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 6379:6379 \
  -m 4g \
  -e LOCALCLOUD_ENABLE_GKE=true \
  -e LOCALCLOUD_ENABLE_COMPUTE=true \
  -e LOCALCLOUD_ENABLE_CLOUDRUN=true \
  -v localcloud-data:/var/lib/localcloud \
  -v /var/run/docker.sock:/var/run/docker.sock \
  localcloud/localcloud:latest
```

### Lifecycle Commands

```bash
# Check service status
curl http://localhost:8080/_localcloud/health | jq

# View container logs
docker logs localcloud-main
docker logs -f --tail 200 localcloud-main

# Stop the container
docker stop localcloud-main

# Stop and remove the container (data preserved in volume)
docker rm -f localcloud-main

# Reset all data via Admin API
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore last loaded seed
curl -X POST http://localhost:8080/_localcloud/reset \
  -H "Content-Type: application/json" -d '{"restore_seed": true}'

# Load seed data into a running instance
curl -X POST http://localhost:8080/_localcloud/seed \
  -H "Content-Type: application/yaml" --data-binary @seed.yaml
```

---

## Connecting Your Application

### Option 1: Shell Environment Variables (Recommended)

The easiest way is to use the auto-generated env vars from the running container:

```bash
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"
```

Or set them manually in your application's environment:

| Variable | Value | Services |
|----------|-------|----------|
| `STORAGE_EMULATOR_HOST` | `http://localhost:4443` | Cloud Storage |
| `PUBSUB_EMULATOR_HOST` | `localhost:8085` | Pub/Sub |
| `FIRESTORE_EMULATOR_HOST` | `localhost:8086` | Firestore |
| `BIGTABLE_EMULATOR_HOST` | `localhost:8087` | Bigtable |
| `SPANNER_EMULATOR_HOST` | `localhost:9010` | Spanner |
| `BIGQUERY_EMULATOR_HOST` | `http://localhost:9050` | BigQuery |
| `SECRET_MANAGER_EMULATOR_HOST` | `localhost:8080` | Secret Manager |
| `CLOUD_TASKS_EMULATOR_HOST` | `localhost:8080` | Cloud Tasks |
| `CLOUD_LOGGING_EMULATOR_HOST` | `localhost:8080` | Cloud Logging |
| `CLOUD_MONITORING_EMULATOR_HOST` | `localhost:8080` | Cloud Monitoring |
| `GKE_EMULATOR_HOST` | `localhost:8080` | GKE |
| `COMPUTE_EMULATOR_HOST` | `http://localhost:8080` | Compute Engine |
| `CLOUD_RUN_EMULATOR_HOST` | `localhost:8080` | Cloud Run |
| `REDIS_HOST` | `localhost` | Memorystore (Redis) |
| `GOOGLE_CLOUD_PROJECT` | `local-project` | All services |

### Option 2: Docker Compose (Multi-Container Apps)

See [Docker Compose Integration](#docker-compose-integration) for full examples.

### SDK Code Examples

**Python — Cloud Storage:**
```python
from google.cloud import storage

# No code changes needed — STORAGE_EMULATOR_HOST is auto-detected
client = storage.Client()
bucket = client.create_bucket("my-bucket")
blob = bucket.blob("hello.txt")
blob.upload_from_string("Hello, LocalCloud!")
print(blob.download_as_text())  # "Hello, LocalCloud!"
```

**Python — Pub/Sub:**
```python
from google.cloud import pubsub_v1

publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-project", "my-topic")
publisher.create_topic(request={"name": topic_path})
publisher.publish(topic_path, b"Hello from Pub/Sub!")
```

**Python — Firestore:**
```python
from google.cloud import firestore

db = firestore.Client()
doc_ref = db.collection("users").document("user-001")
doc_ref.set({"name": "Alice", "email": "alice@example.com"})
doc = doc_ref.get()
print(doc.to_dict())  # {"name": "Alice", "email": "alice@example.com"}
```

**Python — Secret Manager:**
```python
from google.cloud import secretmanager

client = secretmanager.SecretManagerServiceClient()
parent = f"projects/local-project"

# Create secret
secret = client.create_secret(request={"parent": parent, "secret_id": "api-key", "secret": {"replication": {"automatic": {}}}})

# Add version
client.add_secret_version(request={"parent": secret.name, "payload": {"data": b"my-secret-value"}})

# Access latest
response = client.access_secret_version(request={"name": f"{secret.name}/versions/latest"})
print(response.payload.data.decode())  # "my-secret-value"
```

**Python — BigQuery:**
```python
from google.cloud import bigquery

client = bigquery.Client()
dataset = client.create_dataset("my_dataset")
schema = [bigquery.SchemaField("name", "STRING"), bigquery.SchemaField("age", "INTEGER")]
table = client.create_table(bigquery.Table(f"{dataset.dataset_id}.users", schema=schema))

# Insert rows
rows = [{"name": "Alice", "age": 30}, {"name": "Bob", "age": 25}]
client.insert_rows_json(table, rows)

# Query
query = "SELECT name FROM my_dataset.users WHERE age > 20"
for row in client.query(query):
    print(row.name)
```

**Java — Cloud Storage:**
```java
import com.google.cloud.storage.*;

Storage storage = StorageOptions.newBuilder()
    .setHost(System.getenv("STORAGE_EMULATOR_HOST"))
    .setProjectId("local-project")
    .build().getService();

storage.create(BucketInfo.of("my-bucket"));
BlobId blobId = BlobId.of("my-bucket", "hello.txt");
storage.create(BlobInfo.newBuilder(blobId).build(), "Hello!".getBytes());
```

**Switching to Production:**
```bash
# Remove the environment variables — your code now hits real GCP
unset STORAGE_EMULATOR_HOST
unset PUBSUB_EMULATOR_HOST
unset FIRESTORE_EMULATOR_HOST
# ... etc

# Zero code changes required.
```

---

## Emulated Services

### Port Map

All ports are defined in `services.yaml` — the single source of truth for service configuration.

| Port | Service | Protocol |
|------|---------|----------|
| 8080 | Gateway (Admin API, Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run) | REST + gRPC |
| 4443 | Cloud Storage | HTTP |
| 8085 | Pub/Sub | gRPC |
| 8086 | Firestore | gRPC |
| 8087 | Bigtable | gRPC |
| 9010 | Spanner (gRPC) | gRPC |
| 9020 | Spanner (REST) | REST |
| 9050 | BigQuery (REST) | REST |
| 9060 | BigQuery (gRPC) | gRPC |
| 6379 | Memorystore (Redis) | RESP2 |
| 6443 | GKE / k3d Kubernetes API | HTTPS |

### Cloud Storage

**Supported:** Bucket CRUD, object upload/download/list/delete/copy, prefix filtering, metadata.

**Not Supported:** Object versioning beyond generation tracking, bucket lifecycle execution, CMEK encryption.

### Pub/Sub

**Supported:** Topics, subscriptions, publish, pull, streaming pull, acknowledge, modify ack deadline.

**Not Supported:** Schema validation, BigQuery/Cloud Storage subscriptions, exactly-once delivery.

### Firestore

**Supported:** Document CRUD, collection queries (WHERE, ORDER BY, LIMIT), batch writes, real-time listeners.

**Not Supported:** Composite index enforcement, transaction isolation, aggregation queries (COUNT/SUM/AVG).

### BigQuery

**Supported:** Datasets, tables, row inserts, SQL queries (SELECT, JOIN, GROUP BY, subqueries, aggregates).

**Not Supported:** Partitioned/clustered tables, materialized views, BQML, scripting, wildcard tables.

### Secret Manager

**Supported:** Secret CRUD, version management, access latest, enable/disable/destroy versions.

**Not Supported:** Rotation notifications, CMEK, per-secret IAM policies.

### Cloud Tasks

**Supported:** Queue CRUD, pause/resume, task creation with HTTP targets, automatic dispatch with retries.

**Not Supported:** App Engine tasks, OAuth/OIDC token generation for dispatch.

**Tip:** For task targets on the host machine, use `host.docker.internal` as the hostname:
```python
task = {"http_request": {"url": "http://host.docker.internal:5000/webhook", "http_method": "POST"}}
```

### Spanner

**Supported:** Instance/database CRUD, DDL (CREATE/ALTER/DROP TABLE), sessions, ExecuteSql, Read, transactions.

**Not Supported:** Partitioned DML, change streams, foreign keys, multi-region.

### Bigtable

**Supported:** Table management, column families, ReadRows, MutateRow, CheckAndMutateRow, ReadModifyWriteRow.

**Not Supported:** Instance/cluster management, backup/restore, GC policy enforcement.

### Memorystore (Redis)

**Supported:** GET, SET, DEL, MGET, MSET, INCR/DECR, EXPIRE/TTL/PERSIST, lists (LPUSH/RPUSH/LPOP/RPOP/LRANGE/LLEN), sets (SADD/SREM/SMEMBERS/SCARD/SISMEMBER), hashes (HSET/HGET/HDEL/HGETALL/HKEYS/HVALS/HLEN), sorted sets (ZADD/ZREM/ZRANGE/ZRANK/ZSCORE/ZCARD), KEYS, EXISTS, TYPE, PING, DBSIZE, FLUSHDB.

**Not Supported:** Pub/Sub channels, Lua scripting, streams, cluster mode, transactions (MULTI/EXEC), persistence (RDB/AOF).

### Cloud Logging (Sink Mode)

**Supported:** WriteLogEntries, ListLogEntries, ListLogs, DeleteLog. All entries stored and browsable.

**Not Supported:** Log-based metrics, sinks, exclusions, Router config, audit logs.

### Cloud Monitoring (Sink Mode)

**Supported:** CreateTimeSeries, ListTimeSeries, metric descriptors. All metrics stored and browsable.

**Not Supported:** Alerting policies, uptime checks, dashboards, metric aggregation, SLOs.

### GKE (requires Docker + k3d)

**Supported:** Cluster CRUD via GKE API. Creates real k3d (lightweight k3s) clusters when k3d is available; simulated mode otherwise.

**Not Supported:** Node pool management, auto-scaling, cluster upgrades, workload identity.

### Compute Engine (requires Docker)

**Supported:** Instance CRUD, start/stop. Creates real Docker containers as VM stand-ins when Docker is available.

**Not Supported:** Persistent disks, snapshots, instance templates, managed instance groups, networking.

### Cloud Run (requires Docker)

**Supported:** Service CRUD, revision tracking. Deploys real Docker containers per service with dynamic port allocation.

**Not Supported:** Traffic splitting, custom domains, Cloud Run Jobs, IAM authentication.

---

## Seed Data

Seed files define initial state for services using YAML. Load them on startup or via CLI.

### Seed File Format

```yaml
version: "1.0"
project: "local-project"

services:
  gcs:
    buckets:
      - name: "data-bucket"
        location: "US"
        objects:
          - key: "config/settings.json"
            content: '{"debug": true}'
            contentType: "application/json"
          - key: "uploads/logo.png"
            source: "./assets/logo.png"

  pubsub:
    topics:
      - name: "order-events"
        subscriptions:
          - name: "order-processor"
            ackDeadlineSeconds: 30
      - name: "notifications"

  secretmanager:
    secrets:
      - name: "database-url"
        versions:
          - data: "postgresql://user:pass@db:5432/myapp"
            state: "ENABLED"
      - name: "api-key"
        versions:
          - data: "sk-live-abc123"
            state: "ENABLED"
          - data: "sk-live-old-key"
            state: "DISABLED"

  bigquery:
    datasets:
      - name: "analytics"
        tables:
          - name: "events"
            schema:
              - { name: "event_id", type: "STRING" }
              - { name: "timestamp", type: "TIMESTAMP" }
              - { name: "user_id", type: "STRING" }
              - { name: "event_type", type: "STRING" }
            rows:
              - { event_id: "e1", user_id: "u1", event_type: "login" }
```

### Loading Seed Data

```bash
# Load on startup (mount seed file into the container)
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  -v ./seed.yaml:/etc/localcloud/seed.yaml:ro \
  localcloud/localcloud:latest

# Load into a running instance via Admin API
curl -X POST http://localhost:8080/_localcloud/seed \
  -H "Content-Type: application/yaml" --data-binary @seed.yaml

# Reset all data
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore last loaded seed
curl -X POST http://localhost:8080/_localcloud/reset \
  -H "Content-Type: application/json" -d '{"restore_seed": true}'
```

---

## Multi-Project Support

LocalCloud supports running multiple GCP projects simultaneously within a single container. Each project has isolated data across all services.

### Managing Projects

```bash
# List projects
curl http://localhost:8080/_localcloud/projects | jq

# Create a new project
curl -X POST http://localhost:8080/_localcloud/projects \
  -H "Content-Type: application/json" \
  -d '{"project_id": "staging", "display_name": "Staging Environment"}'

# Delete a project (cascades all data)
curl -X DELETE http://localhost:8080/_localcloud/projects/staging

# Browse data for a specific project
curl http://localhost:8080/_localcloud/browse/secretmanager?project=staging

# Get env vars for a specific project
curl http://localhost:8080/_localcloud/env?project=staging

# Reset only one project's data
curl -X POST http://localhost:8080/_localcloud/reset?project=staging
```

### Multi-Project Seed Format

```yaml
version: "1.0"
projects:
  dev:
    gcs:
      buckets:
        - name: "dev-bucket"
    secretmanager:
      secrets:
        - name: "api-key"
          value: "dev-secret-value"
  staging:
    gcs:
      buckets:
        - name: "staging-bucket"
    secretmanager:
      secrets:
        - name: "api-key"
          value: "staging-secret-value"
```

The single-project format (`services: { gcs: ... }`) remains fully supported.

### Console Project Switcher

The web console includes a project dropdown in the topbar. Click the project name to switch between projects — all pages will show data scoped to the selected project.

---

## Web Console

LocalCloud includes a web-based management console (Solid.js) for monitoring and debugging. The console is served directly by the Armeria gateway on port 8080 — no separate server process needed.

```bash
# Open the console in your browser
localcloud console

# Or navigate directly to:
# http://localhost:8080
```

**Console URL:** http://localhost:8080

### Console Features

| Page | Description |
|------|-------------|
| **Dashboard** | Service health status, project info, uptime |
| **Services** | Service table with name, status, port, protocol, env var |
| **Logs** | Real-time request log viewer with method/status filtering |
| **Data Browser** | Explorer panel to browse all 14 services — GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute, Cloud Run, Memorystore |
| **Usage** | API usage per service, estimated GCP cost savings, pricing reference |
| **Settings** | Auto-refresh interval, environment export, about |

---

## Admin API

The gateway exposes admin endpoints at `/_localcloud/` on port 8080.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/_localcloud/health` | Aggregated health status of all services |
| GET | `/_localcloud/services` | List all services with status and ports |
| GET | `/_localcloud/env?format=shell` | Environment variables (shell/json/docker-compose) |
| GET | `/_localcloud/requests` | Recent API request log (ring buffer, last 1000) |
| GET | `/_localcloud/browse/{service}` | Browse service data (read-only) |
| POST | `/_localcloud/seed` | Load seed data (YAML body) |
| POST | `/_localcloud/reset` | Reset all services to clean/seed state |
| GET | `/_localcloud/projects` | List all projects |
| POST | `/_localcloud/projects` | Create a new project |
| DELETE | `/_localcloud/projects/{id}` | Delete a project and all its data |

### Example: Health Check

```bash
curl http://localhost:8080/_localcloud/health | jq
```

```json
{
  "status": "healthy",
  "uptime_seconds": 1234,
  "services": {
    "gcs": { "status": "healthy", "port": 4443, "protocol": "rest" },
    "pubsub": { "status": "healthy", "port": 8085, "protocol": "grpc" },
    "firestore": { "status": "healthy", "port": 8086, "protocol": "grpc" }
  },
  "project_id": "local-project",
  "persistence": true
}
```

### Example: Browse Data

```bash
# List GCS buckets
curl http://localhost:8080/_localcloud/browse/gcs

# List Pub/Sub topics
curl http://localhost:8080/_localcloud/browse/pubsub

# View log entries
curl "http://localhost:8080/_localcloud/browse/logging/entries?severity=ERROR&limit=50"

# View monitoring metrics
curl "http://localhost:8080/_localcloud/browse/monitoring/timeseries?metric_type=custom.googleapis.com/my_metric"
```

---

## Configuration Reference

### Server Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LOCALCLOUD_PROJECT` | `local-project` | GCP project ID used by all services |
| `LOCALCLOUD_PORT` | `8080` | Gateway port for REST + gRPC |
| `LOCALCLOUD_SERVICES` | `gcs,pubsub,...` | Comma-separated list of enabled services |
| `LOCALCLOUD_DATA_DIR` | `/var/lib/localcloud` | Persistent data directory |
| `LOCALCLOUD_PERSISTENCE` | `true` | Enable/disable state persistence |
| `LOCALCLOUD_IAM_MODE` | `permissive` | IAM enforcement: `permissive`, `strict`, `gcp-live` |
| `LOCALCLOUD_IAM_POLICY_FILE` | *(empty)* | Path to IAM policy JSON (for strict mode) |
| `LOCALCLOUD_LOG_VERBOSITY` | `info` | Log level: `debug`, `info`, `warn`, `error` |
| `LOCALCLOUD_PG_HOST` | `localhost` | PostgreSQL host |
| `LOCALCLOUD_PG_PORT` | `5432` | PostgreSQL port |
| `LOCALCLOUD_PG_DATABASE` | `localcloud` | PostgreSQL database |
| `LOCALCLOUD_PG_USER` | `localcloud` | PostgreSQL user |
| `LOCALCLOUD_PG_PASSWORD` | `localcloud` | PostgreSQL password |

### Service Enable Flags (Docker)

These control which external emulator processes start inside the container:

| Variable | Default | Service |
|----------|---------|---------|
| `LOCALCLOUD_ENABLE_GCS` | `true` | Cloud Storage |
| `LOCALCLOUD_ENABLE_PUBSUB` | `true` | Pub/Sub |
| `LOCALCLOUD_ENABLE_FIRESTORE` | `true` | Firestore |
| `LOCALCLOUD_ENABLE_BIGTABLE` | `true` | Bigtable |
| `LOCALCLOUD_ENABLE_SPANNER` | `true` | Spanner |
| `LOCALCLOUD_ENABLE_BIGQUERY` | `true` | BigQuery |
| `LOCALCLOUD_ENABLE_SECRETMANAGER` | `true` | Secret Manager |
| `LOCALCLOUD_ENABLE_CLOUDTASKS` | `true` | Cloud Tasks |
| `LOCALCLOUD_ENABLE_LOGGING` | `true` | Cloud Logging |
| `LOCALCLOUD_ENABLE_MONITORING` | `true` | Cloud Monitoring |
| `LOCALCLOUD_ENABLE_MEMORYSTORE` | `true` | Memorystore (Redis) |
| `LOCALCLOUD_ENABLE_GKE` | `false` | GKE |
| `LOCALCLOUD_ENABLE_COMPUTE` | `false` | Compute Engine |
| `LOCALCLOUD_ENABLE_CLOUDRUN` | `false` | Cloud Run |

### JVM Tuning

The Java gateway uses fixed heap sizes to coexist with PostgreSQL and emulator processes:

| Flag | Value | Purpose |
|------|-------|---------|
| `-Xmx` | `512m` | Maximum heap size |
| `-Xms` | `128m` | Initial heap size |
| `-XX:+UseZGC` | — | Low-latency Z Garbage Collector |
| `-XX:+ZGenerational` | — | Generational ZGC (Java 21) |
| `-XX:MaxMetaspaceSize` | `96m` | Bounded metaspace |
| `-XX:+ExitOnOutOfMemoryError` | — | Fail fast on OOM for Docker restart |

Override via `JAVA_OPTS` environment variable:
```bash
docker run -m 4g -e JAVA_OPTS="-Xmx2g -Xms512m" localcloud/localcloud:latest
```

---

## Docker Compose Integration

### Basic Setup

Create a `docker-compose.yml` for your project:

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    container_name: localcloud-main
    ports:
      - "8080:8080"
      - "4443:4443"
      - "8085:8085"
      - "8086:8086"
      - "8087:8087"
      - "9010:9010"
      - "9050:9050"
      - "6379:6379"
    environment:
      LOCALCLOUD_PROJECT: "my-project"
    volumes:
      - localcloud-data:/var/lib/localcloud
      - ./seed.yaml:/etc/localcloud/seed.yaml:ro
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/_localcloud/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  my-app:
    build: .
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:4443"
      PUBSUB_EMULATOR_HOST: "localcloud:8085"
      FIRESTORE_EMULATOR_HOST: "localcloud:8086"
      BIGTABLE_EMULATOR_HOST: "localcloud:8087"
      SPANNER_EMULATOR_HOST: "localcloud:9010"
      BIGQUERY_EMULATOR_HOST: "http://localcloud:9050"
      SECRET_MANAGER_EMULATOR_HOST: "localcloud:8080"
      CLOUD_TASKS_EMULATOR_HOST: "localcloud:8080"
      REDIS_HOST: "localcloud"
      GOOGLE_CLOUD_PROJECT: "my-project"
    depends_on:
      localcloud:
        condition: service_healthy

volumes:
  localcloud-data:
```

### With Infrastructure Services

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports:
      - "8080:8080"
      - "4443:4443"
      - "8085:8085"
      - "8086:8086"
      - "16443:6443"
    environment:
      LOCALCLOUD_SERVICES: "gcs,pubsub,firestore,secretmanager,gke,compute,cloudrun"
      LOCALCLOUD_ENABLE_GKE: "true"
      LOCALCLOUD_ENABLE_COMPUTE: "true"
      LOCALCLOUD_ENABLE_CLOUDRUN: "true"
    volumes:
      - localcloud-data:/var/lib/localcloud
      - /var/run/docker.sock:/var/run/docker.sock  # Required for GKE/Compute/CloudRun
```

---

## IAM Modes

LocalCloud supports three IAM enforcement modes:

### Permissive Mode (Default)

All requests are accepted regardless of credentials. No authentication or authorization checks.

```bash
localcloud start  # permissive by default
```

### Strict Mode

Enforce role-based access from a local policy file.

```bash
# Set IAM mode and policy file
export LOCALCLOUD_IAM_MODE=strict
export LOCALCLOUD_IAM_POLICY_FILE=/path/to/iam-policy.json
localcloud start
```

**Policy file format:**
```json
{
  "bindings": [
    {
      "identity": "*",
      "services": ["/storage/v1", "/bigquery/v2"]
    },
    {
      "identity": "admin@example.com",
      "services": ["*"]
    },
    {
      "identity": "reader@example.com",
      "services": ["/storage/v1"]
    }
  ]
}
```

- `identity`: Email, service account, or `"*"` for anonymous access
- `services`: List of allowed path prefixes, or `["*"]` for all services
- Requests without credentials are checked against the `"*"` wildcard binding

### GCP-Live Mode

Validate bearer tokens against Google's OAuth2 tokeninfo endpoint. Requires real GCP credentials.

```bash
export LOCALCLOUD_IAM_MODE=gcp-live
localcloud start
```

In this mode:
- Requests must include a valid `Authorization: Bearer <token>` header
- Tokens are validated against `https://oauth2.googleapis.com/tokeninfo`
- Validated identities are cached for 5 minutes
- If a local policy file is also configured, path-based permissions are enforced after authentication

---

## Troubleshooting

### Container Won't Start

```bash
# Check if ports are in use
lsof -i :8080
lsof -i :4443

# Check Docker daemon
docker info

# View startup logs
docker logs localcloud-main
```

### Service Shows "unhealthy"

```bash
# Check container logs
docker logs --tail 50 localcloud-main

# Direct health check
curl http://localhost:8080/_localcloud/health | jq '.services'

# Check individual emulator
curl http://localhost:4443  # GCS
```

### SDK Can't Connect

```bash
# Verify environment variables are set
env | grep -E "(EMULATOR_HOST|GOOGLE_CLOUD)"

# Check service health
curl http://localhost:8080/_localcloud/health | jq

# Test connectivity directly
curl http://localhost:4443/storage/v1/b?project=local-project  # GCS
```

### Data Not Persisting

```bash
# Verify persistence is enabled
curl http://localhost:8080/_localcloud/health | jq '.persistence'

# Check volume mount
docker inspect localcloud-main | jq '.[0].Mounts'

# Verify data directory
docker exec localcloud-main ls -la /var/lib/localcloud/
```

### Reset Everything

```bash
# Reset data but keep container
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore seed data
curl -X POST http://localhost:8080/_localcloud/reset \
  -H "Content-Type: application/json" -d '{"restore_seed": true}'

# Full clean restart
docker rm -f localcloud-main
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

### Memory Issues

The JVM uses fixed heap sizing (`-Xmx512m`) to coexist with PostgreSQL and emulator processes. If you hit OOM:

```bash
# Set a container memory limit (recommended: 4GB for all default services)
docker run -m 4g localcloud/localcloud:latest

# Increase JVM heap if needed
docker run -e JAVA_OPTS="-Xmx1g -Xms256m" -m 6g localcloud/localcloud:latest

# Or reduce services to lower memory usage
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 \
  -m 2g \
  -e LOCALCLOUD_SERVICES=gcs,pubsub,firestore \
  localcloud/localcloud:latest
```

**Note:** On Apple Silicon (arm64), some emulators run under QEMU emulation which increases memory usage. Use `-m 4g` or higher.

### Cloud Tasks Not Dispatching

Cloud Tasks dispatches HTTP requests from inside the container. If your target is on the host machine:

```bash
# Use host.docker.internal instead of localhost
# In your task target URL:
# Wrong:  http://localhost:5000/webhook
# Right:  http://host.docker.internal:5000/webhook
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  Docker Container                                               │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Armeria Gateway (port 8080)                            │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │   │
│  │  │ SecretManager│ │ Cloud Tasks  │ │  Logging     │   │   │
│  │  │   (gRPC)     │ │   (gRPC)     │ │   (gRPC)     │   │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘   │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │   │
│  │  │ Monitoring   │ │  GKE         │ │ Compute      │   │   │
│  │  │   (gRPC)     │ │   (gRPC)     │ │   (REST)     │   │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘   │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │   │
│  │  │ Cloud Run    │ │ Memorystore  │ │  Admin API   │   │   │
│  │  │   (gRPC)     │ │  (RESP2)     │ │   (REST)     │   │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │  fake-gcs-server│  │ PubSub Emulator│  │Firestore Emultr│  │
│  │  (port 4443)    │  │ (port 8085)    │  │ (port 8086)    │  │
│  └─────────────────┘  └────────────────┘  └────────────────┘  │
│                                                                 │
│  ┌─────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │Bigtable Emulator│  │Spanner Emulator│  │BigQuery Emultr │  │
│  │  (port 8087)    │  │ (port 9010)    │  │ (port 9050)    │  │
│  └─────────────────┘  └────────────────┘  └────────────────┘  │
│                                                                 │
│  ┌─────────────────┐                                           │
│  │  PostgreSQL 15  │  (internal persistence)                   │
│  │  (port 5432)    │                                           │
│  └─────────────────┘                                           │
│                                                                 │
│  supervisord (process management)                               │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Host Machine                                                   │
│                                                                 │
│  Your Application (uses Google Cloud SDKs)                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Docker Command Reference

| Action | Command |
|--------|---------|
| Start | `docker run -d --name localcloud-main -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 -p 6379:6379 -m 4g -v localcloud-data:/var/lib/localcloud localcloud/localcloud:latest` |
| Stop | `docker stop localcloud-main` |
| Remove | `docker rm -f localcloud-main` |
| Logs | `docker logs localcloud-main` |
| Follow logs | `docker logs -f --tail 200 localcloud-main` |
| Health check | `curl http://localhost:8080/_localcloud/health \| jq` |
| Service status | `curl http://localhost:8080/_localcloud/services \| jq` |
| Load seed data | `curl -X POST http://localhost:8080/_localcloud/seed -H "Content-Type: application/yaml" --data-binary @seed.yaml` |
| Reset all data | `curl -X POST http://localhost:8080/_localcloud/reset` |
