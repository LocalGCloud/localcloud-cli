# LocalCloud Orchestrator Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transform LocalCloud from a deep GCP reimplementation into an orchestrator that wraps existing emulators and builds thin facades for the rest.

**Architecture:** Single Docker container using supervisord to manage Google's official emulators (Pub/Sub, Firestore, Bigtable, Spanner), third-party emulators (fake-gcs-server, bigquery-emulator), PostgreSQL for facade backing, and our Java gateway server for admin API + thin facades (Secret Manager, Cloud Tasks, Logging, Monitoring).

**Tech Stack:** Java 21 + Armeria, PostgreSQL 15, supervisord, Google Cloud SDK emulators, fake-gcs-server, bigquery-emulator, Python CLI (Click + Docker SDK)

---

## Phase 1: Container Infrastructure

### Task 1: Create supervisord configuration

**Files:**
- Create: `supervisord.conf`

**Step 1: Write supervisord.conf**

```ini
[supervisord]
nodaemon=true
logfile=/var/log/localcloud/supervisord.log
pidfile=/var/run/supervisord.pid
childlogdir=/var/log/localcloud

[program:postgresql]
command=/usr/lib/postgresql/15/bin/postgres -D /var/lib/localcloud/pgdata
user=localcloud
autostart=true
autorestart=true
priority=10
stdout_logfile=/var/log/localcloud/postgresql.log
stderr_logfile=/var/log/localcloud/postgresql-error.log

[program:pubsub-emulator]
command=gcloud beta emulators pubsub start --host-port=0.0.0.0:8085 --project=%(ENV_LOCALCLOUD_PROJECT)s
autostart=%(ENV_LOCALCLOUD_ENABLE_PUBSUB)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/pubsub.log
stderr_logfile=/var/log/localcloud/pubsub-error.log

[program:firestore-emulator]
command=gcloud emulators firestore start --host-port=0.0.0.0:8086 --project=%(ENV_LOCALCLOUD_PROJECT)s
autostart=%(ENV_LOCALCLOUD_ENABLE_FIRESTORE)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/firestore.log
stderr_logfile=/var/log/localcloud/firestore-error.log

[program:bigtable-emulator]
command=gcloud beta emulators bigtable start --host-port=0.0.0.0:8087
autostart=%(ENV_LOCALCLOUD_ENABLE_BIGTABLE)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/bigtable.log
stderr_logfile=/var/log/localcloud/bigtable-error.log

[program:spanner-emulator]
command=/usr/local/bin/spanner-emulator
autostart=%(ENV_LOCALCLOUD_ENABLE_SPANNER)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/spanner.log
stderr_logfile=/var/log/localcloud/spanner-error.log

[program:fake-gcs-server]
command=/usr/local/bin/fake-gcs-server -scheme both -port 4443 -backend filesystem -filesystem-root /var/lib/localcloud/gcs-data
autostart=%(ENV_LOCALCLOUD_ENABLE_GCS)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/gcs.log
stderr_logfile=/var/log/localcloud/gcs-error.log

[program:bigquery-emulator]
command=/usr/local/bin/bigquery-emulator --project=%(ENV_LOCALCLOUD_PROJECT)s --port=9050 --grpc-port=9060
autostart=%(ENV_LOCALCLOUD_ENABLE_BIGQUERY)s
autorestart=true
priority=20
stdout_logfile=/var/log/localcloud/bigquery.log
stderr_logfile=/var/log/localcloud/bigquery-error.log

[program:localcloud-server]
command=java %(ENV_JAVA_OPTS)s -jar /opt/localcloud/server.jar
autostart=true
autorestart=true
priority=30
stdout_logfile=/var/log/localcloud/server.log
stderr_logfile=/var/log/localcloud/server-error.log
```

**Step 2: Commit**

```bash
git add supervisord.conf
git commit -m "feat: add supervisord config for multi-process container"
```

---

### Task 2: Rewrite Dockerfile for orchestrator model

**Files:**
- Modify: `Dockerfile`

**Step 1: Write new Dockerfile**

