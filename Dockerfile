# Stage 1: Build Java server
# Option A: Build inside Docker (requires network access for Gradle)
# FROM eclipse-temurin:21-jdk-jammy AS build
# WORKDIR /app
# COPY localcloud-server/ .
# RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# Option B: Use pre-built JAR (run `cd localcloud-server && ./gradlew shadowJar` first)
# The pre-built JAR is copied directly in the runtime stage below.

# Pull bigquery-emulator binary (amd64-only image)
FROM --platform=linux/amd64 ghcr.io/goccy/bigquery-emulator:latest AS bigquery-emulator

# Stage 2: Runtime
FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators

LABEL maintainer="localcloud"
LABEL description="LocalCloud - Local GCP Emulator Orchestrator"

# Install runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-15 \
        supervisor \
        openjdk-21-jre-headless \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Copy third-party emulator binaries
COPY --from=fsouza/fake-gcs-server:latest /bin/fake-gcs-server /usr/local/bin/fake-gcs-server
# bigquery-emulator is amd64-only; copied from named stage above
COPY --from=bigquery-emulator /bin/bigquery-emulator /usr/local/bin/bigquery-emulator
# Spanner emulator requires both gateway_main and emulator_main
COPY --from=gcr.io/cloud-spanner-emulator/emulator:latest /gateway_main /usr/local/bin/spanner-gateway
COPY --from=gcr.io/cloud-spanner-emulator/emulator:latest /emulator_main /usr/local/bin/spanner-emulator-main

# Create localcloud user, group, and directories
RUN groupadd -r localcloud && useradd -r -g localcloud -m localcloud \
    && mkdir -p /var/lib/localcloud/pgdata \
                /var/lib/localcloud/gcs-data \
                /var/log/localcloud \
                /opt/localcloud \
                /var/run/postgresql \
    && chown -R localcloud:localcloud /var/lib/localcloud \
                                      /var/log/localcloud \
                                      /opt/localcloud \
                                      /var/run/postgresql

# Initialize PostgreSQL data directory
RUN su - localcloud -s /bin/bash -c "/usr/lib/postgresql/15/bin/initdb -D /var/lib/localcloud/pgdata"

# Create the localcloud database
RUN su - localcloud -s /bin/bash -c " \
    /usr/lib/postgresql/15/bin/pg_ctl -D /var/lib/localcloud/pgdata start && \
    /usr/lib/postgresql/15/bin/createdb -h /var/run/postgresql localcloud && \
    /usr/lib/postgresql/15/bin/pg_ctl -D /var/lib/localcloud/pgdata stop"

# Copy pre-built server JAR (run `cd localcloud-server && ./gradlew shadowJar` before docker build)
COPY localcloud-server/build/libs/localcloud-server-*-all.jar /opt/localcloud/server.jar

# Copy supervisor and entrypoint configuration
COPY supervisord.conf /etc/supervisor/conf.d/localcloud.conf
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# JVM tuning for container environment with ZGC
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xss256k \
  -XX:MaxMetaspaceSize=128m \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# Default project and service enable flags
ENV LOCALCLOUD_PROJECT="local-project" \
    LOCALCLOUD_ENABLE_GCS="true" \
    LOCALCLOUD_ENABLE_PUBSUB="true" \
    LOCALCLOUD_ENABLE_FIRESTORE="true" \
    LOCALCLOUD_ENABLE_BIGQUERY="true" \
    LOCALCLOUD_ENABLE_SPANNER="false" \
    LOCALCLOUD_ENABLE_BIGTABLE="false" \
    LOCALCLOUD_ENABLE_SECRETMANAGER="true" \
    LOCALCLOUD_ENABLE_CLOUDTASKS="true" \
    LOCALCLOUD_ENABLE_LOGGING="true" \
    LOCALCLOUD_ENABLE_MONITORING="true"

# Data persistence volume
VOLUME /var/lib/localcloud

# Ports: gateway, GCS, Pub/Sub, Firestore, BigQuery, Spanner, Bigtable, SecretManager, CloudTasks
EXPOSE 8080 4443 8085 8086 8087 9010 9020 9050 9060

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/_localcloud/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/localcloud.conf"]
