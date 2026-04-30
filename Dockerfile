# =============================================================================
# LocalCloud — Local GCP Emulator Orchestrator
# =============================================================================
#
# QUICK START
# -----------
#   mkdir -p ~/.localcloud/data
#   docker run -d --name localcloud \
#     -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
#     -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 9060:9060 \
#     -p 6379:6379 \
#     -m 4g \
#     -v ~/.localcloud/data:/var/lib/localcloud \
#     localcloud/localcloud:latest
#
#   Console: http://localhost:8080
#   Health:  curl http://localhost:8080/_localcloud/health
#
# PORTS
# -----
#   8080  — Gateway (admin API + web console)
#   4443  — Cloud Storage (REST)
#   8085  — Pub/Sub (gRPC)
#   8086  — Firestore (gRPC)
#   8087  — Bigtable (gRPC)
#   9010  — Spanner (gRPC)
#   9020  — Spanner (REST)
#   9050  — BigQuery (REST)
#   9060  — BigQuery (gRPC)
#   6379  — Memorystore / Redis (RESP2)
#   6443  — GKE / k3d Kubernetes API (optional, requires docker.sock)
#
# ENVIRONMENT VARIABLES
# ---------------------
#   LOCALCLOUD_PROJECT        Project ID (default: "local-project")
#   LOCALCLOUD_SERVICES       Comma-separated list of services to enable.
#                             Overrides all individual LOCALCLOUD_ENABLE_* flags.
#                             Example: "gcs,pubsub,bigquery,secretmanager"
#   LOCALCLOUD_SEED_FILE      Path to seed YAML inside the container
#                             (default: /etc/localcloud/seed.yaml, baked into image)
#   JAVA_OPTS                 JVM flags (default: -Xmx512m -Xms128m -XX:+UseZGC)
#
#   Individual service flags (all default to true except GKE, Compute, Cloud Run):
#     LOCALCLOUD_ENABLE_GCS, LOCALCLOUD_ENABLE_PUBSUB,
#     LOCALCLOUD_ENABLE_FIRESTORE, LOCALCLOUD_ENABLE_BIGQUERY,
#     LOCALCLOUD_ENABLE_SPANNER, LOCALCLOUD_ENABLE_BIGTABLE,
#     LOCALCLOUD_ENABLE_SECRETMANAGER, LOCALCLOUD_ENABLE_CLOUDTASKS,
#     LOCALCLOUD_ENABLE_LOGGING, LOCALCLOUD_ENABLE_MONITORING,
#     LOCALCLOUD_ENABLE_MEMORYSTORE,
#     LOCALCLOUD_ENABLE_GKE (default: false),
#     LOCALCLOUD_ENABLE_COMPUTE (default: false),
#     LOCALCLOUD_ENABLE_CLOUDRUN (default: false)
#
# DATA PERSISTENCE
# ----------------
#   Option A — Docker named volume (default, not accessible from host on macOS):
#     -v localcloud-data:/var/lib/localcloud
#
#   Option B — Bind mount (data accessible on host filesystem):
#     mkdir -p ~/.localcloud/data
#     -v ~/.localcloud/data:/var/lib/localcloud
#
#     The data directory will contain:
#       pgdata/          PostgreSQL database files
#       gcs-data/        Cloud Storage blobs
#       spanner-data/    Spanner persistence
#       bigquery-data/   BigQuery DuckDB files
#       logs/            All service logs (supervisord, emulators, gateway)
#
#     First run auto-creates subdirectories and sets ownership.
#     Use bind mount when you need to inspect, backup, or share data.
#
# OPTIONAL MOUNTS
# ---------------
#   Custom seed data:
#     -v /path/to/my-seed.yaml:/etc/localcloud/seed.yaml:ro
#
#   GKE emulation (requires Docker-in-Docker):
#     -v /var/run/docker.sock:/var/run/docker.sock
#
#   Custom CA certificates (corporate proxy / VPN):
#     -v /path/to/certs:/etc/localcloud/certs:ro
#     Auto-imports .pem/.crt/.cer into Java truststore + system CA bundle.
#
#   GCP credential bridging (for hybrid local+cloud routing):
#     -v ~/.config/gcloud:/credentials/adc:ro
#     -e LOCALCLOUD_GCP_CREDENTIAL_SOURCE=adc
#
# EXAMPLES
# --------
#   # Run only storage and messaging services:
#   docker run -d --name localcloud \
#     -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 6379:6379 \
#     -m 4g \
#     -e LOCALCLOUD_SERVICES="gcs,pubsub,memorystore" \
#     -v ~/.localcloud/data:/var/lib/localcloud \
#     localcloud/localcloud:latest
#
#   # Using Docker named volume (data not accessible from host on macOS):
#   docker run -d --name localcloud \
#     -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
#     -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 9060:9060 \
#     -m 4g \
#     -v localcloud-data:/var/lib/localcloud \
#     localcloud/localcloud:latest
#
# SEED DATA
# ---------
#   A default seed.yaml is baked into the image at /etc/localcloud/seed.yaml.
#   It auto-loads on container startup once all services are healthy.
#   Mount your own file to override, or set LOCALCLOUD_SEED_FILE to a different path.
#   To load seed data manually into a running container:
#     curl -X POST http://localhost:8080/_localcloud/seed \
#       -H "Content-Type: application/x-yaml" --data-binary @seed.yaml
#
# TERRAFORM
# ---------
#   Point Terraform at LocalCloud (zero .tf changes needed):
#     eval $(curl -s 'http://localhost:8080/_localcloud/env?format=terraform')
#     terraform init && terraform plan && terraform apply
#
# BUILD (for contributors)
# ------------------------
#   cd localcloud-server && ./gradlew shadowJar
#   cd localcloud-console && npm run build
#   docker build -t localcloud/localcloud:latest .
#
# =============================================================================