```dockerfile
# Stage 1: Build Java server
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY localcloud-server/ .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# Stage 2: Runtime with all emulators
FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators

LABEL maintainer="localcloud"
LABEL description="LocalCloud - Local GCP Emulator Orchestrator"

# Install PostgreSQL, supervisor, JRE, curl
RUN apt-get update && apt-get install -y --no-install-recommends \
    postgresql-15 \
    supervisor \
    openjdk-21-jre-headless \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy third-party emulator binaries
COPY --from=fsouza/fake-gcs-server:latest /bin/fake-gcs-server /usr/local/bin/fake-gcs-server
COPY --from=ghcr.io/goccy/bigquery-emulator:latest /bin/bigquery-emulator /usr/local/bin/bigquery-emulator
COPY --from=gcr.io/cloud-spanner-emulator/emulator:latest /gateway_main /usr/local/bin/spanner-emulator

# Create localcloud user and directories
RUN groupadd -r localcloud && useradd -r -g localcloud localcloud \
    && mkdir -p /var/lib/localcloud/pgdata \
    && mkdir -p /var/lib/localcloud/gcs-data \
    && mkdir -p /var/log/localcloud \
    && mkdir -p /opt/localcloud \
    && chown -R localcloud:localcloud /var/lib/localcloud /var/log/localcloud /opt/localcloud

# Initialize PostgreSQL data directory
RUN su - localcloud -s /bin/bash -c "/usr/lib/postgresql/15/bin/initdb -D /var/lib/localcloud/pgdata"

# Copy our server JAR
COPY --from=build /app/build/libs/localcloud-server-*-all.jar /opt/localcloud/server.jar

# Copy supervisord config
COPY supervisord.conf /etc/supervisor/conf.d/localcloud.conf

# Copy entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# JVM options
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0 -XX:+UseZGC -XX:+ZGenerational -Xss256k"

# Default environment
ENV LOCALCLOUD_PROJECT=local-project
ENV LOCALCLOUD_ENABLE_GCS=true
ENV LOCALCLOUD_ENABLE_PUBSUB=true
ENV LOCALCLOUD_ENABLE_FIRESTORE=true
ENV LOCALCLOUD_ENABLE_BIGQUERY=true
ENV LOCALCLOUD_ENABLE_SPANNER=false
ENV LOCALCLOUD_ENABLE_BIGTABLE=false
ENV LOCALCLOUD_ENABLE_SECRETMANAGER=true
ENV LOCALCLOUD_ENABLE_CLOUDTASKS=true
ENV LOCALCLOUD_ENABLE_LOGGING=true
ENV LOCALCLOUD_ENABLE_MONITORING=true

VOLUME /var/lib/localcloud

# Ports: gateway, GCS, Pub/Sub, Firestore, Bigtable, Spanner gRPC/REST, BigQuery REST/gRPC
EXPOSE 8080 4443 8085 8086 8087 9010 9020 9050 9060

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/localcloud.conf"]
```

**Step 2: Create docker-entrypoint.sh**

```bash
#!/bin/bash
set -e

# Map LOCALCLOUD_SERVICES env var to individual enable flags
if [ -n "$LOCALCLOUD_SERVICES" ]; then
    # Default all to false
    export LOCALCLOUD_ENABLE_GCS=false
    export LOCALCLOUD_ENABLE_PUBSUB=false
    export LOCALCLOUD_ENABLE_FIRESTORE=false
    export LOCALCLOUD_ENABLE_BIGQUERY=false
    export LOCALCLOUD_ENABLE_SPANNER=false
    export LOCALCLOUD_ENABLE_BIGTABLE=false
    export LOCALCLOUD_ENABLE_SECRETMANAGER=false
    export LOCALCLOUD_ENABLE_CLOUDTASKS=false
    export LOCALCLOUD_ENABLE_LOGGING=false
    export LOCALCLOUD_ENABLE_MONITORING=false

    IFS=',' read -ra SERVICES <<< "$LOCALCLOUD_SERVICES"
    for service in "${SERVICES[@]}"; do
        service=$(echo "$service" | tr '[:lower:]' '[:upper:]' | xargs)
        export "LOCALCLOUD_ENABLE_${service}=true"
    done
fi

exec "$@"
```

**Step 3: Commit**

```bash
git add Dockerfile docker-entrypoint.sh
git commit -m "feat: rewrite Dockerfile for orchestrator model with supervisord"
```

