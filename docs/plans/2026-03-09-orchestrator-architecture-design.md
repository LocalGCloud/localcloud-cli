# LocalCloud Orchestrator Architecture Design

**Date**: 2026-03-09
**Status**: Approved
**Supersedes**: Original spec's deep-reimplementation approach

## Problem

The original spec called for deep reimplementation of 10+ GCP services in Java. This is excessive for a local testing tool where **API and functionality compatibility** matters most, not production-grade fidelity. Google and the community already provide mature emulators for most services.

## Decision

LocalCloud becomes an **orchestrator** that:
1. Wraps Google's official emulators and battle-tested third-party emulators
2. Builds thin API facades only for services without existing emulators
3. Provides a unified admin layer, seed loading, and developer experience

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Single Docker Container                    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │          LocalCloud Java Server (Armeria)             │   │
│  │  ┌─────────────┐ ┌──────────┐ ┌──────────────────┐  │   │
│  │  │ Admin API    │ │ Health   │ │ Request Logger   │  │   │
│  │  │  │ │ Aggreg.  │ │ (ring buffer)    │  │   │
│  │  └─────────────┘ └──────────┘ └──────────────────┘  │   │
│  │  ┌─────────────────────────────────────────────────┐ │   │
│  │  │         Thin Facades (our code)                 │ │   │
│  │  │  Secret Manager | Cloud Tasks | Logging | Mon.  │ │   │
│  │  │         (all backed by PostgreSQL)              │ │   │
│  │  └─────────────────────────────────────────────────┘ │   │
│  │  ┌─────────────────────────────────────────────────┐ │   │
│  │  │         Proxy/Router Layer                      │ │   │
│  │  │  Routes requests to underlying emulators        │ │   │
│  │  └─────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌───────────────────── Managed Processes ─────────────────┐ │
│  │                                                         │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ │ │
│  │  │ Pub/Sub  │ │Firestore │ │ Bigtable │ │  Spanner  │ │ │
│  │  │ (Google) │ │ (Google) │ │ (Google) │ │  (Google) │ │ │
│  │  │ :8085    │ │ :8086    │ │ :8087    │ │ :9010/20  │ │ │
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────┘ │ │
│  │                                                         │ │
│  │  ┌──────────────┐ ┌────────────────┐ ┌──────────────┐ │ │
│  │  │fake-gcs-srv  │ │bigquery-emul.  │ │  PostgreSQL  │ │ │
│  │  │ (3rd party)  │ │ (3rd party)    │ │  (backing)   │ │ │
│  │  │ :4443        │ │ :9050/60       │ │  :5432       │ │ │
│  │  └──────────────┘ └────────────────┘ └──────────────┘ │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  supervisord manages all processes                           │
└─────────────────────────────────────────────────────────────┘

Exposed to host:
  :8080  → LocalCloud gateway (admin API + proxy to emulators)
  :9010  → Spanner gRPC (direct passthrough)
  :9020  → Spanner REST (direct passthrough)
