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
#   Health:  curl http://localhost:8080/health
#
#   Caddy reverse proxy (enabled by default, add -p 80:80 for cloud.localhost):
#     docker run -d --name localcloud ... -p 80:80 ...
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
#   5432  — Cloud SQL PostgreSQL data plane (optional)
#   3306  — Cloud SQL MySQL-compatible data plane (optional, requires OpenHalo image path)
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
#   Individual service flags (all default to true except GKE, Compute, Cloud Run,
#   Vertex AI, Cloud KMS, and Cloud SQL):
#     LOCALCLOUD_ENABLE_GCS, LOCALCLOUD_ENABLE_PUBSUB,
#     LOCALCLOUD_ENABLE_FIRESTORE, LOCALCLOUD_ENABLE_BIGQUERY,
#     LOCALCLOUD_ENABLE_SPANNER, LOCALCLOUD_ENABLE_BIGTABLE,
#     LOCALCLOUD_ENABLE_SECRETMANAGER, LOCALCLOUD_ENABLE_CLOUDTASKS,
#     LOCALCLOUD_ENABLE_LOGGING, LOCALCLOUD_ENABLE_MONITORING,
#     LOCALCLOUD_ENABLE_MEMORYSTORE,
#     LOCALCLOUD_ENABLE_GKE (default: false),
#     LOCALCLOUD_ENABLE_COMPUTE (default: false),
#     LOCALCLOUD_ENABLE_CLOUDRUN (default: false),
#     LOCALCLOUD_ENABLE_VERTEXAI (default: false),
#     LOCALCLOUD_ENABLE_KMS (default: false),
#     LOCALCLOUD_ENABLE_CLOUDSQL (default: false)
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
#       logs/            Service logs (on container filesystem, not bind mount)
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
#     curl -X POST http://localhost:8080/seed \
#       -H "Content-Type: application/x-yaml" --data-binary @seed.yaml
#
# TERRAFORM
# ---------
#   Point Terraform at LocalCloud (zero .tf changes needed):
#     eval $(curl -s 'http://localhost:8080/env?format=terraform')
#     terraform init && terraform plan && terraform apply
#
# BUILD (for contributors)
# ------------------------
#   cd localcloud-server && ./gradlew shadowJar
#   cd localcloud-console && npm run build
#   docker build -t localcloud/localcloud:latest .
#
# =============================================================================


# Image repositories for dependencies
ARG SPANNER_EMULATOR_IMAGE=jaysen2apache/spanner-emulator-extended:latest
ARG BIGQUERY_EMULATOR_IMAGE=jaysen2apache/bigquery-emulator-on-duckdb:latest
ARG GO_BASE_IMAGE=public.ecr.aws/docker/library/golang:1.25-alpine
ARG GCLOUD_SDK_IMAGE=gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators
ARG GCS_EMULATOR_IMAGE=fsouza/fake-gcs-server:1.54.0
ARG DOCKER_CLI_IMAGE=docker:27.1-cli
ARG JDK_IMAGE=eclipse-temurin:25-jdk

# --- Valkey (Redis-compatible) ---
FROM valkey/valkey:8.1-bookworm AS valkey-build

# --- Named build stages for COPY --from references ---
FROM ${SPANNER_EMULATOR_IMAGE} AS spanner-emulator
FROM ${BIGQUERY_EMULATOR_IMAGE} AS bq-emulator
FROM ${GCS_EMULATOR_IMAGE} AS gcs-emulator
FROM ${DOCKER_CLI_IMAGE} AS docker-cli
FROM ${GCLOUD_SDK_IMAGE} AS gcloud-sdk

# --- Build little_bigtable from published Go module ---
FROM ${GO_BASE_IMAGE} AS bigtable-build
WORKDIR /src
RUN sed -i 's/https:/http:/' /etc/apk/repositories \
    && apk add --no-cache gcc=15.2.0-r2 musl-dev=1.2.5-r23 git=2.52.0-r0
