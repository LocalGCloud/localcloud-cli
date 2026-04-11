## Why

The current seed data is minimal (3 rows per table) and doesn't cover all database services — Firestore and Bigtable have no seed data at all. Spanner persistence (via the forked emulator with LevelDB) needs validation that data survives Docker restarts. Additionally, the Data Browser is read-only — developers can view data but cannot create, update, or delete records through the console, forcing them to use SDK code or CLI tools for every data change during development.

## What Changes

- **Expanded seed data**: Add realistic mockup data for all database services — Spanner (multiple tables with interleaved data), Firestore (collections with nested documents), BigQuery (larger datasets), Bigtable (row families), and Memorystore (more data types). Increase row counts to 10-20 per table for meaningful browsing.
- **Spanner persistence validation**: Add a verification step confirming Spanner data persists across `docker compose restart` using the forked emulator's `--data_dir` flag.
- **Data Browser CRUD**: Add create, update, and delete capabilities to the Data Browser UI for all browsable database services. Operations go through emulator APIs (not direct PostgreSQL), maintaining consistency with how real GCP services work.
- **Firestore & Bigtable browsing**: Promote Firestore and Bigtable from "connection only" to full data browsing in the Data Browser.

## Capabilities

### New Capabilities

- `data-browser-crud`: CRUD operations in the web console Data Browser, using emulator APIs to create, update, and delete data across all browsable services (GCS, Pub/Sub, BigQuery, Spanner, Firestore, Bigtable, Secret Manager, Cloud Tasks, Memorystore)

### Modified Capabilities

_None — no existing OpenSpec capabilities yet (this is the first change in the new system)_

## Impact

- **Seed file**: `seed.yaml` — significantly expanded with more services and data
- **Console frontend**: `localcloud-console/src/pages/DataBrowser.jsx` — add CRUD UI components (forms, edit dialogs, delete confirmations)
- **Console backend**: `localcloud-console/backend/app.py` and `proxy.py` — add mutation API endpoints
- **Java server**: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` — add write/update/delete endpoints that proxy to emulator APIs
- **Firestore/Bigtable**: New browse implementations in BrowseService using their REST/gRPC APIs