---

### Task 3: Update docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

**Step 1: Update port mappings and environment**

Update to reflect new port layout matching the emulators. Key changes:
- Add ports for GCS (4443), BigQuery (9050, 9060), Bigtable (8087)
- Change Firestore from 9010 to 8086, Pub/Sub from 9020 to 8085
- Update env vars to use LOCALCLOUD_SERVICES instead of individual flags

**Step 2: Commit**

```bash
git add docker-compose.yml docker-compose.override.yml
git commit -m "feat: update docker-compose for orchestrator port layout"
```

---

## Phase 2: Java Server Refactor — Remove Delegated Emulators

### Task 4: Update build.gradle — remove unused dependencies

**Files:**
- Modify: `localcloud-server/build.gradle`

**Step 1: Remove proto dependencies for delegated services**

Remove these dependencies (services now handled by external emulators):
- `proto-google-cloud-pubsub-v1` and `grpc-google-cloud-pubsub-v1`
- `proto-google-cloud-firestore-v1` and `grpc-google-cloud-firestore-v1`
- `proto-google-cloud-storage-v2`
- `proto-google-cloud-spanner-*` (all 6 spanner deps)
- `proto-google-cloud-bigtable-*` (all 4 bigtable deps)
- H2 database dependency (`com.h2database:h2`)

Keep these (still needed for our facades):
- `proto-google-cloud-secretmanager-v1` and `grpc-*`
- `proto-google-cloud-tasks-v2` and `grpc-*`
- `proto-google-cloud-logging-v2` and `grpc-*`
- `proto-google-cloud-monitoring-v3` and `grpc-*`
- Armeria, gRPC, Jackson, SLF4J, HikariCP

Add new dependency:
- `org.postgresql:postgresql:42.7.3` (PostgreSQL JDBC driver)

**Step 2: Run build to verify compilation**

```bash
cd localcloud-server && ./gradlew compileJava
```

Expected: Compilation errors in files we're about to delete. That's fine.

**Step 3: Commit**

```bash
git add build.gradle
git commit -m "refactor: update dependencies for orchestrator model"
```

---

### Task 5: Delete delegated emulator code

**Files:**
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/gcs/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/pubsub/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/firestore/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/bigquery/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/spanner/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/bigtable/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/functions/` (entire directory)
- Delete: `localcloud-server/src/main/java/com/localcloud/events/EventBus.java`
- Delete: `localcloud-server/src/main/java/com/localcloud/persistence/H2DataSource.java`
- Delete: `localcloud-server/src/main/java/com/localcloud/persistence/BlobStore.java`

**Step 1: Delete files**

```bash
cd localcloud-server/src/main/java/com/localcloud
rm -rf emulators/gcs emulators/pubsub emulators/firestore emulators/bigquery emulators/spanner emulators/bigtable emulators/functions events/EventBus.java persistence/H2DataSource.java persistence/BlobStore.java
```

**Step 2: Commit**

```bash
git add -A
git commit -m "refactor: remove delegated emulator code (now handled by external emulators)"
```

---

### Task 6: Replace H2DataSource with PostgresDataSource

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/persistence/PostgresDataSource.java`

**Step 1: Write PostgresDataSource**

```java
package com.localcloud.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import com.localcloud.config.LocalCloudConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresDataSource {
    private static final Logger logger = LoggerFactory.getLogger(PostgresDataSource.class);
    private final HikariDataSource dataSource;

    public PostgresDataSource(LocalCloudConfig config) {
        HikariConfig hikari = new HikariConfig();
        String host = config.getPostgresHost();
        int port = config.getPostgresPort();
        String db = config.getPostgresDatabase();
        hikari.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        hikari.setUsername(config.getPostgresUser());
        hikari.setPassword(config.getPostgresPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(2);
        hikari.setPoolName("localcloud-pg");
        hikari.setConnectionTestQuery("SELECT 1");
        this.dataSource = new HikariDataSource(hikari);
        logger.info("PostgreSQL connection pool initialized: {}:{}/{}", host, port, db);
    }

    public DataSource getDataSource() { return dataSource; }
    public Connection getConnection() throws SQLException { return dataSource.getConnection(); }
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            logger.info("PostgreSQL data source closed");
        }
    }
}
```