ARG LITTLE_BIGTABLE_VERSION=v0.0.1
ENV GOPRIVATE=github.com/jhsenjaliya/* \
    GIT_SSL_NO_VERIFY=1 \
    GONOSUMCHECK=* \
    GONOSUMDB=* \
    GOINSECURE=*
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod init bigtable-build && \
    GOPROXY=direct go get github.com/jhsenjaliya/little_bigtable@${LITTLE_BIGTABLE_VERSION}
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 go build -trimpath -ldflags="-s -w -linkmode external -extldflags -static" \
    -o /out/localcloud-bigtable-emulator github.com/jhsenjaliya/little_bigtable

# --- Build custom JRE with jlink ---
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

# --- Cleanup BigQuery Emulator (Reduce image size by ~100MB) ---
FROM bq-emulator AS bq-cleaned
RUN find /opt/bqenv -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null \
    && find /opt/bqenv -name "*.pyc" -delete 2>/dev/null || true

# --- Artifact Collector (Consolidates 20+ layers into 1, scoped to /out) ---
FROM debian:trixie-slim AS artifact-collector
RUN mkdir -p /out/opt/java /out/usr/local/bin /out/opt/emulators /out/usr/local/lib /out/opt/bqenv

# Java
COPY --from=jlink-build /opt/java-custom /out/opt/java
# Docker
COPY --from=docker-cli /usr/local/bin/docker /out/usr/local/bin/docker
# Emulators
COPY --from=gcs-emulator /bin/fake-gcs-server /out/usr/local/bin/fake-gcs-server
COPY --from=gcloud-sdk /google-cloud-sdk/platform/cloud-firestore-emulator/cloud-firestore-emulator.jar /out/opt/emulators/cloud-firestore-emulator.jar
COPY --from=gcloud-sdk /google-cloud-sdk/platform/pubsub-emulator/lib/ /out/opt/emulators/pubsub-lib/
COPY --chmod=755 --from=bigtable-build /out/localcloud-bigtable-emulator /out/usr/local/bin/localcloud-bigtable-emulator
# BigQuery
COPY --from=bq-cleaned /opt/bqenv /out/opt/bqenv
# Spanner
COPY --from=spanner-emulator /gateway_main /out/usr/local/bin/spanner-gateway
COPY --from=spanner-emulator /emulator_main /out/usr/local/bin/spanner-emulator-main
# Valkey
COPY --from=valkey-build /usr/local/bin/valkey-server /usr/local/bin/valkey-cli /out/usr/local/bin/

# --- Runtime stage ---
FROM debian:trixie-slim

LABEL maintainer="Jay Sen <jaysen@apache.org>" \
      description="LocalCloud - Local GCP Emulator Orchestrator" \
      org.opencontainers.image.authors="Jay Sen <jaysen@apache.org>" \
      org.opencontainers.image.title="LocalCloud" \
      org.opencontainers.image.description="Local GCP Emulator Orchestrator" \
      org.opencontainers.image.licenses="Apache-2.0"

# Install runtime dependencies with BuildKit caching
RUN rm -f /etc/apt/apt.conf.d/docker-clean; \
    echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' > /etc/apt/apt.conf.d/keep-cache
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        postgresql-17=17.10-0+deb13u1 \
        python3 \
        curl=8.14.1-2+deb13u3 \
        ca-certificates=20250419 \
        openssl=3.5.6-1~deb13u1 \
        gosu=1.17-3+b4 \
        caddy=2.6.2-12+b3 \
    && apt-get remove -y --purge postgresql-17-jit 2>/dev/null || true \
    && apt-get autoremove -y --purge \
    && rm -rf /usr/lib/postgresql/17/lib/bitcode \
              /usr/lib/postgresql/17/lib/llvmjit*.so \
              /usr/lib/postgresql/17/lib/llvmjit_types.bc \
              /usr/share/doc /usr/share/man /usr/share/locale \
              /usr/lib/python3.*/idlelib \
              /usr/lib/python3.*/test \
              /usr/lib/python3.*/tkinter \
              /usr/lib/python3.*/ensurepip \
              /usr/lib/python3.*/pydoc_data \
              /usr/lib/python3.*/lib2to3

# 1. Bring in all artifacts in ONE layer (SAFE: only from /out)
COPY --from=artifact-collector /out/ /

# 2. Environment and PATH setup
ENV JAVA_HOME=/opt/java \
    PATH="/opt/bqenv/bin:/opt/java/bin:${PATH}"

# 3. Install k3d
ARG INCLUDE_K3D=true
ARG K3D_VERSION=v5.8.3
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN if [ "$INCLUDE_K3D" = "true" ]; then \
      ARCH=$(uname -m | sed "s/aarch64/arm64/" | sed "s/x86_64/amd64/") && \
      curl -fsSLk -o /usr/local/bin/k3d "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${ARCH}" && \
      chmod +x /usr/local/bin/k3d; \
    fi
SHELL ["/bin/sh", "-c"]