# Image repositories for dependencies (can be overridden for air-gapped or internal repos)
ARG SPANNER_EMULATOR_IMAGE=jaysen2apache/spanner-emulator-extended@sha256:58702f59729905d3db97225480ad3f9c8496a59d697bcb750ab856450c65889a
ARG BIGQUERY_EMULATOR_IMAGE=jaysen2apache/bigquery-emulator-on-duckdb@sha256:70254a43605dd6dd6125bf3b85ad31fdd25fe73047d5617f0936e0cdf92f2d57
ARG GO_BASE_IMAGE=public.ecr.aws/docker/library/golang:1.25-alpine
ARG GCLOUD_SDK_IMAGE=gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators
ARG GCS_EMULATOR_IMAGE=fsouza/fake-gcs-server:1.54.0
ARG DOCKER_CLI_IMAGE=docker:27.1-cli
ARG JDK_IMAGE=eclipse-temurin:25-jdk

# --- Named build stages for COPY --from references ---
FROM ${SPANNER_EMULATOR_IMAGE} AS spanner-emulator
FROM ${BIGQUERY_EMULATOR_IMAGE} AS bq-emulator
FROM ${GCS_EMULATOR_IMAGE} AS gcs-emulator
FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${GCLOUD_SDK_IMAGE} AS gcloud-sdk

# --- Build little_bigtable from published Go module ---
# Package: github.com/jhsenjaliya/little_bigtable/bttest@v0.0.1
FROM ${GO_BASE_IMAGE} AS bigtable-build
WORKDIR /src
RUN sed -i 's/https:/http:/' /etc/apk/repositories \
    && apk add --no-cache gcc musl-dev git
