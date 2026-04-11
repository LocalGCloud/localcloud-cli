## 1. Bigtable — Replace cbtemulator with little_bigtable

- [ ] 1.1 Add `little_bigtable` binary to Dockerfile — download pre-built release from `github.com/bitly/little_bigtable` for linux/amd64 and linux/arm64, copy to `/usr/local/bin/little_bigtable`
- [ ] 1.2 Create `/var/lib/localcloud/bigtable-data/` directory in Dockerfile and docker-entrypoint.sh
- [ ] 1.3 Update `supervisord.conf` — replace `gcloud beta emulators bigtable start --host-port=0.0.0.0:8087` with `little_bigtable --host 0.0.0.0 --port 8087 --db-path /var/lib/localcloud/bigtable-data/bigtable.db`
- [ ] 1.4 Update `BrowseService.browseBigtable()` — replace PostgreSQL queries with gRPC proxy calls to `little_bigtable` (ListTables via Admin API, ReadRows via Data API)
- [ ] 1.5 Update `MutateService.mutateBigtable()` — replace PostgreSQL writes with gRPC proxy calls (MutateRow, DeleteRow via Data API)
- [ ] 1.6 Update `SeedService.seedBigtable()` — replace PostgreSQL inserts with gRPC calls (CreateTable, MutateRows)
- [ ] 1.7 Remove `bigtable_data` table from `SchemaManager.java` (no longer needed)
- [ ] 1.8 Test: create table, write rows, restart container, verify data persists via `ReadRows`

## 2. Pub/Sub — PostgreSQL Config Sync

- [ ] 2.1 Add `pubsub_topics` and `pubsub_subscriptions` tables to `SchemaManager.java`
- [ ] 2.2 Create `PubSubPersistenceSync.java` — background thread that runs every 30 seconds: calls Pub/Sub emulator REST API to list topics/subscriptions, upserts into PostgreSQL tables, removes rows for deleted resources
- [ ] 2.3 Add startup restore logic — on gateway startup (after Pub/Sub emulator health check), read topics/subs from PostgreSQL, re-create them in the emulator via REST PUT calls
- [ ] 2.4 Wire `PubSubPersistenceSync` into `LocalCloudApplication.java` — start the sync thread after gateway initialization
- [ ] 2.5 Test: create topics and subscriptions, restart container, verify they exist without re-seeding

## 3. Firestore — Export/Import Persistence

- [ ] 3.1 Create `/var/lib/localcloud/firestore-data/` directory in Dockerfile and docker-entrypoint.sh
- [ ] 3.2 Investigate `--seed_from_export` flag — test if the gcloud Firestore emulator JAR accepts this flag and what export format it expects. Document findings.
- [ ] 3.3 Create `FirestorePersistenceService.java` — background thread that periodically exports all Firestore documents via REST API (`GET /v1/projects/{project}/databases/(default)/documents/{collection}` for each collection) and saves to JSON files at `/var/lib/localcloud/firestore-data/`
- [ ] 3.4 Add startup restore logic — on gateway startup, if Firestore export JSON files exist, call `seedFirestore()` to re-import all documents via REST PATCH. This reuses the existing seed mechanism.
- [ ] 3.5 If `--seed_from_export` works: update `supervisord.conf` to pass the flag, modify export to produce the expected binary format instead of JSON
- [ ] 3.6 Wire `FirestorePersistenceService` into `LocalCloudApplication.java`
- [ ] 3.7 Test: create collections and documents, restart container, verify documents persist

## 4. Integration and Build

- [ ] 4.1 Update `services.yaml` — add `persistence: true` annotation for Firestore, Pub/Sub, Bigtable
- [ ] 4.2 Update `docker-entrypoint.sh` — ensure all new data directories are created on startup for existing volumes
- [ ] 4.3 Build and test full Docker image — `./gradlew shadowJar && docker compose build && docker compose up`
- [ ] 4.4 Run full demo suite — `python3 run_demo.py --keep-data` for all services
- [ ] 4.5 Test persistence end-to-end — seed data, create additional data via demos, restart container, verify all 11 database services retain their data
- [ ] 4.6 Test idempotent re-seed after restart — verify auto-seed runs cleanly without duplicates or errors
