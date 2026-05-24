# LocalCloud Developer Guide

**Google Cloud Platform — In-a-Box.**

LocalCloud emulates 15 GCP services in a single Docker container. Develop and test locally without cloud access, approvals, or costs. Zero code changes — just set environment variables.

---

## Quick Start

```bash
# Pull and start
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Configure your shell (one command)
eval "$(curl -s http://localhost:8080/env?format=shell)"

# Use GCP SDKs as normal — they now point to localhost
python -c "
from google.cloud import storage
client = storage.Client()
bucket = client.create_bucket('my-bucket')
print(f'Created: {bucket.name}')
"
```

Open **http://localhost:8080** for the web console — it includes an interactive setup guide, SQL editor, data explorer, SDK examples, and environment variable export.

### Common Options

```bash
# Custom project ID
docker run -d --name localcloud ... -e LOCALCLOUD_PROJECT=my-project localcloud/localcloud:latest

# Only specific services (fewer ports needed)
docker run -d --name localcloud -p 8080:8080 -p 4443:4443 -p 8085:8085 -m 4g \
  -e LOCALCLOUD_SERVICES=gcs,pubsub,firestore localcloud/localcloud:latest

# With pre-populated seed data
docker run -d --name localcloud ... \
  -v ./seed.yaml:/etc/localcloud/seed.yaml:ro localcloud/localcloud:latest
```

### Lifecycle

```bash
docker stop localcloud          # Stop
docker start localcloud         # Restart (data persists)
docker rm -f localcloud         # Remove container
docker logs -f localcloud       # Follow logs
curl localhost:8080/health | jq   # Health check
```

### Custom Builds

```bash
# Build without license enforcement (default — no license key needed)
docker build -t localcloud/localcloud:dev .                              # ENFORCE_LICENSE=false

# Build with license enforcement (requires LOCALCLOUD_API_KEY at runtime)
docker build -t localcloud/localcloud:prod --build-arg ENFORCE_LICENSE=true .

# With a custom license server public key
docker build -t localcloud/localcloud:prod \
  --build-arg ENFORCE_LICENSE=true \
  --build-arg LICENSE_PUBLIC_KEY="$(cat my-pubkey.pem)" \
  .
```

When `ENFORCE_LICENSE=false` (default), the container starts without checking for any license key, API key, or license server. The web console shows "PRO" tier. Suitable for local development, CI/CD, and demos.

When `ENFORCE_LICENSE=true`, the container enforces full license validation at startup. A valid `LOCALCLOUD_API_KEY` environment variable or connection to a license server is required or the container exits immediately.

---

## Connecting Your Application

```bash
# Auto-configure all env vars
eval "$(curl -s http://localhost:8080/env?format=shell)"
```

Or set manually:

| Variable | Value | Service |
|----------|-------|---------|
| `STORAGE_EMULATOR_HOST` | `http://localhost:4443` | Cloud Storage |
| `PUBSUB_EMULATOR_HOST` | `localhost:8085` | Pub/Sub |
| `FIRESTORE_EMULATOR_HOST` | `localhost:8086` | Firestore |
| `BIGTABLE_EMULATOR_HOST` | `localhost:8087` | Bigtable |
| `SPANNER_EMULATOR_HOST` | `localhost:9010` | Spanner |
| `BIGQUERY_EMULATOR_HOST` | `http://localhost:9050` | BigQuery |
| `SECRET_MANAGER_EMULATOR_HOST` | `localhost:8080` | Secret Manager |
| `CLOUD_TASKS_EMULATOR_HOST` | `localhost:8080` | Cloud Tasks |
| `REDIS_HOST` | `localhost` | Memorystore |
| `GOOGLE_CLOUD_PROJECT` | `local-project` | All services |

**To switch back to real GCP:** unset the emulator variables or open a new terminal. No code changes needed.

> SDK code examples (Python, Java) are available in the web console under **Settings > SDK**.

---

## Terraform Integration

LocalCloud works as a drop-in replacement for Google Cloud in Terraform workflows. No changes to `.tf` files — just set environment variables.

### Quick Start

```bash
# Point Terraform at LocalCloud (one command)
eval $(curl -s 'http://localhost:8080/env?format=terraform')

# Run Terraform normally
terraform init
terraform plan
terraform apply
```

