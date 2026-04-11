## 1. Expand Seed Data

- [x] 1.1 Add Firestore seed data to `seed.yaml` — `users` collection (10 documents with name, email, jobTitle, worksFor, nested address) and `products` collection (8 documents with name, price, category, inStock)
- [x] 1.2 Add Bigtable seed data to `seed.yaml` — `user-activity` table with column families `profile` and `activity`, 10+ rows with row keys like `user#1`, `user#2`
- [x] 1.3 Expand Spanner seed data in `seed.yaml` — add `orders_db` database with `Orders` table (15 rows), `OrderItems` interleaved table (25 rows), and `Products` table (10 rows)
- [x] 1.4 Expand BigQuery seed data in `seed.yaml` — grow `page_views` to 15 rows, add `orders` table (10 rows) to `app_analytics` dataset
- [x] 1.5 Expand Memorystore seed data in `seed.yaml` — add list keys (`queue:tasks`), set keys (`tags:popular`), sorted set keys (`leaderboard:global`), and more string/hash entries
- [x] 1.6 Add more Secret Manager entries — add `oauth-client-secret`, `database-encryption-key` secrets with versions

## 2. Firestore Seed & Browse Implementation

- [x] 2.1 Add Firestore seeding in `SeedService.java` — implement `seedFirestore()` method that calls Firestore REST API `POST /v1/projects/{project}/databases/(default)/documents/{collection}` to create documents
- [x] 2.2 Add Firestore browsing in `BrowseService.java` — implement `browseFirestore()` method that calls Firestore REST API to list collections and documents, returning collection names, document IDs, and field data
- [x] 2.3 Add Firestore browse proxy routes in Flask backend `app.py` — `/api/browse/firestore`, `/api/browse/firestore/{collection}`, `/api/browse/firestore/{collection}/{docId}`
- [x] 2.4 Update `DataBrowser.jsx` — promote Firestore from `CONNECTION_ONLY` to `FETCH_SERVICES`, add collection → document drill-down navigation with sub-collection support

## 3. Bigtable Seed & Browse Implementation

- [x] 3.1 Add Bigtable seeding in `SeedService.java` — implement `seedBigtable()` method using Bigtable Admin REST API to create tables/column families and Data API to write rows
- [x] 3.2 Add Bigtable browsing in `BrowseService.java` — implement `browseBigtable()` with table listing via Admin API and row reading via Data API (ReadRows), returning row keys, column families, qualifiers, and cell values
- [x] 3.3 Add Bigtable browse proxy routes in Flask backend `app.py`
- [x] 3.4 Update `DataBrowser.jsx` — promote Bigtable from `CONNECTION_ONLY` to `FETCH_SERVICES`, add table → row data drill-down with column family grouping

## 4. MutateService (Java Backend CRUD Endpoints)

- [x] 4.1 Create `MutateService.java` in `com.localcloud.admin` — new Armeria annotated service registered at `/_localcloud/mutate` prefix, with POST/PUT/DELETE handlers dispatching to per-service mutation methods
- [x] 4.2 Implement GCS mutations — `createObject` (POST upload via GCS REST API), `deleteObject` (DELETE via GCS REST API)
- [x] 4.3 Implement Spanner mutations — `insertRow` (commit insert mutation via Spanner REST API with session management), `updateRow` (commit update mutation), `deleteRow` (commit delete mutation)
- [x] 4.4 Implement BigQuery mutations — `insertRow` (insertAll API), `deleteRow` (jobs.query with DELETE DML, best-effort)
- [x] 4.5 Implement Secret Manager mutations — `createSecret` (POST via gRPC API), `deleteSecret` (DELETE via gRPC API)
- [x] 4.6 Implement Memorystore mutations — `setKey` (via Redis RESP SET/HSET/LPUSH/SADD/ZADD), `deleteKey` (via Redis RESP DEL), `updateKey` (via Redis RESP SET/HSET)
- [x] 4.7 Implement Firestore mutations — `createDocument` (POST via Firestore REST API), `updateDocument` (PATCH), `deleteDocument` (DELETE)
- [x] 4.8 Implement Bigtable mutations — `writeRow` (MutateRow via Data API), `deleteRow` (MutateRow with DeleteFromRow)
- [x] 4.9 Register `MutateService` in `AdminApiService.java` alongside `BrowseService`

## 5. Flask Backend Mutation Proxy

- [x] 5.1 Add mutation proxy routes in `app.py` — `POST/PUT/DELETE /api/mutate/{service}/*` routes that proxy to `/_localcloud/mutate/{service}/*` on the Java server
- [x] 5.2 Ensure CORS and JSON content-type handling for mutation requests

## 6. Frontend CRUD UI

- [x] 6.1 Create `CrudModal` component in `DataBrowser.jsx` — shared modal for Add/Edit operations with dynamic form fields based on service type and table schema
- [x] 6.2 Create `DeleteConfirmation` component — confirmation dialog showing record details before deletion
- [x] 6.3 Add CRUD controls to GCS browser — "Upload Object" button, "Delete" icon per object row
- [x] 6.4 Add CRUD controls to Spanner browser — "Add Row" button above table data, "Edit" and "Delete" icons per row, column-aware form generation from DDL metadata
- [x] 6.5 Add CRUD controls to BigQuery browser — "Add Row" button, "Delete" icon per row (with caveat message if DELETE unsupported)
- [x] 6.6 Add CRUD controls to Secret Manager browser — "Add Secret" button, "Delete" icon per secret
- [x] 6.7 Add CRUD controls to Memorystore browser — "Add Key" button with type selector (string/hash/list/set/sorted_set), "Edit" and "Delete" icons per key
- [x] 6.8 Add CRUD controls to Firestore browser — "Add Document" button, "Edit" and "Delete" icons per document
- [x] 6.9 Add CRUD controls to Bigtable browser — "Add Row" button, "Delete" icon per row
- [x] 6.10 Wire mutation API calls in `api.js` — add `mutate(service, action, data)` helper function that calls the Flask mutation endpoints

## 7. Spanner Persistence Validation

- [x] 7.1 Verify Spanner `--data_dir` is configured in `supervisord.conf` pointing to `/var/lib/localcloud/spanner-data`
- [ ] 7.2 Manual validation: seed Spanner data, restart Docker container, confirm data persists via Data Browser and SDK queries
- [x] 7.3 Document the Spanner persistence verification steps in the seed.yaml file comments or integration guide

## 8. Build & Test

- [x] 8.1 Build Java server (`./gradlew build`) — ensure MutateService compiles and existing tests pass
- [x] 8.2 Build console frontend (`npm run build`) — ensure updated DataBrowser.jsx builds without errors
- [ ] 8.3 Run full Docker Compose cycle — `docker compose build && docker compose up`, load seed data, verify all services have data in Data Browser
- [ ] 8.4 Test CRUD operations end-to-end — create, edit, and delete a record for each service through the Data Browser UI
