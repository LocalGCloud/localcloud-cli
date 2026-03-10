# Quickstart: LocalCloud - GCP Local Emulator

## Prerequisites

- Docker installed and running
- Python 3.9+ (for CLI tool)
- pip

## Installation

```bash
# Install the CLI tool
pip install localcloud
```

## Start the Emulator

```bash
# Start all services
localcloud start

# Or start specific services only
localcloud start --services gcs,pubsub,firestore

# Start with seed data
localcloud start --seed ./seed.yaml
```

## Configure Your Shell

```bash
# Set all environment variables at once
eval $(localcloud env)

# Verify
echo $STORAGE_EMULATOR_HOST
# Output: http://localhost:8080
```

## Use Google Cloud SDKs (No Code Changes)

### Python

```python
from google.cloud import storage

# This automatically uses STORAGE_EMULATOR_HOST if set
client = storage.Client(project="local-project")

# Create a bucket
bucket = client.create_bucket("my-test-bucket")

# Upload a file
blob = bucket.blob("hello.txt")
blob.upload_from_string("Hello, LocalCloud!")

# Download it back
print(blob.download_as_text())
# Output: Hello, LocalCloud!
```

### Java

```java
import com.google.cloud.storage.*;

// Set STORAGE_EMULATOR_HOST=http://localhost:8080 in your environment
Storage storage = StorageOptions.newBuilder()
    .setProjectId("local-project")
    .build()
    .getService();

// Create a bucket
storage.create(BucketInfo.of("my-test-bucket"));

// Upload a file
BlobId blobId = BlobId.of("my-test-bucket", "hello.txt");
storage.create(BlobInfo.newBuilder(blobId).build(), "Hello!".getBytes());
```

## Check Status

```bash
localcloud status
```

Output:
```
Service            Status    Port   Requests
─────────────────────────────────────────────
Cloud Storage      running   8080   12
Pub/Sub            running   9020   0
Firestore          running   9010   5
BigQuery           running   8080   3
Secret Manager     running   8080   0
Cloud Tasks        running   8080   0
Spanner            running   9030   0
Bigtable           running   9040   0
Cloud Logging      running   8080   2
Cloud Monitoring   running   8080   0
```

## View Dashboard

Open http://localhost:8080/_localcloud/dashboard/ in your browser to:
- Monitor service health
- Browse stored data (buckets, documents, topics)
- View API request logs

## Use with Docker Compose

Add to your `docker-compose.yml`:

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports:
      - "8080:8080"
      - "9010:9010"
      - "9020:9020"
      - "9030:9030"
      - "9040:9040"
    volumes:
      - localcloud-data:/var/lib/localcloud
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/_localcloud/health"]
      interval: 10s
      retries: 5

  my-app:
    build: .
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:8080"
      PUBSUB_EMULATOR_HOST: "localcloud:9020"
      FIRESTORE_EMULATOR_HOST: "localcloud:9010"
      SPANNER_EMULATOR_HOST: "localcloud:9030"
      BIGTABLE_EMULATOR_HOST: "localcloud:9040"
      GOOGLE_CLOUD_PROJECT: "local-project"
    depends_on:
      localcloud:
        condition: service_healthy

volumes:
  localcloud-data:
```

## Seed Data

Create a `seed.yaml` file:

```yaml
version: "1.0"
project: "local-project"

gcs:
  buckets:
    - name: "my-app-uploads"
      objects:
        - key: "config/defaults.json"
          content: '{"feature_flag": true}'
          contentType: "application/json"

pubsub:
  topics:
    - name: "user-events"
      subscriptions:
        - name: "user-events-processor"
          ackDeadlineSeconds: 30

secretmanager:
  secrets:
    - name: "database-url"
      versions:
        - data: "postgresql://localhost:5432/myapp"
          state: "ENABLED"
```

Load it:
```bash
localcloud seed seed.yaml
```

## Reset to Clean State

```bash
# Reset to seed state (or empty if no seed)
localcloud reset

# Reset and reload a specific seed file
localcloud reset --seed ./seed.yaml
```

## Stop

```bash
localcloud stop
```

## Moving to Production

When deploying to GCP, simply remove the `*_EMULATOR_HOST` environment variables. Your application code requires zero changes - only configuration differs between local and production.