**Step 2: Update LocalCloudConfig to add PostgreSQL settings**

Add these fields and env var mappings to `LocalCloudConfig.java`:
- `LOCALCLOUD_PG_HOST` → postgresHost (default: "localhost")
- `LOCALCLOUD_PG_PORT` → postgresPort (default: 5432)
- `LOCALCLOUD_PG_DATABASE` → postgresDatabase (default: "localcloud")
- `LOCALCLOUD_PG_USER` → postgresUser (default: "localcloud")
- `LOCALCLOUD_PG_PASSWORD` → postgresPassword (default: "localcloud")

**Step 3: Update SchemaManager to use PostgresDataSource**

Change constructor to accept `PostgresDataSource` instead of `H2DataSource`. Remove schema for delegated services (topics, subscriptions, spanner_*, bigtable_*). Keep schema for: secrets, secret_versions, task_queues, log_entries, time_series, metric_points. Adjust SQL syntax for PostgreSQL compatibility (e.g., `BLOB` → `BYTEA`, `CLOB` → `TEXT`).

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: replace H2 with PostgreSQL data source"
```

---

### Task 7: Update SecretManagerEmulator and CloudTasksEmulator to use PostgresDataSource

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerStore.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/TaskDispatcher.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java`

**Step 1: Change all `H2DataSource` references to `PostgresDataSource`**

In every file listed above, replace:
- `import com.localcloud.persistence.H2DataSource;` → `import com.localcloud.persistence.PostgresDataSource;`
- Constructor parameter `H2DataSource dataSource` → `PostgresDataSource dataSource`
- Any stored field type `H2DataSource` → `PostgresDataSource`

These are find-and-replace operations. The API (getConnection(), getDataSource()) is identical.

**Step 2: Run build**

```bash
cd localcloud-server && ./gradlew compileJava
```

**Step 3: Commit**

```bash
git add -A
git commit -m "refactor: migrate facade emulators from H2 to PostgreSQL"
```

---

## Phase 3: Rewrite LocalCloudApplication for Orchestrator Model

### Task 8: Create EmulatorProcessHealth checker

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java`

**Step 1: Write ProcessHealthChecker**

This class polls each external emulator's health endpoint and reports status. It maintains a map of service name → health status. Methods:
- `checkAll()` — polls all configured emulator endpoints
- `getStatus(String service)` — returns "healthy", "unhealthy", or "disabled"
- `getAllStatuses()` — returns full map

Health check endpoints per emulator:
- Pub/Sub: HTTP GET to `http://localhost:8085` (returns 200 if running)
- Firestore: HTTP GET to `http://localhost:8086` (returns 200 if running)
- Bigtable: TCP connect to `localhost:8087`
- Spanner: HTTP GET to `http://localhost:9020/v1/projects/test` (REST port)
- GCS (fake-gcs-server): HTTP GET to `http://localhost:4443/storage/v1/b`
- BigQuery: HTTP GET to `http://localhost:9050/discovery/v1/apis`
- PostgreSQL: JDBC connection test

**Step 2: Commit**

```bash
git add -A
git commit -m "feat: add ProcessHealthChecker for external emulator health polling"
```

---

### Task 9: Rewrite LocalCloudApplication.java

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

**Step 1: Simplify start() method**

Remove all emulator registration code for delegated services (GCS, Pub/Sub, Firestore, BigQuery, Spanner, Bigtable). Keep:
- Admin API registration at ``
- Dashboard static files
- Health check service (updated to use ProcessHealthChecker)
- Secret Manager gRPC registration (our facade)
- Cloud Tasks gRPC registration (our facade)
- Logging gRPC registration (our facade)
- Monitoring gRPC registration (our facade)

Remove references to:
- EventBus
- FunctionTriggerService, TriggerRegistry
- H2DataSource (replace with PostgresDataSource)
- All deleted emulator classes

The start() method should be roughly 100 lines instead of 323.

**Step 2: Update constructor**

Replace `H2DataSource` field with `PostgresDataSource`. Remove `eventBus`, `triggerRegistry`, `functionTriggerService` fields. Add `ProcessHealthChecker` field.

**Step 3: Run build**

