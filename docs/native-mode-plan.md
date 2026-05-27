# Migrating from Docker to Native Process Mode

> **Status:** Planning document — not yet implemented
> **Target audience:** Developers who want to run LocalCloud services natively (no Docker)

---

## Table of Contents

1. [Why Native Mode?](#1-why-native-mode)
2. [Service Architecture](#2-service-architecture)
3. [What Changes](#3-what-changes)
4. [What Stays the Same](#4-what-stays-the-same)
5. [Installation](#5-installation)
6. [Usage](#6-usage)
7. [Pros and Cons](#7-pros-and-cons)
8. [Transition Plan](#8-transition-plan)
9. [Docker Coexistence](#9-docker-coexistence)

---

## 1. Why Native Mode?

Currently, LocalCloud runs as a single Docker container managed by supervisord. All 23 GCP emulated services run inside that container. Native mode removes the Docker dependency and runs each service as a host process.

**Primary motivations:**
- Faster iteration (no Docker build → restart cycle for Java changes)
- Direct JVM debugging without remote debug or `docker exec`
- No Docker Desktop dependency — works on bare Linux, CI runners, etc.
- Lower memory overhead (~400MB savings vs Docker + container JVM overhead)
- Unified architecture with the license server (which already runs natively)

---

## 2. Service Architecture

### Service Map

Two categories of services: **facades** (in-process on the Java gateway) and **external** (separate binaries).

```
TYPE         SERVICE              PORT    BINARY / SOURCE
──────      ──────               ────    ─────────────────────────
facade      Pub/Sub              (8080)  Java gateway (in-process)
facade      Secret Manager       (8080)  Java gateway
facade      Cloud Tasks          (8080)  Java gateway
facade      Cloud Logging        (8080)  Java gateway
facade      Cloud Monitoring     (8080)  Java gateway
facade      GKE                  (8080)  Java gateway
facade      Compute Engine       (8080)  Java gateway
facade      Cloud Run            (8080)  Java gateway
facade      Cloud Workflows      (8080)  Java gateway
facade      Cloud Scheduler      (8080)  Java gateway
facade      Cloud Functions      (8080)  Java gateway
facade      AlloyDB              (8080)  Java gateway
facade      Dataproc             (8080)  Java gateway
facade      Cloud IAM            (8080)  Java gateway
facade      Vertex AI            (8080)  Java gateway
facade      Cloud KMS            (8080)  Java gateway
facade      Cloud SQL            (8080)  Java gateway
──────      ──────               ────    ─────────────────────────
external    GCS                  4443    fake-gcs-server
external    Firestore            8086    cloud-firestore-emulator (JAR)
external    Bigtable             8087    localcloud-bigtable-emulator (Go)
external    Spanner            9010/9020 spanner-emulator-wrapper (Go)
external    BigQuery           9050/9060 bigquery-emulator (Python + DuckDB)
external    Memorystore (Valkey) 6379    valkey-server
external    PostgreSQL           5432    postgres (system install)
──────      ──────               ────    ─────────────────────────
gateway     Java Server          8080    java -jar localcloud-server-*.jar
license     License Server       9090    java -jar localcloud-license-server-*.jar
console     Web UI               (8080)  Served by gateway at /
```

### Facade Services (17 services, zero external processes)

These run inside the Java gateway process and require no external binary:

| Service | Protocol | gRPC Path |
|---------|----------|-----------|
| Pub/Sub | gRPC | `/google.pubsub` |
| Secret Manager | gRPC | `/google.cloud.secretmanager` |
| Cloud Tasks | gRPC | `/google.cloud.tasks` |
| Cloud Logging | gRPC | `/google.logging` |
| Cloud Monitoring | gRPC | `/google.monitoring` |
| GKE | gRPC | `/google.container` |
| Compute Engine | REST | `/compute/v1` |
| Cloud Run | gRPC | `/google.cloud.run` |
| Cloud Workflows | gRPC | `/google.cloud.workflows` |
| Vertex AI | REST | `/v1/projects` |
| Cloud KMS | REST | `/v1/projects` |
| Cloud SQL | REST | `/v1/projects` |

### External Services (8 processes)

These are separate binaries that the gateway depends on:

| Service | Port | Start priority | Dependencies |
|---------|------|----------------|-------------|
| PostgreSQL | 5432 | 10 (first) | None |
| Valkey | 6379 | 15 | None |
| GCS | 4443 | 20 | None |
| Firestore | 8086 | 20 | PostgreSQL |
| Bigtable | 8087 | 25 | PostgreSQL |
| Spanner | 9010/9020 | 20 | None (filesystem) |
| BigQuery | 9050/9060 | 20 | GCS (for external tables) |
| Java Gateway | 8080 | 30 | All of the above |

---

## 3. What Changes

### 3.1 Service Lifecycle

```
Current:   docker run → docker-entrypoint.sh → supervisord → 8 processes
Native:    localcloud start → launcher → parallel background processes
```

supervisord is replaced with a native process manager. Options:

- **`overmind`** — Procfile-based, Homebrew-installable. Start/stop/restart individual services.
- **`tmuxp`** — tmux session manager. Each service in its own pane.
- **Minimal bash launcher** — `scripts/localcloud.sh` with `&`, `wait`, and `trap` for cleanup.

Recommended: `overmind` for development (simple Procfile, per-service logs, graceful shutdown).

### 3.2 Service-specific Changes

#### PostgreSQL

```bash
# Currently: embedded in Docker image, started by supervisord
# Native: installed via Homebrew/apt, managed as system service

brew install postgresql@17
brew services start postgresql@17
createdb localcloud
psql localcloud -c "CREATE USER localcloud WITH PASSWORD 'localcloud';"
psql localcloud -c "GRANT ALL ON SCHEMA public TO localcloud;"
```

- Data dir: `~/.localcloud/pgdata` (instead of `/var/lib/localcloud/pgdata`)
- Connection: Unix socket (faster) or TCP on port 5432
- No more embedded PostgreSQL init in `docker-entrypoint.sh`

#### Valkey / Redis

```bash
# Currently: Valkey binary in Docker image
# Native: installed via Homebrew/apt

brew install valkey
valkey-server /usr/local/etc/valkey.conf
```

- Default config: listen on `127.0.0.1:6379`, no auth (dev mode)
- No more custom valkey.conf generation in `docker-entrypoint.sh`

#### GCS (Cloud Storage)

```bash
# Currently: fake-gcs-server Go binary baked into image
# Native: installed via Go

go install github.com/fsouza/fake-gcs-server@latest
fake-gcs-server -scheme http -port 4443 -public-host localhost:4443 \
  -backend filesystem -filesystem-root ~/.localcloud/gcs-data
```

#### Firestore

```bash
# Currently: cloud-firestore-emulator JAR in image
# Native: downloaded JAR or via npm

# Option A: Direct JAR
java -Duser.language=en -jar cloud-firestore-emulator.jar \
  --host=0.0.0.0 --port=8086

# Option B: Firebase CLI (recommended for parity)
npm install -g firebase-tools
firebase emulators:start --only firestore
```

#### Bigtable

```bash
# Currently: localcloud-bigtable-emulator Go binary
# Native: build from source

cd localcloud-server && go build -o /usr/local/bin/bigtable-emulator \
  ./emulators/bigtable/cmd/
bigtable-emulator -host 0.0.0.0 -port 8087 \
  -database-driver postgres \
  -database-url "postgres://localcloud@localhost/localcloud?sslmode=disable"
```

#### Spanner

```bash
# Currently: spanner-gateway + spanner-emulator-wrapper Go binaries
# Native: build from source or use Docker Spanner emulator

spanner-gateway --hostname 0.0.0.0 \
  --grpc_binary /usr/local/bin/spanner-emulator-wrapper \
  --data_dir ~/.localcloud/spanner-data
```

#### BigQuery

```bash
# Currently: Python-based bigquery-emulator (DuckDB + SQLGlot) in virtualenv
# Native: pip install

pip install bigquery-emulator
bigquery-emulator --project=local-project \
  --port=9050 --grpc-port=9060 \
  --database=~/.localcloud/bigquery-data/bigquery.duckdb
```

#### Java Gateway

```bash
# Currently: JAR baked into Docker image, started by supervisord
# Native: built and run directly

cd localcloud-server
./gradlew shadowJar
java -Xmx512m -jar build/libs/localcloud-server-*-all.jar
```

Environment variables (same as Docker, just set in shell instead of `-e` flags):

```bash
export LOCALCLOUD_PROJECT="local-project"
export LOCALCLOUD_SERVICES="gcs,pubsub,firestore,bigquery,secretmanager,cloudtasks,spanner,bigtable,logging,monitoring,memorystore,workflows"
export STORAGE_EMULATOR_HOST="http://localhost:4443"
export PUBSUB_EMULATOR_HOST="localhost:8080"
export FIRESTORE_EMULATOR_HOST="localhost:8086"
export BIGTABLE_EMULATOR_HOST="localhost:8087"
export SPANNER_EMULATOR_HOST="localhost:9010"
export BIGQUERY_EMULATOR_HOST="http://localhost:9050"
export REDIS_HOST="localhost:6379"
```

#### License Server

```bash
# Already native (never was in Docker)

cd localcloud-license-server
ADMIN_PASSWORD=your-password \
  LICENSE_DB_URL=jdbc:postgresql://localhost:5432/localcloud \
  LICENSE_DB_USER=localcloud \
  LICENSE_DB_PASSWORD=localcloud \
  java -jar build/libs/localcloud-license-server-*-all.jar
```

### 3.3 Infrastructure Changes

| Docker-specific step | Native equivalent |
|----------------------|-------------------|
| CA certificate auto-detection | Not needed (uses system trust store) |
| Container hostname detection | Not applicable |
| Volume mounts for data | `~/.localcloud/data/` directory |
| Health check (wait-for-pg.sh) | `pg_isready` or `redis-cli ping` |
| Data directory creation | `mkdir -p ~/.localcloud/{gcs-data,spanner-data,bigquery-data}` |
| Seed data loading | `curl -X POST http://localhost:8080/seed` (same) |

### 3.4 Port Configuration

Native mode uses host ports directly. The default port allocations match the Docker container:

| Port | Service | Notes |
|------|---------|-------|
| 5432 | PostgreSQL | Common conflict — change via `PGPORT` / `LICENSE_DB_URL` |
| 6379 | Valkey | Common conflict — change via valkey config |
| 4443 | GCS | Less common conflict |
| 8080 | Java Gateway | Common conflict — change via `LOCALCLOUD_PORT` |
| 8086 | Firestore | |
| 8087 | Bigtable | |
| 9010 | Spanner (gRPC) | |
| 9020 | Spanner (REST) | |
| 9050 | BigQuery (REST) | |
| 9060 | BigQuery (gRPC) | |
| 9090 | License Server | Change via `LICENSE_PORT` |

---

## 4. What Stays the Same

### 4.1 No Code Changes Required

- All **17 facade services** (in-process Java) — zero changes
- The `services.yaml` registry — still the single source of truth
- The web console — same build process (`npm run build`)
- All SDK integration code — same endpoints on `localhost`
- The `services.yaml` service definitions — same ports, same protocols
- License validation flow — same API key, same device binding

### 4.2 Same Build Commands

```bash
# Build server JAR (same as Docker mode)
cd localcloud-server && ./gradlew shadowJar

# Build console (same as Docker mode)
cd localcloud-console && npm run build

# Build license server JAR (same as now)
cd localcloud-license-server && ./gradlew shadowJar
```

---

## 5. Installation

### 5.1 Prerequisites

```bash
# Java 21+
java --version

# Node.js 20+ (for console build)
node --version

# Python 3.11+ (for BigQuery emulator)
python3 --version

# Go 1.22+ (for Go-based emulators — optional, can use prebuilt binaries)
go version
```

### 5.2 Install Script (Proposed)

A `scripts/install.sh` script would:

```bash
# Detect OS — macOS, Ubuntu, Debian, Fedora
OS=$(uname -s)

# Install system packages
if [ "$OS" = "Darwin" ]; then
    brew install postgresql@17 valkey overmind
elif [ "$OS" = "Linux" ]; then
    sudo apt-get install postgresql-17 valkey-server
fi

# Install Go-based emulators
go install github.com/fsouza/fake-gcs-server@latest

# Install Python-based emulators
pip install bigquery-emulator

# Create data directories
mkdir -p ~/.localcloud/{gcs-data,spanner-data,bigquery-data}

# Download Firestore emulator JAR
curl -Lo /usr/local/lib/cloud-firestore-emulator.jar \
  https://storage.googleapis.com/firestore-emulator/cloud-firestore-emulator.jar

# Verify all dependencies
scripts/install.sh --check
```

### 5.3 Dependency Version Lockfile (Proposed)

`config/localcloud-deps.json`:

```json
{
  "postgresql": "17",
  "valkey": "8.0",
  "fake-gcs-server": "1.47.0",
  "firestore-emulator": "latest",
  "bigquery-emulator": "0.9.0",
  "spanner-emulator": "1.5.0",
  "bigtable-emulator": "0.14.0"
}
```

---

## 6. Usage

### 6.1 Quick Start

```bash
# 1. Install dependencies
./scripts/install.sh

# 2. Start all services
./scripts/localcloud.sh start

# 3. Open web console
open http://localhost:8080
```

### 6.2 Proposed CLI

A `localcloud` CLI (lightweight shell script or Go tool):

```bash
localcloud start        # Start all services
localcloud stop         # Graceful shutdown all services
localcloud restart      # Restart all services
localcloud status       # Health check per service
localcloud logs         # Tail logs (or localcloud logs <service>)
localcloud logs -f      # Follow all logs
localcloud ps           # List running processes and ports
```

### 6.3 Procfile (for `overmind`)

```yaml
# Procfile
postgresql: pg_ctl -D ~/.localcloud/pgdata -l ~/.localcloud/logs/postgresql.log start
valkey: valkey-server ~/.localcloud/valkey.conf
gcs: fake-gcs-server -scheme http -port 4443 -public-host localhost:4443 -backend filesystem -filesystem-root ~/.localcloud/gcs-data
firestore: java -Duser.language=en -jar /usr/local/lib/cloud-firestore-emulator.jar --host=0.0.0.0 --port=8086
bigtable: bigtable-emulator -host 0.0.0.0 -port 8087 -database-driver postgres -database-url "postgres://localcloud@localhost/localcloud?sslmode=disable"
spanner: spanner-gateway --hostname 0.0.0.0 --grpc_binary /usr/local/bin/spanner-emulator-wrapper --data_dir ~/.localcloud/spanner-data
bigquery: bigquery-emulator --project=local-project --port=9050 --grpc-port=9060 --database=~/.localcloud/bigquery-data/bigquery.duckdb
gateway: java -Xmx512m -jar localcloud-server/build/libs/localcloud-server-*-all.jar
license: java -Xmx256m -jar localcloud-license-server/build/libs/localcloud-license-server-*-all.jar
```

Usage: `overmind start`

---

## 7. Pros and Cons

### 7.1 Pros

| Pro | Detail |
|-----|--------|
| **Faster iteration** | Edit Java → `./gradlew shadowJar` → restart process. Seconds vs minutes for Docker build + image rebuild. |
| **No Docker dependency** | Works on systems without Docker. Lighter CI/CD pipelines. No Docker Desktop license concerns. |
| **Direct debugging** | `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` — attach IntelliJ/VS Code debugger directly. No remote debug or `docker exec` needed. |
| **Resource efficiency** | ~600MB vs ~1.2GB for Docker + container overhead. No `-m 4g` memory cap. |
| **Better macOS integration** | PostgreSQL and Valkey via Homebrew — standard macOS dev setup. |
| **Unified architecture** | License server already runs natively. Both services share the same PostgreSQL instance. |
| **Simpler CI** | Single `pip install` + `go install` instead of Docker-in-Docker or pulling images. |

### 7.2 Cons

| Con | Detail | Mitigation |
|-----|--------|-----------|
| **Complex setup** | 8+ external binaries to install and version-manage | `install.sh` script + version lockfile |
| **Version drift** | Emulator versions diverge between devs | Pin versions in lockfile, `install.sh --check` validates |
| **Platform gaps** | Go binaries fine everywhere, but build matrix expands | Docker mode stays as fallback and CI canonical mode |
| **Port conflicts** | 5432 (PG), 6379 (Redis) etc. already in use | Configurable via env vars, documented defaults |
| **No process supervision** | No supervisord auto-restart on crash | `overmind` or `systemd` user services |
| **No single command** | Need to start 8 processes instead of one `docker run` | `localcloud start` CLI abstracts this |

---

## 8. Transition Plan

### Phase 1: Install Script

**File:** `scripts/install.sh`

- Detect OS (macOS via Homebrew, Linux via apt/yum)
- Install PostgreSQL, Valkey, overmind
- Install Go-based emulators (fake-gcs-server, bigtable, spanner)
- Install Python-based emulators (bigquery-emulator)
- Download Firestore emulator JAR
- Create data directories
- Validate all deps with `--check` flag

### Phase 2: Launcher Script

**File:** `scripts/localcloud.sh`

- `start` — starts all 8 external processes in order (respecting priorities)
- `stop` — graceful shutdown (SIGTERM → wait → SIGKILL)
- `status` — health check per service
- `logs` — tail combined or per-service logs
- Backgrounds processes, captures PIDs, writes PID file for `stop`

### Phase 3: CLI (Optional Enhancement)

A small Go CLI for a more polished experience:

```bash
go install github.com/localcloud/cli@latest
localcloud up
```

- Integrates with `services.yaml` for port/service definitions
- Colorful terminal output
- Startup banner with URLs

### Phase 4: Docker Mode Coexistence

Both modes coexist and share the same configuration:

```
Project Root:
├── scripts/
│   ├── docker-entrypoint.sh     # Docker mode (unchanged)
│   ├── install.sh               # Native mode: install deps
│   └── localcloud.sh            # Native mode: process manager
├── config/
│   ├── services.yaml            # Both modes read this
│   └── supervisord.conf         # Docker mode only
└── Dockerfile                   # Docker mode only
```

**CI/CD strategy:**
- CI runs Docker mode (reproducible, pinned image)
- Devs can choose either mode per preference
- Both modes produce identical env vars via `services.yaml`
- The build smoke test runs in Docker mode before merge

---

## 9. Docker Coexistence

Docker mode is **not deprecated**. It remains the canonical deployment method and the default for CI. Native mode is an alternative for local development.

### Decision Matrix

| Criteria | Native | Docker |
|----------|--------|--------|
| Setup time | 10-15 min (install deps) | 60s (`docker run`) |
| Iteration speed | Seconds (restart JVM) | Minutes (rebuild image) |
| Debugging | Direct JVM attach | Remote debug or exec |
| Resource usage | ~600MB | ~1.2GB |
| Reproducibility | Moderate (platform-sensitive) | High (pinned image) |
| Port conflicts | Risk | None (isolated) |
| Learning curve | Higher (8 processes) | Low (single container) |
| CI complexity | Higher | Lower |
| Offline capability | Full (all local) | Full (all in container) |

### When to Use Each

**Use Docker when:**
- Onboarding a new developer (fastest path to working environment)
- Running CI/CD (reproducible, no platform variability)
- Running integration tests (isolated, clean state per run)
- You don't want to install 8 dependencies locally

**Use Native when:**
- Actively developing the Java gateway (frequent rebuilds)
- Debugging connection issues or service interactions
- Running the license server alongside (shared PostgreSQL)
- You already have PostgreSQL/Valkey installed (reuse existing)
- Working offline with limited disk/memory for Docker

---

*Last updated: 2026-05-15*
*See also: `services.yaml` for service definitions, `docs/` for emulator-specific docs*
