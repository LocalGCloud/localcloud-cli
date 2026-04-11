## ADDED Requirements

### Requirement: Per-service health status in Data Browser
The Data Browser SHALL show a health indicator (green/red dot) next to each service tab, reflecting whether that specific emulator is reachable.

#### Scenario: Healthy service shows green indicator
- **WHEN** developer views the Data Browser and the GCS emulator is running
- **THEN** the GCS tab shows a green dot indicator

#### Scenario: Unhealthy service shows red indicator
- **WHEN** the Spanner emulator has crashed
- **THEN** the Spanner tab shows a red dot with a message "Service unavailable"

### Requirement: Per-service reset
The console SHALL allow resetting individual services without affecting others. Each service in the Data Browser SHALL have a "Reset" button that clears only that service's data and optionally re-seeds it.

#### Scenario: Reset only BigQuery
- **WHEN** developer clicks "Reset" on BigQuery and confirms
- **THEN** BigQuery datasets and tables are cleared, but GCS buckets and Spanner data remain untouched

### Requirement: Per-emulator health checks
The health endpoint SHALL return individual health status for each emulator process, not just the gateway. External emulators (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery) SHALL be checked via their respective health check mechanisms defined in services.yaml.

#### Scenario: Detailed health response
- **WHEN** developer calls `GET /_localcloud/health`
- **THEN** the response includes per-service health: `{"services": {"gcs": "healthy", "spanner": "healthy", "bigquery": "unhealthy"}}`

### Requirement: Export current state as seed YAML
The console SHALL provide an "Export" button that generates a seed YAML file from the current state of all services, suitable for sharing with teammates or checking into version control.

#### Scenario: Export after making changes
- **WHEN** developer clicks "Export State" in Settings
- **THEN** a YAML file downloads containing current GCS buckets/objects, Pub/Sub topics, BigQuery datasets/tables/rows, secrets, Spanner instances/databases/data, and Memorystore keys