This sets `GOOGLE_*_CUSTOM_ENDPOINT` env vars that the Google Terraform provider reads automatically. Authentication is skipped (permissive IAM mode).

### Supported Resources (Phase 1)

| Terraform Resource | LocalCloud Emulator |
|---|---|
| `google_storage_bucket` / `google_storage_bucket_object` | GCS (fake-gcs-server) |
| `google_pubsub_topic` / `google_pubsub_subscription` | Pub/Sub emulator |
| `google_bigquery_dataset` / `google_bigquery_table` | BigQuery (DuckDB) |
| `google_spanner_instance` / `google_spanner_database` | Spanner emulator |

Phase 2 (Secret Manager, Cloud Tasks) and Phase 3 (Compute, Cloud Run, GKE, Memorystore) are planned — see [Terraform Compatibility Matrix](terraform/COMPATIBILITY.md) for full details.

### Docker Compose with Terraform

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports:
      - "8080:8080"
      - "4443:4443"
      - "8085:8085"
      - "9050:9050"
      - "9010:9010"
    mem_limit: 4g
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      retries: 5
```

```bash
# After container is healthy
eval $(curl -s 'http://localhost:8080/env?format=terraform')
terraform apply
```

### CI/CD (GitHub Actions)

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports: ["8080:8080", "4443:4443", "8085:8085", "9050:9050", "9010:9010"]
    options: --memory 4g

steps:
  - run: eval $(curl -s http://localhost:8080/env?format=terraform)
  - run: terraform init && terraform apply -auto-approve
  - run: terraform destroy -auto-approve
```

See `terraform/examples/` for complete configs and pipeline examples.

---

## Emulated Services

### Port Map

| Port | Service | Protocol |
|------|---------|----------|
| 8080 | Gateway (Admin API, Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run, Cloud Workflows) | REST + gRPC |
| 4443 | Cloud Storage | HTTP |
| 8085 | Pub/Sub | gRPC |
| 8086 | Firestore | gRPC |
| 8087 | Bigtable | gRPC |
| 9010 | Spanner (gRPC) | gRPC |
| 9020 | Spanner (REST) | REST |
| 9050 | BigQuery (REST) | REST |
| 9060 | BigQuery (gRPC) | gRPC |
| 6379 | Memorystore (Redis) | RESP2 |

### Service Details

| Service | Supported | Not Supported |
|---------|-----------|---------------|
| **Cloud Storage** | Bucket CRUD, object upload/download/list/delete/copy, metadata | Versioning, lifecycle execution, CMEK |
| **Pub/Sub** | Topics, subscriptions, publish, pull, streaming pull, ack | Schema validation, BQ/GCS subscriptions |
| **Firestore** | Document CRUD, collection queries, batch writes, listeners | Composite indexes, aggregation queries |
| **BigQuery** | ~95% Standard SQL (DQL/DDL/DML), 120+ functions, JOINs, CTEs, window functions, UNNEST, PIVOT, external tables (Parquet/CSV/JSON), gRPC Storage API, INFORMATION_SCHEMA | Scripting (IF/LOOP), stored procedures, BQML, geography functions, partitioned table execution |
| **Secret Manager** | Secret CRUD, version management, enable/disable/destroy | Rotation, CMEK, per-secret IAM |
| **Cloud Tasks** | Queue CRUD, HTTP tasks, auto-dispatch with retries | App Engine tasks, OAuth token generation |
| **Spanner** | Instance/DB CRUD, DDL, sessions, ExecuteSql, transactions | Partitioned DML, change streams |
| **Bigtable** | Tables, column families, ReadRows, MutateRow, CheckAndMutate | Instance management, backup/restore |
| **Memorystore** | GET/SET/DEL, lists, sets, hashes, sorted sets, TTL, KEYS | Pub/Sub, Lua, streams, MULTI/EXEC |
| **Cloud Logging** | WriteLogEntries, ListLogEntries, ListLogs, DeleteLog | Metrics, sinks, exclusions, audit logs |
| **Cloud Monitoring** | CreateTimeSeries, ListTimeSeries, metric descriptors | Alerting, uptime checks, dashboards |
| **GKE** | Cluster CRUD (creates real k3d clusters when available) | Node pools, auto-scaling, upgrades |
| **Compute Engine** | Instance CRUD, start/stop (Docker containers as VMs) | Disks, snapshots, templates, networking |
| **Cloud Run** | Service CRUD, revisions (real Docker containers) | Traffic splitting, custom domains, Jobs |
| **Cloud Workflows** | YAML workflow definitions, expression language, all step types (assign, call, switch, for, parallel, try/except, raise, return), standard library (http, sys, json, base64, math, text, list, map), connector shims to other LocalCloud emulators, callback support | Persistent execution checkpointing (in-flight executions lost on restart), KMS encryption, IAM enforcement |

