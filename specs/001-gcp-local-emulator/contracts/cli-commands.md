# Contract: CLI Commands

**Tool Name**: `localcloud`
**Installation**: `pip install localcloud`
**Language**: Python (Click framework)

## Commands

### `localcloud start`

Start the LocalCloud emulator container.

```
localcloud start [OPTIONS]

Options:
  -s, --services TEXT       Comma-separated services to start (default: all)
                            Valid: gcs,pubsub,firestore,bigquery,secretmanager,
                                   cloudtasks,spanner,bigtable,logging,monitoring
  --seed FILE               Path to seed YAML file to load on startup
  -d, --detach              Run in background (default: true)
  -p, --port INTEGER        Gateway port (default: 8080)
  --project TEXT             GCP project ID (default: local-project)
  --data-dir PATH           Host directory for persistent data (default: ./localcloud-data)
  --image TEXT               Docker image (default: localcloud/localcloud:latest)
  --name TEXT                Container name (default: localcloud-main)
```

**Output**:
```
LocalCloud starting...
  Cloud Storage      ✓  http://localhost:8080  (STORAGE_EMULATOR_HOST)
  Pub/Sub            ✓  localhost:9020         (PUBSUB_EMULATOR_HOST)
  Firestore          ✓  localhost:9010         (FIRESTORE_EMULATOR_HOST)
  BigQuery           ✓  http://localhost:8080  (BIGQUERY_EMULATOR_HOST)
  Secret Manager     ✓  localhost:8080         (SECRET_MANAGER_EMULATOR_HOST)
  Cloud Tasks        ✓  localhost:8080         (CLOUD_TASKS_EMULATOR_HOST)
  Spanner            ✓  localhost:9030         (SPANNER_EMULATOR_HOST)
  Bigtable           ✓  localhost:9040         (BIGTABLE_EMULATOR_HOST)
  Cloud Logging      ✓  localhost:8080         (LOGGING_EMULATOR_HOST)
  Cloud Monitoring   ✓  localhost:8080         (MONITORING_EMULATOR_HOST)

Dashboard: http://localhost:8080/_localcloud/dashboard/

Run 'eval $(localcloud env)' to configure your shell.
```

### `localcloud stop`

Stop the running container.

```
localcloud stop [OPTIONS]

Options:
  --name TEXT    Container name (default: localcloud-main)
  --rm           Remove container after stopping (data preserved in volume)
```

### `localcloud status`

Show status of all emulated services.

```
localcloud status [OPTIONS]

Options:
  --name TEXT     Container name (default: localcloud-main)
  --format TEXT   Output format: table, json (default: table)
```

### `localcloud env`

Output environment variables for shell configuration.

```
localcloud env [OPTIONS]

Options:
  --format TEXT   Output format: shell, docker-compose, json (default: shell)
  --name TEXT     Container name (default: localcloud-main)
```

**Usage**: `eval $(localcloud env)`

### `localcloud seed`

Load seed data from a YAML file into the running emulator.

```
localcloud seed SEED_FILE [OPTIONS]

Arguments:
  SEED_FILE       Path to seed YAML file

Options:
  --name TEXT     Container name (default: localcloud-main)
  --clear-first   Clear existing data before seeding
```

### `localcloud reset`

Reset all services to seed state or clear entirely.

```
localcloud reset [OPTIONS]

Options:
  --seed FILE     Seed file to restore to (uses last loaded seed if not specified)
  --name TEXT     Container name (default: localcloud-main)
  --yes           Skip confirmation prompt
```

### `localcloud logs`

View emulator container logs.

```
localcloud logs [OPTIONS]

Options:
  --follow, -f    Follow log output
  --tail INTEGER  Number of lines from end (default: 100)
  --name TEXT     Container name (default: localcloud-main)
```

## Global Options

```
Options available on all commands:
  --project TEXT    GCP project ID (env: LOCALCLOUD_PROJECT, default: local-project)
  --name TEXT       Container name (env: LOCALCLOUD_CONTAINER_NAME, default: localcloud-main)
  --help            Show help message
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Container not found / not running |
| 3 | Docker not available |
| 4 | Port conflict |
| 5 | Seed file parse error |