```bash
cd localcloud-server && ./gradlew compileJava
```

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor: simplify LocalCloudApplication for orchestrator model"
```

---

### Task 10: Update HealthCheckService for aggregated health

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/gateway/HealthCheckService.java`

**Step 1: Integrate ProcessHealthChecker**

Update the `/health` endpoint to:
- Report our server's own facade services (Secret Manager, Cloud Tasks, Logging, Monitoring) from the gateway
- Report external emulator statuses from ProcessHealthChecker
- Return overall status as "healthy" only if all enabled services are healthy

Update the `/services` endpoint to include all emulators (both our facades and external) with correct ports and env var names.

**Step 2: Commit**

```bash
git add -A
git commit -m "feat: update health check to aggregate external emulator status"
```

---

### Task 11: Update AdminApiService — remove trigger management

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`

**Step 1: Remove trigger endpoints**

Remove the `/triggers` POST, GET, DELETE endpoints and `TriggerRegistry` dependency. These were for the function trigger system which is no longer needed (external emulators handle their own eventing).

Keep: `/env`, `/requests` endpoints.

**Step 2: Update `/env` endpoint**

Update the env var export to return correct ports for all external emulators:
```
STORAGE_EMULATOR_HOST=http://localhost:4443
PUBSUB_EMULATOR_HOST=localhost:8085
FIRESTORE_EMULATOR_HOST=localhost:8086
BIGTABLE_EMULATOR_HOST=localhost:8087
SPANNER_EMULATOR_HOST=localhost:9010
BIGQUERY_EMULATOR_HOST=http://localhost:9050
SECRET_MANAGER_EMULATOR_HOST=localhost:8080
CLOUD_TASKS_EMULATOR_HOST=localhost:8080
GCLOUD_PROJECT=<project-id>
```

**Step 3: Commit**

```bash
git add -A
git commit -m "refactor: simplify admin API, update env var export for emulator ports"
```

---

### Task 12: Update SeedService for external emulators

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`

**Step 1: Rewrite seed loading to call emulator APIs**

Instead of inserting directly into H2, seed loading now calls each emulator's native API:
- GCS seeds: HTTP PUT to `http://localhost:4443/storage/v1/b` to create buckets, then upload objects
- Pub/Sub seeds: HTTP PUT to `http://localhost:8085/v1/projects/{project}/topics/{topic}` to create topics/subscriptions
- Firestore seeds: gRPC calls to `localhost:8086` to create documents
- BigQuery seeds: HTTP POST to `http://localhost:9050/bigquery/v2/projects/{project}/datasets` for datasets/tables
- Secret Manager seeds: gRPC calls to our own facade (same process)

Use Java's `HttpClient` for REST calls and gRPC stubs for gRPC calls.

**Step 2: Commit**

```bash
git add -A
git commit -m "feat: rewrite seed loading to call external emulator APIs"
```

---

## Phase 4: CLI Updates

### Task 13: Update Python CLI for new port layout

**Files:**
- Modify: `localcloud-cli/src/localcloud/docker_manager.py`
- Modify: `localcloud-cli/src/localcloud/commands/env.py`
- Modify: `localcloud-cli/src/localcloud/commands/start.py`

**Step 1: Update port mappings in docker_manager.py**

Update the port mapping to match new emulator ports:
```python
DEFAULT_PORTS = {
    'gateway': 8080,
    'gcs': 4443,
    'pubsub': 8085,
    'firestore': 8086,
    'bigtable': 8087,
    'spanner_grpc': 9010,
    'spanner_rest': 9020,
    'bigquery_rest': 9050,
    'bigquery_grpc': 9060,
}
```

**Step 2: Update env command output**

Update `env.py` to output correct `*_EMULATOR_HOST` variables matching the new ports.

**Step 3: Update start command**

Update `start.py` to pass `LOCALCLOUD_SERVICES` env var to the container, which the entrypoint script maps to individual `LOCALCLOUD_ENABLE_*` flags.

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: update CLI for orchestrator port layout"
```

---

## Phase 5: Cleanup and Validation

### Task 14: Delete remaining unused files

**Files:**
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/EmulatorBase.java` (if unused after refactor)
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/AbstractEmulator.java` (evaluate if still needed by retained facades)
- Delete: `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java` (credential bypass handled by emulators themselves)
- Delete: `localcloud-server/src/main/java/com/localcloud/persistence/BlobStore.java` (already deleted in Task 5, verify)
- Clean up: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` — simplify to query external emulator APIs instead of H2