GKE, Compute, and Cloud Run are disabled by default (require Docker socket). Enable with:
```bash
docker run ... -e LOCALCLOUD_ENABLE_GKE=true -v /var/run/docker.sock:/var/run/docker.sock ...
```

---

## Cloud Workflows

**Cloud Workflows** is a serverless orchestration service for chaining service calls into reliable, repeatable pipelines. LocalCloud emulates it at port 8080 via the REST browse API — no separate process or port required.

### Features

- YAML workflow definitions with full expression language support
- All step types: `assign`, `call`, `switch`, `for`, `parallel`, `try/except`, `raise`, `return`
- Standard library: `http`, `sys`, `json`, `base64`, `math`, `text`, `list`, `map`
- Connector shims to other LocalCloud emulators (GCS, Pub/Sub, Secret Manager, Cloud Tasks, etc.)
- Callback support for pausing and resuming executions

### Limitations

- No persistent execution checkpointing — in-flight executions are lost on container restart
- No KMS encryption of workflow definitions or execution data
- No IAM enforcement — all workflows and executions are accessible without credentials

### Browse API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/browse/workflows` | List all workflows |
| GET | `/browse/workflows/{id}` | Get workflow definition and metadata |
| GET | `/browse/workflows/{id}/executions` | List executions for a workflow |

```bash
# List all workflows
curl http://localhost:8080/browse/workflows | jq

# Get a specific workflow
curl http://localhost:8080/browse/workflows/my-workflow | jq

# List executions for a workflow
curl http://localhost:8080/browse/workflows/my-workflow/executions | jq
```

### Seed Format

```yaml
services:
  workflows:
    workflows:
      - name: "notify-on-upload"
        description: "Send a Pub/Sub notification when a GCS object is uploaded"
        source_contents: |
          main:
            steps:
              - init:
                  assign:
                    - bucket: ${sys.get_env("BUCKET_NAME")}
                    - topic: "upload-events"
              - publish:
                  call: http.post
                  args:
                    url: ${"http://localhost:8085/v1/projects/local-project/topics/" + topic + ":publish"}
                    body:
                      messages:
                        - data: ${base64.encode(json.encode({"bucket": bucket}))}
                  result: publish_response
              - done:
                  return: ${publish_response.body}
```

---

## Seed Data

Mount a YAML file to pre-populate services on startup:

```yaml
services:
  gcs:
    buckets:
      - name: "data-bucket"
        objects:
          - key: "config.json"
            content: '{"debug": true}'
  pubsub:
    topics:
      - name: "events"
        subscriptions:
          - name: "processor"
  secretmanager:
    secrets:
      - name: "api-key"
        versions:
          - data: "sk-live-abc123"
  bigquery:
    datasets:
      - name: "analytics"
        tables:
          - name: "events"
            schema:
              - { name: "event_id", type: "STRING" }
              - { name: "user_id", type: "STRING" }
```

```bash
# Load on startup
docker run -d --name localcloud ... -v ./seed.yaml:/etc/localcloud/seed.yaml:ro ...

# Load into running instance
curl -X POST http://localhost:8080/seed \
  -H "Content-Type: application/yaml" --data-binary @seed.yaml

# Reset all data (or reset + restore seed)
curl -X POST http://localhost:8080/reset
curl -X POST http://localhost:8080/reset \
  -H "Content-Type: application/json" -d '{"restore_seed": true}'
```

---

## Multi-Project Support

LocalCloud supports multiple GCP projects with isolated data per project.