# 4. System setup (Users, Dirs, Links)
RUN groupadd -g 1001 localcloud && useradd -g localcloud -m -u 1001 localcloud \
    && mkdir -p /var/lib/localcloud/pgdata \
                /var/lib/localcloud/gcs-data \
                /var/lib/localcloud/spanner-data \
                /var/lib/localcloud/redis-data \
                /var/lib/localcloud/bigquery-data \
                /var/log/localcloud \
                /opt/localcloud \
                /var/run/postgresql \
                /credentials/adc \
    && chown -R localcloud:localcloud /var/lib/localcloud \
                                      /var/log/localcloud \
                                      /opt/localcloud \
                                      /var/run/postgresql \
                                      /credentials \
    && ln -sf /usr/bin/python3 /opt/bqenv/bin/python3 \
    && ln -sf /usr/bin/python3 /opt/bqenv/bin/python \
    && ln -sf /opt/bqenv/bin/bigquery-emulator /usr/local/bin/bigquery-emulator \
    && ln -sf /opt/bqenv/bin/supervisord /usr/bin/supervisord \
    && ln -sf /opt/bqenv/bin/supervisord /usr/local/bin/supervisord \
    && ln -sf /opt/bqenv/bin/supervisorctl /usr/bin/supervisorctl \
    && ln -sf /opt/bqenv/bin/supervisorctl /usr/local/bin/supervisorctl \
    && printf "#!/bin/bash\nexec /usr/local/bin/spanner-emulator-main --data_dir=/var/lib/localcloud/spanner-data \"\$@\"\n" > /usr/local/bin/spanner-emulator-wrapper \
    && chmod +x /usr/local/bin/spanner-emulator-wrapper

# 5. Initialize PostgreSQL
RUN su - localcloud -s /bin/bash -c " \
    /usr/lib/postgresql/17/bin/initdb -D /var/lib/localcloud/pgdata && \
    /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata start && \
    /usr/lib/postgresql/17/bin/createdb -h /var/run/postgresql localcloud && \
    /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata stop"

# 6. Copy local configuration and artifacts
COPY valkey.conf /etc/valkey.conf
COPY --chown=localcloud:localcloud services.yaml seed.yaml /etc/localcloud/
COPY --chown=localcloud:localcloud localcloud-server/build/libs/localcloud-server-*-all.jar /opt/localcloud/server.jar
COPY --chown=localcloud:localcloud localcloud-console/dist/ /opt/localcloud/console/dist/
COPY supervisord.conf /etc/supervisor/conf.d/localcloud.conf
COPY Caddyfile /etc/caddy/Caddyfile
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY wait-for-pg.sh /usr/local/bin/wait-for-pg.sh
COPY license-gate.sh /opt/localcloud/license-gate.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /usr/local/bin/wait-for-pg.sh /opt/localcloud/license-gate.sh

# Build mode: "development" (default) skips license gate when no key is set.
# Pass --build-arg BUILD_MODE=production to enforce key requirement at startup.
ARG BUILD_MODE=development
RUN echo "${BUILD_MODE}" > /opt/localcloud/BUILD_MODE

# License enforcement: set to "false" (default) to skip all license checks.
# Pass --build-arg ENFORCE_LICENSE=true to require a valid LOCALCLOUD_API_KEY.
ARG ENFORCE_LICENSE=false
RUN echo "${ENFORCE_LICENSE}" > /opt/localcloud/ENFORCE_LICENSE

# 7. Metadata and versioning (ARGs late for cache optimization)
ARG VERSION_VAL=0.0.0
ARG BUILD_HASH=unknown
ARG BUILD_DATE=unknown
ARG IMAGE_DIGEST=local
RUN echo "${VERSION_VAL}+${BUILD_HASH}.${BUILD_DATE}" > /opt/localcloud/VERSION && \
    echo "${VERSION_VAL} (${BUILD_DATE})" > /opt/localcloud/VERSION_DISPLAY && \
    echo "${IMAGE_DIGEST}" > /opt/localcloud/DIGEST
ENV LOCALCLOUD_VERSION_FILE=/opt/localcloud/VERSION

# 8. Runtime Environment
ENV JAVA_OPTS="-Xmx512m -Xms128m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    LOCALCLOUD_PROJECT="local-project" \
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
    LOCALCLOUD_ENABLE_WORKFLOWS="true" \
    LOCALCLOUD_ENABLE_VERTEXAI="false" \
    LOCALCLOUD_ENABLE_KMS="false" \
    LOCALCLOUD_ENABLE_CLOUDSQL="false" \
    LOCALCLOUD_ENABLE_LOCAL_PROXY="true" \
    LOCALCLOUD_TELEMETRY="true"

VOLUME /var/lib/localcloud

# Ports: gateway (+ console), Cloud SQL, GCS, Memorystore, GKE/k3d, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery
EXPOSE 8080 3306 4443 5432 6379 6443 8085 8086 8087 9010 9020 9050 9060

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -sf http://localhost:8080/health || exit 1
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/opt/bqenv/bin/supervisord", "-c", "/etc/supervisor/conf.d/localcloud.conf"]