```

## Service Mapping

### Delegated to Existing Emulators (zero emulation code from us)

| Service | Emulator | Source | Ports | Maturity |
|---------|----------|--------|-------|----------|
| Cloud Storage | `fake-gcs-server` | Third-party (fsouza) | 4443 | De facto standard, v1.54+ |
| Pub/Sub | Google emulator | `gcloud beta emulators pubsub` | 8085 | Stable |
| Firestore | Google emulator | `gcloud emulators firestore` | 8086 | Stable |
| BigQuery | `bigquery-emulator` | Third-party (goccy) | 9050/9060 | Beta, widely adopted |
| Spanner | Google emulator | `gcr.io/cloud-spanner-emulator/emulator` | 9010/9020 | Most mature |
| Bigtable | Google emulator | `gcloud beta emulators bigtable` | 8087 | Stable |

### Custom Facades (our code, backed by PostgreSQL)

| Service | API Style | What We Build |
|---------|-----------|---------------|
| Secret Manager | gRPC | Create/list/get/access/delete secrets and versions |
| Cloud Tasks | gRPC | Queue CRUD, task CRUD, HTTP dispatch with retry |
| Cloud Logging | gRPC | WriteLogEntries, ListLogEntries (sink mode) |
| Cloud Monitoring | gRPC | CreateTimeSeries, ListTimeSeries (sink mode) |

## Java Server Responsibilities

Our Java server (Armeria) handles:

1. **Gateway routing** — unified `:8080` entry, routes to correct backing emulator based on request path/service
2. **Admin API** — `/*` endpoints: health aggregation, service status, request log browsing, seed loading, state reset
3. **Health aggregation** — polls each emulator process, combines into unified `/health`
4. **Request logging** — ring buffer of last 1000 requests across all services
5. **Seed loading** — parses seed YAML, calls each emulator's API to create initial state (buckets, topics, documents, etc.)
6. **Thin facades** — Secret Manager, Cloud Tasks, Logging, Monitoring implemented as gRPC services backed by PostgreSQL
7. **Environment export** — `/env` returns all emulator endpoints as env vars

## Container Image Strategy

```dockerfile
# Base: Google Cloud SDK emulators (includes Pub/Sub, Firestore, Bigtable)
FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators

# PostgreSQL for our facade backing store
RUN apt-get update && apt-get install -y postgresql postgresql-client supervisor

# Spanner emulator (separate Google image)
COPY --from=gcr.io/cloud-spanner-emulator/emulator /emulator /usr/local/bin/spanner-emulator

# fake-gcs-server
COPY --from=fsouza/fake-gcs-server /fake-gcs-server /usr/local/bin/fake-gcs-server

# bigquery-emulator
COPY --from=ghcr.io/goccy/bigquery-emulator /bigquery-emulator /usr/local/bin/bigquery-emulator

# JRE for our server
RUN apt-get install -y openjdk-21-jre-headless

# Our server JAR
COPY --from=build /app/localcloud-server.jar /opt/localcloud/server.jar

# Supervisord config
COPY supervisord.conf /etc/supervisor/conf.d/

EXPOSE 8080 9010 9020
VOLUME /var/lib/localcloud

CMD ["/usr/bin/supervisord"]
```

Estimated image size: ~2-2.5GB. Acceptable for a dev/test tool.

## Process Management (supervisord)

Supervised processes:
- `postgresql` — backing store for facades
- `pubsub-emulator` — `gcloud beta emulators pubsub start --host-port=0.0.0.0:8085`
- `firestore-emulator` — `gcloud emulators firestore start --host-port=0.0.0.0:8086`
- `bigtable-emulator` — `gcloud beta emulators bigtable start --host-port=0.0.0.0:8087`
- `spanner-emulator` — `/usr/local/bin/spanner-emulator`
- `fake-gcs-server` — `/usr/local/bin/fake-gcs-server -scheme both -port 4443`
- `bigquery-emulator` — `/usr/local/bin/bigquery-emulator --project=PROJECT_ID`
- `localcloud-server` — `java -jar /opt/localcloud/server.jar`

All configured with `autorestart=true` and stdout/stderr captured to `/var/log/localcloud/`.

## Selective Service Startup

The `--services` flag controls which emulators to start:

```bash
localcloud start --services gcs,pubsub,firestore
# Only starts: fake-gcs-server, pubsub-emulator, firestore-emulator, postgresql, localcloud-server
```

When a service is not started, its supervisor program is set to `autostart=false`. This reduces resource usage for projects that only need a subset of GCP services.

## Seed Loading Strategy

Seed loading uses each emulator's native API:

| Service | Seed Mechanism |
|---------|---------------|
| GCS | HTTP PUT to fake-gcs-server API to create buckets/objects |
| Pub/Sub | gRPC calls to emulator to create topics/subscriptions |
| Firestore | gRPC calls to emulator to create documents |
| BigQuery | REST calls to bigquery-emulator to create datasets/tables, insert rows |
| Secret Manager | Direct PostgreSQL insert (our facade) |
| Cloud Tasks | Direct PostgreSQL insert (our facade) |

## Environment Variables Export

`eval $(localcloud env)` outputs:

```bash
export STORAGE_EMULATOR_HOST=http://localhost:4443
export PUBSUB_EMULATOR_HOST=localhost:8085
export FIRESTORE_EMULATOR_HOST=localhost:8086
export BIGTABLE_EMULATOR_HOST=localhost:8087
export SPANNER_EMULATOR_HOST=localhost:9010
export BIGQUERY_EMULATOR_HOST=http://localhost:9050
export SECRET_MANAGER_EMULATOR_HOST=localhost:8080
export CLOUD_TASKS_EMULATOR_HOST=localhost:8080
export GCLOUD_PROJECT=local-project
```

Google's client libraries natively respect `*_EMULATOR_HOST` environment variables and skip authentication when they're set.

## What Changes from Original Spec

| Aspect | Original Spec | New Design |
|--------|---------------|------------|
| GCS | Custom Java StorageEmulator + StorageDao | Proxy to `fake-gcs-server` |
| Pub/Sub | Custom gRPC PubSubEmulator + PubSubStore | Proxy to Google emulator |
| Firestore | Custom gRPC FirestoreEmulator + FirestoreStore | Proxy to Google emulator |
| BigQuery | Custom SqlQueryEngine on H2 | Proxy to `bigquery-emulator` |
| Spanner | Custom gRPC SpannerEmulator | Proxy to Google emulator |
| Bigtable | Custom gRPC BigtableEmulator | Proxy to Google emulator |
| Backing DB | H2 embedded | PostgreSQL (for facades only) |
| Est. task count | 97 tasks | ~30-40 tasks |
| Custom emulator code | ~20 files | ~8 files (4 facades + routing) |
| Container | Single Java process | supervisord managing ~8 processes |

### Eliminated Code
- All DAO/Store classes for delegated services
- `SqlQueryEngine.java` (BigQuery SQL translation)
- `FirestoreProtoHelper.java` (proto marshalling)
- `PushDeliveryService.java` (Pub/Sub push — emulator handles it)
- `EventBus.java` (cross-service events — handled per-emulator)
- Most of `emulators/` package (~12 files)

### Retained Code
- Admin API (`AdminApiService`, `BrowseService`, `HealthCheckService`)
- Request logging (`RequestLogger`)
- Configuration (`LocalCloudConfig`)
- Gateway routing (`ApiGateway`) — simplified to proxy logic
- Seed loading (`SeedService`) — adapted to call emulator APIs
- CLI (`localcloud-cli/`) — unchanged
- Docker setup — updated for multi-process

### New Code
- `supervisord.conf` — process definitions
- `ProcessHealthChecker.java` — poll emulator health endpoints
- PostgreSQL schema for facades (secrets, tasks, logs, metrics tables)
- 4 thin facade implementations (Secret Manager, Cloud Tasks, Logging, Monitoring)
- Proxy routing configuration

## Priority Order

1. **P0 — Foundation**: Container image, supervisord, PostgreSQL, admin API, health aggregation
2. **P1 — Core Services**: GCS (fake-gcs-server), Pub/Sub (Google), Firestore (Google), BigQuery (bigquery-emulator)
3. **P2 — Facades**: Secret Manager, Cloud Tasks (PostgreSQL-backed)
4. **P3 — Extended**: Spanner, Bigtable (Google emulators), Logging/Monitoring (sink facades)

## Success Criteria

1. `localcloud start` brings up all selected emulators within 60 seconds
2. `eval $(localcloud env)` configures all GCP client libraries without code changes
3. Standard GCP SDK operations work against each emulated service
4. Seed files populate initial state across all services
5. `/health` reports aggregated status of all processes
6. Container memory stays under 2GB with all services running