**Step 1: Evaluate each file**

Read each file, check if it's still referenced by retained code. Delete if unreferenced.

**Step 2: Commit**

```bash
git add -A
git commit -m "chore: remove unused files from pre-orchestrator architecture"
```

---

### Task 15: Update application.yaml configuration

**Files:**
- Modify: `localcloud-server/src/main/resources/application.yaml`

**Step 1: Add emulator endpoint configuration**

Add sections for external emulator hosts/ports so they're configurable:
```yaml
localcloud:
  project: ${LOCALCLOUD_PROJECT:local-project}
  postgres:
    host: ${LOCALCLOUD_PG_HOST:localhost}
    port: ${LOCALCLOUD_PG_PORT:5432}
    database: ${LOCALCLOUD_PG_DATABASE:localcloud}
  emulators:
    gcs:
      host: localhost
      port: 4443
    pubsub:
      host: localhost
      port: 8085
    firestore:
      host: localhost
      port: 8086
    bigtable:
      host: localhost
      port: 8087
    spanner:
      grpc-port: 9010
      rest-port: 9020
    bigquery:
      rest-port: 9050
      grpc-port: 9060
```

**Step 2: Commit**

```bash
git add -A
git commit -m "feat: add emulator endpoint configuration to application.yaml"
```

---

### Task 16: Full build and smoke test

**Files:**
- No new files

**Step 1: Build the Java server**

```bash
cd localcloud-server && ./gradlew clean shadowJar
```

Expected: Successful build producing `localcloud-server-0.1.0-SNAPSHOT-all.jar`

**Step 2: Build Docker image**

```bash
docker build -t localcloud/localcloud:dev .
```

Expected: Successful multi-stage build. Note: first build will be slow due to downloading emulator images.

**Step 3: Run container**

```bash
docker run --rm -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 -e LOCALCLOUD_SERVICES=gcs,pubsub,firestore localcloud/localcloud:dev
```

Expected: supervisord starts PostgreSQL, fake-gcs-server, pubsub-emulator, firestore-emulator, and our server. Health endpoint returns healthy.

**Step 4: Test health endpoint**

```bash
curl http://localhost:8080/health
```

Expected: JSON with status "healthy" and individual service statuses.

**Step 5: Test env endpoint**

```bash
curl http://localhost:8080/env
```

Expected: Shell-format env vars with correct emulator host values.

**Step 6: Commit any fixes**

```bash
git add -A
git commit -m "fix: smoke test fixes for orchestrator build"
```

---

### Task 17: Update seed.yaml and test seed loading

**Files:**
- Modify: `seed.yaml` (if needed)

**Step 1: Test seed loading against external emulators**

```bash
curl -X POST http://localhost:8080/seed -H "Content-Type: application/yaml" -d @seed.yaml
```

Expected: Seed data created in GCS (bucket + object), Pub/Sub (topic + subscription), and Secret Manager (secret + version).

**Step 2: Verify seeded data**

```bash
# GCS bucket
curl http://localhost:4443/storage/v1/b?project=local-project

# Pub/Sub topic
curl http://localhost:8085/v1/projects/local-project/topics

# Secret Manager (via gRPC or admin browse)
curl http://localhost:8080/browse/secretmanager/secrets
```

**Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: seed loading fixes for external emulators"
```

---

## Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| 1 | 1-3 | Container infrastructure (supervisord, Dockerfile, docker-compose) |
| 2 | 4-7 | Remove delegated emulator code, switch to PostgreSQL |
| 3 | 8-12 | Rewrite Java server for orchestrator model |
| 4 | 13 | Update Python CLI |
| 5 | 14-17 | Cleanup, configuration, build, smoke test |

**Total: 17 tasks** (down from original 97)

**Files deleted:** ~18 Java files (delegated emulator implementations)
**Files created:** ~4 new files (supervisord.conf, docker-entrypoint.sh, PostgresDataSource.java, ProcessHealthChecker.java)
**Files modified:** ~12 existing files