```bash
# Create a project
curl -X POST http://localhost:8080/projects \
  -H "Content-Type: application/json" \
  -d '{"project_id": "staging", "display_name": "Staging"}'

# Browse data scoped to a project
curl http://localhost:8080/browse/gcs?project=staging

# Delete a project (cascades all data)
curl -X DELETE http://localhost:8080/projects/staging
```

The web console includes a project switcher dropdown in the topbar.

---

## Web Console

Open **http://localhost:8080** — no separate server needed.

| Page | What It Does |
|------|-------------|
| **Dashboard** | Service health, project info, uptime, request counts |
| **APIs & Services** | All 15 services with status, ports, routing, env vars |
| **Service Explorer** | SQL editor with schema browser, data explorer for all services |
| **Logs** | Real-time request log viewer with filtering |
| **Usage** | Cumulative API usage per service, estimated GCP cost savings |
| **Settings** | Environment variable export, SDK examples, cloud routing, preferences |

The Settings page includes a complete setup guide with copy-paste commands for shell, Docker Compose, and SDK configuration.

---

## Admin API

All endpoints are at `/` on port 8080.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health status |
| GET | `/services` | List services with ports and request counts |
| GET | `/env?format=shell` | Environment variables (shell/json/docker-compose) |
| GET | `/usage` | Cumulative usage metrics per service |
| GET | `/browse/{service}` | Browse service data (read-only) |
| POST | `/seed` | Load seed data (YAML body) |
| POST | `/reset` | Reset all data |
| GET | `/projects` | List projects |
| POST | `/projects` | Create project |
| DELETE | `/projects/{id}` | Delete project and all data |

---

## Docker Compose Integration

If your app already uses Docker Compose, add LocalCloud as a service:

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
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
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  my-app:
    build: .
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:4443"
      PUBSUB_EMULATOR_HOST: "localcloud:8085"
      FIRESTORE_EMULATOR_HOST: "localcloud:8086"
      GOOGLE_CLOUD_PROJECT: "my-project"
    depends_on:
      localcloud:
        condition: service_healthy

volumes:
  localcloud-data:
```

> Note: Inside Docker Compose, use `localcloud` (the service name) instead of `localhost` for emulator hosts.

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `LOCALCLOUD_PROJECT` | `local-project` | GCP project ID |
| `LOCALCLOUD_SERVICES` | all 11 enabled | Comma-separated service list |
| `LOCALCLOUD_DATA_DIR` | `/var/lib/localcloud` | Persistent data directory |
| `LOCALCLOUD_IAM_MODE` | `permissive` | `permissive`, `strict`, or `gcp-live` |
| `JAVA_OPTS` | `-Xmx512m -Xms128m` | JVM tuning (override for more memory) |

Container needs `-m 4g` memory for all default services. Use `-m 2g` if running fewer services.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Container won't start | Check port conflicts: `lsof -i :8080` |
| Service shows unhealthy | Check logs: `docker logs localcloud` |
| SDK can't connect | Verify env vars: `env \| grep EMULATOR` |
| Data not persisting | Check volume: `docker inspect localcloud \| jq '.[0].Mounts'` |
| Cloud Tasks not dispatching | Use `host.docker.internal` instead of `localhost` in task URLs |
| Out of memory | Use `-m 4g` or reduce services with `LOCALCLOUD_SERVICES` |

---

## Architecture

```
Docker Container
├── Armeria Gateway (port 8080)
│   ├── Admin API + Web Console
│   ├── Secret Manager, Cloud Tasks, Logging, Monitoring (gRPC facades)
│   ├── GKE, Compute, Cloud Run (gRPC/REST facades)
│   └── Memorystore (RESP2 on port 6379)
├── External Emulators (managed by supervisord)
│   ├── fake-gcs-server (port 4443)
│   ├── Pub/Sub emulator (port 8085)
│   ├── Firestore emulator (port 8086)
│   ├── Bigtable emulator (port 8087)
│   ├── Spanner emulator (port 9010)
│   └── BigQuery emulator (port 9050, DuckDB+SQLGlot)
└── PostgreSQL 15 (internal persistence)
```

> **Building from source?** See [README.md](README.md) for build instructions using `./gradlew shadowJar` and `docker compose build`.
