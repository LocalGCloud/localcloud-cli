# LocalCloud

Google Cloud Platform — in a box.

LocalCloud emulates 15 GCP services inside a single Docker container so you can develop and test locally without cloud access, credentials, or costs. Your application code works against LocalCloud with **zero code changes** — just point your GCP SDKs at localhost.

## Emulated Services

| Service | Protocol | Port |
|---------|----------|------|
| Cloud Storage | HTTP | 4443 |
| Pub/Sub | gRPC | 8085 |
| Firestore | gRPC | 8086 |
| BigQuery | REST / gRPC | 9050 / 9060 |
| Secret Manager | gRPC | 8080 |
| Cloud Tasks | gRPC | 8080 |
| Spanner | gRPC / REST | 9010 / 9020 |
| Bigtable | gRPC | 8087 |
| Cloud Logging | gRPC | 8080 |
| Cloud Monitoring | gRPC | 8080 |
| GKE | gRPC | 8080 |
| Compute Engine | REST | 8080 |
| Cloud Run | gRPC | 8080 |
| Memorystore (Redis) | RESP2 | 6379 |
| Cloud Workflows | REST | 8080 |

## Quick Start

```bash
# Create the persistent volume (one-time)
docker volume create localcloud-data

# Pull and start
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

Sample data is baked into the image and auto-loads on startup. Open the console at **http://localhost:8080** to explore.

Set environment variables to route GCP SDKs to LocalCloud:

```bash
# Auto-configure from running container
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"

# Or set manually
export STORAGE_EMULATOR_HOST=http://localhost:4443
export PUBSUB_EMULATOR_HOST=localhost:8085
export FIRESTORE_EMULATOR_HOST=localhost:8086
export GOOGLE_CLOUD_PROJECT=local-project
```

Use GCP client libraries as normal — no code changes needed:

```python
from google.cloud import storage

client = storage.Client()
bucket = client.create_bucket("my-bucket")
blob = bucket.blob("hello.txt")
blob.upload_from_string("Hello, LocalCloud!")
```

## Building from Source

### Prerequisites

- Java 21+ (JDK, for building the server JAR)
- Docker
- Node.js 18+ (for the web console)

### Build the Server

```bash
cd localcloud-server
./gradlew build
```

This produces a fat JAR at `localcloud-server/build/libs/localcloud-server-*-all.jar`.

### Build the Docker Image

The server JAR and console must be built first — the Dockerfile copies pre-built artifacts.

```bash
# 1. Build the server JAR
cd localcloud-server && ./gradlew shadowJar && cd ..

# 2. Build the web console
cd localcloud-console && npm install && npm run build && cd ..

# 3. Build the Docker image using Compose
docker compose build

# Or build directly with Docker
docker build -t localcloud/localcloud:latest .
```

**Architecture notes:**
- Native arm64 (Apple Silicon) and amd64 (Intel/AMD) builds are supported
- The Docker image uses a custom Java 25 JRE (built via jlink, ~72 MB) and debian:trixie-slim base
- Emulators (Firestore, Pub/Sub, Bigtable) run as direct JAR/binary execution — no gcloud SDK at runtime

**Running the built image:**

```bash
docker volume create localcloud-data

docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Verify it's running
curl http://localhost:8080/_localcloud/health | jq

