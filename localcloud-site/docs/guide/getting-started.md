# Getting Started

LocalCloud is a single Docker image that provides a unified, high-fidelity GCP development environment. It orchestrates official Google emulators and enhanced third-party alternatives to give you a local experience that feels like the real cloud.

## Prerequisites

- **Docker:** Desktop, Engine, or Rancher Desktop.
- **Memory:** At least 4GB allocated to Docker.
- **Port Availability:** Ensure ports like `8080`, `4443`, and `9010` are not in use.

## Installation

The easiest way to run LocalCloud is via Docker Compose.

1. **Create a `docker-compose.yml`:**

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports:
      - "8080:8080"  # Gateway & Console
      - "4443:4443"  # Cloud Storage
      - "8085:8085"  # Pub/Sub
      - "9010:9010"  # Spanner
    volumes:
      - localcloud-data:/var/lib/localcloud

volumes:
  localcloud-data:
```

2. **Start the services:**

```bash
docker compose up -d
```

3. **Verify Health:**

```bash
curl http://localhost:8080/_localcloud/health
```

## Exploring the Console

Once running, open [http://localhost:8080](http://localhost:8080) in your browser. From here, you can:

- **Browse GCS Buckets:** Upload and preview files.
- **Run SQL:** Query Spanner and BigQuery tables using the built-in editor.
- **Inspect Logs:** View unified logs from all running emulators.
- **Manage Seeds:** Import and export project state.

## Next Steps

- [Configure Persistence](/guide/persistence)
- [Add Seed Data](/guide/seed-data)
- [Connect your SDKs](/guide/service-reference)
