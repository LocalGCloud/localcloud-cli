# LocalCloud

Google Cloud Platform — in a box.

LocalCloud emulates 14 GCP services inside a single Docker container so you can develop and test locally without cloud access, credentials, or costs. Your application code works against LocalCloud with **zero code changes** — just point your GCP SDKs at localhost.

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

## Quick Start

```bash
# Start all services (ports are pre-configured in docker-compose.yml)
docker compose up -d

# Or use docker run (service ports defined in services.yaml)
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

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

- Java 21 (JDK)
- Docker
- Node.js 18+ (for the web console, optional)
- Python 3.9+ (for the CLI, optional)

### Build the Server

```bash
cd localcloud-server
./gradlew build
```

This produces a fat JAR at `localcloud-server/build/libs/localcloud-server-*-all.jar`.

### Build the Docker Image

The server JAR must be built first — the Dockerfile copies the pre-built JAR.

```bash
# 1. Build the server JAR (required before docker build)
cd localcloud-server && ./gradlew shadowJar && cd ..

# 2. Build the Docker image using Compose
docker compose build

# Or build directly with Docker
docker build -t localcloud/localcloud:latest .
```

**Architecture notes:**
- Native arm64 (Apple Silicon) and amd64 (Intel/AMD) builds are supported
- First build downloads ~3GB of base images; subsequent builds use cache

**Running the built image:**

```bash
# Start with docker-compose (recommended)
docker compose up -d

# Or start directly with docker
docker run -d --name localcloud-main \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Verify it's running
curl http://localhost:8080/_localcloud/health | jq

# View logs
docker logs -f localcloud-main
```

### Build the Web Console (optional)

```bash
cd localcloud-console
npm install
npm run build
```

### Install the CLI (optional)

```bash
cd localcloud-cli
pip install -e ".[test,console]"
localcloud --help
```

### Run Tests

```bash
# Java server tests (187 unit tests)
cd localcloud-server && ./gradlew test

# Python CLI tests (66 unit tests)
cd localcloud-cli && pip install -e ".[test]" && pytest

# Python CLI linting
cd localcloud-cli && ruff check .
```

### Full Build (all components)

```bash
# Build everything from the repo root
cd localcloud-server && ./gradlew shadowJar && cd ..
cd localcloud-console && npm install && npm run build && cd ..
cd localcloud-cli && pip install -e ".[test,console]" && cd ..
docker compose build
docker compose up -d
```

## Docker Compose

A `docker-compose.yml` is included for development:

```bash
# Start LocalCloud
docker compose up -d

# View logs
docker compose logs -f

# Stop
docker compose down
```

## Seed Data

Pre-populate services with data on startup by mounting a seed file:

```bash
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9050:9050 -p 9060:9060 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  -v ./seed.yaml:/etc/localcloud/seed.yaml:ro \
  localcloud/localcloud:latest
```

See `seed.yaml` for an example.

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

## Project Structure

```
localcloud-server/    Java API gateway + emulators (Armeria, gRPC, PostgreSQL)
localcloud-cli/       Python CLI tool (Click)
localcloud-console/   Web console (Solid.js, served by gateway)
specs/                Feature specifications
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