# View logs
docker logs -f localcloud
```

### Run Tests

```bash
# Java server tests (187 unit tests)
cd localcloud-server && ./gradlew test
```

### Full Build (all components)

```bash
# Build everything from the repo root
cd localcloud-server && ./gradlew shadowJar && cd ..
cd localcloud-console && npm install && npm run build && cd ..
docker build -t localcloud/localcloud:latest .
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LOCALCLOUD_PROJECT` | `local-project` | GCP project ID |
| `LOCALCLOUD_SERVICES` | _(all enabled)_ | Comma-separated list of services to enable (overrides individual flags) |
| `LOCALCLOUD_SEED_FILE` | `/etc/localcloud/seed.yaml` | Path to seed data file inside container |
| `JAVA_OPTS` | `-Xmx512m -Xms128m` | JVM flags for the gateway server |

Individual service flags (set to `true` or `false`):

| Flag | Default |
|------|---------|
| `LOCALCLOUD_ENABLE_GCS` | `true` |
| `LOCALCLOUD_ENABLE_PUBSUB` | `true` |
| `LOCALCLOUD_ENABLE_FIRESTORE` | `true` |
| `LOCALCLOUD_ENABLE_BIGQUERY` | `true` |
| `LOCALCLOUD_ENABLE_SPANNER` | `true` |
| `LOCALCLOUD_ENABLE_BIGTABLE` | `true` |
| `LOCALCLOUD_ENABLE_SECRETMANAGER` | `true` |
| `LOCALCLOUD_ENABLE_CLOUDTASKS` | `true` |
| `LOCALCLOUD_ENABLE_LOGGING` | `true` |
| `LOCALCLOUD_ENABLE_MONITORING` | `true` |
| `LOCALCLOUD_ENABLE_MEMORYSTORE` | `true` |
| `LOCALCLOUD_ENABLE_GKE` | `false` |
| `LOCALCLOUD_ENABLE_COMPUTE` | `false` |
| `LOCALCLOUD_ENABLE_CLOUDRUN` | `false` |

### Examples

```bash
# Run only storage and messaging services
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 6379:6379 \
  -m 4g \
  -e LOCALCLOUD_SERVICES="gcs,pubsub,memorystore" \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Custom project ID
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -e LOCALCLOUD_PROJECT="my-app-dev" \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

### Optional Volume Mounts

```bash
# Custom seed data (overrides the built-in seed)
-v ./my-seed.yaml:/etc/localcloud/seed.yaml:ro

# GKE emulation (requires Docker-in-Docker)
-v /var/run/docker.sock:/var/run/docker.sock

# GCP credential bridging (for hybrid local+cloud routing)
-v ~/.config/gcloud:/credentials/adc:ro -e LOCALCLOUD_GCP_CREDENTIAL_SOURCE=adc
```

## Seed Data

Default seed data is baked into the image and auto-loads on startup — no setup needed. It populates all services with sample data (users, buckets, topics, datasets, secrets, etc.).

To use your own seed data, mount a custom file:

```bash
docker run -d --name localcloud \
  ... \
  -v ./my-seed.yaml:/etc/localcloud/seed.yaml:ro \
  localcloud/localcloud:latest
```

To load seed data into a running container:

```bash
curl -X POST http://localhost:8080/_localcloud/seed \
  -H "Content-Type: application/x-yaml" --data-binary @seed.yaml
```

See `seed.yaml` for the format and a full example.

## Admin API

The gateway exposes admin endpoints at `/_localcloud/` on port 8080:

```bash
# Health check
curl http://localhost:8080/_localcloud/health | jq

# List services
curl http://localhost:8080/_localcloud/services | jq

# Reset all data
curl -X POST http://localhost:8080/_localcloud/reset
```

## Docker Compose (for contributors)

A `docker-compose.yml` is included for building from source:

```bash
docker compose build
docker compose up -d
docker compose logs -f
docker compose down
```

## Project Structure

```
localcloud-server/    Java API gateway + emulators (Armeria, gRPC, PostgreSQL)
localcloud-console/   Web console (Solid.js, served by gateway)
specs/                Feature specifications
```

## Telemetry

LocalCloud collects anonymous usage statistics to help improve the project. No personally identifiable information is collected — only aggregate counters like which services are enabled, request counts, and error rates.

**Opt out:**

```bash
docker run -e LOCALCLOUD_TELEMETRY=false ...
```

## Documentation

See the [Developer Guide](DEVELOPER_GUIDE.md) for complete documentation including:

- All service details and limitations
- SDK code examples (Python, Java)
- Environment variable reference
- Docker Compose integration patterns
- IAM modes (permissive, strict, gcp-live)
- Seed file format
- Troubleshooting

## License

Apache-2.0
