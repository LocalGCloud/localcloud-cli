## 1. Per-Emulator Health Checks

- [x] 1.1 Extend `HealthCheckService` response to include per-service health status — added `GET /_localcloud/health/{service}` endpoint for individual service health, existing `/health` already returns per-service map
- [x] 1.2 Add Flask proxy for detailed health — `/api/health` already proxies per-service breakdown
- [x] 1.3 Add health indicators to Data Browser tabs — colored dots (green/red/gray) next to each tab, fetched every 30 seconds with cleanup

## 2. Per-Service Reset

- [x] 2.1 Add `POST /_localcloud/reset/{service}` endpoint — dispatches to 14 per-service reset methods (SQL DELETE for PostgreSQL-backed, API calls for external emulators)
- [x] 2.2 Add optional `restore_seed` support — parses JSON body, re-seeds just that service after clearing
- [x] 2.3 Add Flask proxy route `POST /api/reset/<service>`
- [x] 2.4 Add "Reset Service" button in Data Browser — confirmation dialog, calls resetService API, reloads data

## 3. Data Browser Fixes and Polish

- [x] 3.1 Verify Firestore browsing works with seeded data — seedFirestore uses REST PATCH with retry logic, browse uses REST GET
- [x] 3.2 Verify Spanner CRUD buttons work — Add/Edit/Delete buttons wired to MutateService Spanner handlers
- [x] 3.3 Polish Pub/Sub message view — View Messages button, decoded data display, Publish Message form
- [x] 3.4 Add empty-state improvements — already has empty-state messages per service view
- [x] 3.5 Add refresh button to each service's data view — Refresh button in service action bar

## 4. State Export as Seed YAML

- [x] 4.1 Add `GET /_localcloud/export` endpoint in ExportService — queries GCS, Pub/Sub, BigQuery, Secret Manager, Spanner, Memorystore, Cloud Tasks
- [x] 4.2 Add Flask proxy route `GET /api/export` — returns YAML with Content-Disposition attachment header
- [x] 4.3 Add "Export State" button in Settings page — downloads YAML file
- [x] 4.4 Add "Export State" button to Dashboard quick actions — alongside Reset All and Copy Env Vars

## 5. Build & Test

- [x] 5.1 Build Java server — BUILD SUCCESSFUL (187 tests pass)
- [x] 5.2 Build console frontend — Build complete (app.js + styles.css)
- [ ] 5.3 Test per-service reset end-to-end — reset one service, verify others unaffected
- [ ] 5.4 Test export — export state, reset all, re-import exported YAML, verify data matches
