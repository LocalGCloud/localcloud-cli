# Stage 1: Build Java server
# Option A: Build inside Docker (requires network access for Gradle)
# FROM eclipse-temurin:21-jdk-jammy AS build
# WORKDIR /app
# COPY localcloud-server/ .
# RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# Option B: Use pre-built JAR (run `cd localcloud-server && ./gradlew shadowJar` first)
# The pre-built JAR is copied directly in the runtime stage below.

# Spanner emulator source: google (official) or local (fork with persistence)
# Set via: docker compose build --build-arg SPANNER_EMULATOR_IMAGE=google
# Must be declared before the first FROM for use in FROM line interpolation
ARG SPANNER_EMULATOR_IMAGE=local

# Pull bigquery-emulator binary (amd64-only)
FROM --platform=linux/amd64 ghcr.io/goccy/bigquery-emulator:latest AS bigquery-emulator-amd64

FROM gcr.io/cloud-spanner-emulator/emulator:latest AS spanner-emulator-upstream
# Fork stage: only pulled by BuildKit when SPANNER_EMULATOR_IMAGE=local
FROM spanner-emulator-build:latest AS spanner-emulator-fork

# Normalize emulator binary path for each mode
FROM spanner-emulator-upstream AS spanner-bin-google
FROM spanner-emulator-fork AS spanner-bin-local
RUN cp /build/output/emulator_main /emulator_main
# Select the right emulator binary source based on build arg
FROM spanner-bin-${SPANNER_EMULATOR_IMAGE} AS spanner-bin

# Stage 2: Runtime
FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators

# Re-declare ARG after FROM so it's available in this stage
ARG SPANNER_EMULATOR_IMAGE=local

LABEL maintainer="Jay Sen <jaysen@apache.org>"
LABEL description="LocalCloud - Local GCP Emulator Orchestrator"
LABEL org.opencontainers.image.authors="Jay Sen <jaysen@apache.org>"
LABEL org.opencontainers.image.title="LocalCloud"
LABEL org.opencontainers.image.description="Local GCP Emulator Orchestrator"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Install runtime dependencies
# Note: Java 21 is already included in the base image (google-cloud-cli:emulators)
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-15 \
        supervisor \
        curl \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Docker CLI only (not the full engine — daemon runs on host via mounted docker.sock)
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker

# Install k3d (lightweight k3s wrapper for GKE emulation)
# Set --build-arg INCLUDE_K3D=false for slim images without GKE support
ARG INCLUDE_K3D=true
RUN if [ "$INCLUDE_K3D" = "true" ]; then \
      curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash; \
    fi

# Copy third-party emulator binaries
COPY --from=fsouza/fake-gcs-server:latest /bin/fake-gcs-server /usr/local/bin/fake-gcs-server
# bigquery-emulator is amd64-only
# On arm64 this copies the amd64 binary which requires QEMU; BigQuery may not work on arm64
COPY --from=bigquery-emulator-amd64 /bin/bigquery-emulator /usr/local/bin/bigquery-emulator
# Spanner emulator: gateway always from upstream, emulator from selected source (google or local fork)
COPY --from=spanner-emulator-upstream /gateway_main /usr/local/bin/spanner-gateway
COPY --from=spanner-bin /emulator_main /usr/local/bin/spanner-emulator-main

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

# Spanner emulator wrapper: passes --data_dir only for local fork (persistence support)
RUN printf '#!/bin/bash\nif [ ! -x /usr/local/bin/spanner-emulator-main ]; then\n  echo "ERROR: spanner-emulator-main not found or not executable" >&2\n  exit 1\nfi\nif [ "${SPANNER_EMULATOR_IMAGE}" = "google" ]; then\n  exec /usr/local/bin/spanner-emulator-main "$@"\nelse\n  exec /usr/local/bin/spanner-emulator-main --data_dir=/var/lib/localcloud/spanner-data "$@"\nfi\n' \
    > /usr/local/bin/spanner-emulator-wrapper \
    && chmod +x /usr/local/bin/spanner-emulator-wrapper

# Initialize PostgreSQL data directory
RUN su - localcloud -s /bin/bash -c "/usr/lib/postgresql/15/bin/initdb -D /var/lib/localcloud/pgdata"

# Create the localcloud database
RUN su - localcloud -s /bin/bash -c " \
    /usr/lib/postgresql/15/bin/pg_ctl -D /var/lib/localcloud/pgdata start && \
    /usr/lib/postgresql/15/bin/createdb -h /var/run/postgresql localcloud && \
    /usr/lib/postgresql/15/bin/pg_ctl -D /var/lib/localcloud/pgdata stop"

# Copy pre-built server JAR (run `cd localcloud-server && ./gradlew shadowJar` before docker build)
COPY localcloud-server/build/libs/localcloud-server-*-all.jar /opt/localcloud/server.jar

# Copy console (pre-built frontend, served by Armeria gateway)
# Run `cd localcloud-console && npm run build` before docker build
COPY localcloud-console/dist/ /opt/localcloud/console/dist/
# Copy service registry and configuration
COPY services.yaml /etc/localcloud/services.yaml
COPY supervisord.conf /etc/supervisor/conf.d/localcloud.conf
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# JVM tuning for container environment
# Fixed heap sizes to coexist with PostgreSQL + emulator processes within the container.
# Override via: docker run -e JAVA_OPTS="-Xmx2g -Xms512m" ...
ENV JAVA_OPTS="\
  -Xmx512m \
  -Xms128m \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xss256k \
  -XX:MaxMetaspaceSize=96m \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# Spanner emulator mode (baked in from build arg, used by wrapper script and entrypoint)
ENV SPANNER_EMULATOR_IMAGE=${SPANNER_EMULATOR_IMAGE}

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
    LOCALCLOUD_ENABLE_MEMORYSTORE="true"

# Data persistence volume
VOLUME /var/lib/localcloud

# Ports: gateway (+ console), GCS, Memorystore, GKE/k3d, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery
EXPOSE 8080 4443 6379 6443 8085 8086 8087 9010 9020 9050 9060

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/_localcloud/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/localcloud.conf"]