ARG LITTLE_BIGTABLE_VERSION=v0.0.1
ENV GOPRIVATE=github.com/jhsenjaliya/*
ENV GIT_SSL_NO_VERIFY=1
# GONOSUMCHECK + GOINSECURE scoped to private repo; transitive deps use GOPROXY=direct with TLS
ENV GONOSUMCHECK=github.com/jhsenjaliya/*
ENV GOINSECURE=github.com/jhsenjaliya/*
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod init bigtable-build && \
    GONOSUMDB=* GOPROXY=direct go get github.com/jhsenjaliya/little_bigtable@${LITTLE_BIGTABLE_VERSION}
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 go build -trimpath -ldflags="-s -w -linkmode external -extldflags -static" \
    -o /out/localcloud-bigtable-emulator github.com/jhsenjaliya/little_bigtable

# --- Build custom JRE with jlink (Java 25 LTS, ~72 MB instead of ~194 MB full JRE) ---
FROM ${JDK_IMAGE} AS jlink-build
RUN jlink \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,\
java.naming,java.net.http,java.security.jgss,java.security.sasl,\
java.sql,jdk.management,jdk.unsupported,jdk.crypto.ec,\
java.xml,java.logging,java.management,java.prefs,java.datatransfer,\
java.scripting,java.rmi,jdk.httpserver,jdk.localedata,jdk.zipfs \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /opt/java-custom

# --- Runtime stage (slim Debian instead of full gcloud SDK) ---
# Trixie (Debian 13) required: spanner emulator needs GLIBCXX_3.4.32,
# BigQuery's Python 3.12 needs GLIBC_2.38 — both unavailable in Bookworm (glibc 2.36)
FROM debian:trixie-slim

LABEL maintainer="Jay Sen <jaysen@apache.org>"
LABEL description="LocalCloud - Local GCP Emulator Orchestrator"
LABEL org.opencontainers.image.authors="Jay Sen <jaysen@apache.org>"
LABEL org.opencontainers.image.title="LocalCloud"
LABEL org.opencontainers.image.description="Local GCP Emulator Orchestrator"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Install runtime dependencies (single layer, no gcloud SDK)
# PostgreSQL 17: install full package then strip JIT + LLVM + Z3 (~144 MB savings).
# We only need: postgres server, initdb, createdb, pg_isready, pg_ctl.
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-17 \
        supervisor \
        curl \
        ca-certificates \
        openssl \
        gosu \
    && apt-get remove -y --purge postgresql-17-jit 2>/dev/null || true \
    && apt-get autoremove -y --purge \
    && rm -rf /usr/lib/postgresql/17/lib/bitcode \
              /usr/lib/postgresql/17/lib/llvmjit*.so \
              /usr/lib/postgresql/17/lib/llvmjit_types.bc \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* \
        /usr/share/doc/* /usr/share/man/* /usr/share/locale/* \
        /usr/share/postgresql/17/man/*

# Custom JRE (jlink-built, ~72 MB instead of ~194 MB full JRE)
COPY --from=jlink-build /opt/java-custom /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"

# Docker CLI only (not the full engine — daemon runs on host via mounted docker.sock)
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

# Install k3d (lightweight k3s wrapper for GKE emulation)
# Set --build-arg INCLUDE_K3D=false for slim images without GKE support
ARG INCLUDE_K3D=true
ARG K3D_VERSION=v5.8.3
RUN if [ "$INCLUDE_K3D" = "true" ]; then \
      ARCH=$(uname -m | sed 's/aarch64/arm64/' | sed 's/x86_64/amd64/') && \
      curl -fsSLk -o /usr/local/bin/k3d "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${ARCH}" && \
      chmod +x /usr/local/bin/k3d; \
    fi

# Copy third-party emulator binaries
COPY --from=gcs-emulator /bin/fake-gcs-server /usr/local/bin/fake-gcs-server

# Extract emulator JARs/binaries from gcloud SDK image (no gcloud CLI needed at runtime)
# Firestore: Java JAR, launched via java -cp (no Main-Class manifest)
# Pub/Sub: Java fat JAR, launched via java -jar
# Bigtable: built from github.com/jhsenjaliya/little_bigtable (PostgreSQL-backed)
COPY --from=gcloud-sdk /google-cloud-sdk/platform/cloud-firestore-emulator/cloud-firestore-emulator.jar /opt/emulators/cloud-firestore-emulator.jar
COPY --from=gcloud-sdk /google-cloud-sdk/platform/pubsub-emulator/lib/ /opt/emulators/pubsub-lib/
COPY --chmod=755 --from=bigtable-build /out/localcloud-bigtable-emulator /usr/local/bin/localcloud-bigtable-emulator

# BigQuery Emulator v2: copy pre-built venv + Python 3.12 interpreter from the BQ emulator image
COPY --from=bq-emulator /usr/local/bin/python3.12 /usr/local/bin/python3.12
COPY --from=bq-emulator /usr/local/lib/libpython3.12.so.1.0 /usr/local/lib/libpython3.12.so.1.0
COPY --from=bq-emulator /usr/local/lib/python3.12/ /usr/local/lib/python3.12/
COPY --from=bq-emulator /opt/bqenv /opt/bqenv
# [D] Strip Python 3.12 stdlib modules not needed at runtime
# Wire up: libpython shared lib, venv symlinks, and CLI entrypoint
RUN rm -rf \
        /usr/local/lib/python3.12/test \
        /usr/local/lib/python3.12/idlelib \
        /usr/local/lib/python3.12/tkinter \
        /usr/local/lib/python3.12/turtledemo \
        /usr/local/lib/python3.12/ensurepip \
        /usr/local/lib/python3.12/lib2to3 \
        /usr/local/lib/python3.12/distutils \
        /usr/local/lib/python3.12/pydoc_data \
        /usr/local/lib/python3.12/unittest/test \
    && ln -sf /usr/local/lib/libpython3.12.so.1.0 /usr/local/lib/libpython3.12.so \
    && ln -sf /usr/local/lib/libpython3.12.so.1.0 /usr/local/lib/libpython3.so \
    && ldconfig \
    && ln -sf /usr/local/bin/python3.12 /opt/bqenv/bin/python3 \
    && ln -sf /usr/local/bin/python3.12 /opt/bqenv/bin/python \
    && ln -sf /opt/bqenv/bin/bigquery-emulator /usr/local/bin/bigquery-emulator \
    && find /opt/bqenv -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null; \
    find /opt/bqenv -name '*.pyc' -delete 2>/dev/null; \
    true

# Spanner emulator (extended fork with persistence + gateway)
COPY --from=spanner-emulator /gateway_main /usr/local/bin/spanner-gateway
COPY --from=spanner-emulator /emulator_main /usr/local/bin/spanner-emulator-main

# Create localcloud user, group, and directories
RUN groupadd -r localcloud && useradd -r -g localcloud -m localcloud \
    && mkdir -p /var/lib/localcloud/pgdata \
                /var/lib/localcloud/gcs-data \
                /var/lib/localcloud/spanner-data \
                /var/lib/localcloud/bigquery-data \
                /var/log/localcloud \
                /opt/localcloud \
                /var/run/postgresql \
                /credentials/adc \
    && chown -R localcloud:localcloud /var/lib/localcloud \
                                      /var/log/localcloud \
                                      /opt/localcloud \
                                      /var/run/postgresql \
                                      /credentials

# Spanner emulator wrapper: always passes --data_dir for persistence
RUN printf '#!/bin/bash\nif [ ! -x /usr/local/bin/spanner-emulator-main ]; then\n  echo "ERROR: spanner-emulator-main not found or not executable" >&2\n  exit 1\nfi\nexec /usr/local/bin/spanner-emulator-main --data_dir=/var/lib/localcloud/spanner-data "$@"\n' \
    > /usr/local/bin/spanner-emulator-wrapper \
    && chmod +x /usr/local/bin/spanner-emulator-wrapper

# [B] Initialize PostgreSQL data directory + create database in single layer
# (avoids 34MB duplicate WAL data from separate initdb + createdb layers)
RUN su - localcloud -s /bin/bash -c " \
    /usr/lib/postgresql/17/bin/initdb -D /var/lib/localcloud/pgdata && \
    /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata start && \
    /usr/lib/postgresql/17/bin/createdb -h /var/run/postgresql localcloud && \
    /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata stop"

# Copy pre-built server JAR (run `cd localcloud-server && ./gradlew shadowJar` before docker build)
COPY localcloud-server/build/libs/localcloud-server-*-all.jar /opt/localcloud/server.jar

# Copy console (pre-built frontend, served by Armeria gateway)
# Run `cd localcloud-console && npm run build` before docker build
COPY localcloud-console/dist/ /opt/localcloud/console/dist/
# Copy service registry and configuration
COPY services.yaml /etc/localcloud/services.yaml
COPY seed.yaml /etc/localcloud/seed.yaml
COPY supervisord.conf /etc/supervisor/conf.d/localcloud.conf
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY wait-for-pg.sh /usr/local/bin/wait-for-pg.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /usr/local/bin/wait-for-pg.sh

# JVM tuning for container environment (Java 25 LTS)
# ZGenerational is default in Java 25 LTS, no need to specify it
# Override via: docker run -e JAVA_OPTS="-Xmx2g -Xms512m" ...
ENV JAVA_OPTS="-Xmx512m -Xms128m -XX:+UseZGC -Xss256k -XX:MaxMetaspaceSize=96m -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Default project and service enable flags
ENV LOCALCLOUD_PROJECT="local-project" \
    LOCALCLOUD_ENABLE_GCS="true" \
    LOCALCLOUD_ENABLE_PUBSUB="true" \
    LOCALCLOUD_ENABLE_FIRESTORE="true" \
    LOCALCLOUD_ENABLE_BIGQUERY="true" \
    LOCALCLOUD_ENABLE_SPANNER="true" \
    LOCALCLOUD_ENABLE_BIGTABLE="true" \
    LOCALCLOUD_ENABLE_SECRETMANAGER="true" \
    LOCALCLOUD_ENABLE_CLOUDTASKS="true" \
    LOCALCLOUD_ENABLE_LOGGING="true" \
    LOCALCLOUD_ENABLE_MONITORING="true" \
    LOCALCLOUD_ENABLE_GKE="false" \
    LOCALCLOUD_ENABLE_COMPUTE="false" \
    LOCALCLOUD_ENABLE_CLOUDRUN="false" \
    LOCALCLOUD_ENABLE_MEMORYSTORE="true" \
    LOCALCLOUD_ENABLE_WORKFLOWS="true"

# Telemetry: sends anonymous usage stats (API key set at runtime in entrypoint)
ENV LOCALCLOUD_TELEMETRY="true"

# Data persistence volume
VOLUME /var/lib/localcloud

# Ports: gateway (+ console), GCS, Memorystore, GKE/k3d, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery
EXPOSE 8080 4443 6379 6443 8085 8086 8087 9010 9020 9050 9060

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/_localcloud/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/localcloud.conf"]
