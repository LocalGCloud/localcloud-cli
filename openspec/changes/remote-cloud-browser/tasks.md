## 1. Credential Token Refresh

- [ ] 1.1 Add token expiry tracking to `CredentialBroker` — parse `expiry_date` from ADC file, store as `Instant`
- [ ] 1.2 Implement `refreshAccessToken()` — POST to `https://oauth2.googleapis.com/token` with refresh_token, client_id, client_secret from ADC file
- [ ] 1.3 Add proactive refresh in `getAccessToken()` — if token expires within 5 minutes, refresh before returning
- [ ] 1.4 Update `/_localcloud/credentials` response to include `token_valid`, `expires_in_seconds`
- [ ] 1.5 Write unit tests for token refresh logic

## 2. GCP Project Discovery

- [ ] 2.1 Add `GcpProjectService` class — calls `https://cloudresourcemanager.googleapis.com/v1/projects` with auth header from CredentialBroker
- [ ] 2.2 Add 5-minute caching in `GcpProjectService` using `ConcurrentHashMap` + `Instant` expiry
- [ ] 2.3 Add `GET /_localcloud/gcp/projects` endpoint in `AdminApiService` — returns `[{ projectId, name, projectNumber }]`
- [ ] 2.4 Handle error cases: no credentials (400), invalid token (401), API errors (502)
- [ ] 2.5 Add `gcpProjects()` to the console `api.js` client
- [ ] 2.6 Write unit tests for `GcpProjectService`

## 3. Wire RemoteProxyService

- [ ] 3.1 Instantiate `RemoteProxyService` in `LocalCloudApplication` constructor and inject `CredentialBroker` + `ServiceRoutingRepository`
- [ ] 3.2 Add `GcpResponseTransformer` class — transforms GCS `storage.buckets.list` response to local browse format
- [ ] 3.3 Add BigQuery response transformation — `bigquery.datasets.list`, `bigquery.tables.list`, `bigquery.tabledata.list` → local browse format
- [ ] 3.4 Modify `BrowseService` — add routing guard at top of GCS browse handlers: if mode="remote", delegate to `RemoteProxyService` + `GcpResponseTransformer`
- [ ] 3.5 Modify `BrowseService` — add routing guard for BigQuery browse handlers
- [ ] 3.6 Modify `QueryService` — add routing guard for BigQuery query execution: if mode="remote", proxy SQL to BigQuery Jobs API
- [ ] 3.7 Add BigQuery dry-run support — `POST /_localcloud/query` with `dryRun=true` parameter returns estimated bytes
- [ ] 3.8 Write integration tests for remote browse flow (mock GCP API responses)

## 4. Console UI — Settings Page Routing Controls

- [ ] 4.1 Add GCP project picker dropdown to Settings page — populated from `/_localcloud/gcp/projects`
- [ ] 4.2 Add editable `remote_project` field per service in the routing table — defaults to selected GCP project
- [ ] 4.3 Wire routing toggle to call `api.setRouting(serviceId, mode, remoteProject, remoteRegion)` with the selected project
- [ ] 4.4 Show credential status prominently — green check if valid, warning if expired, error if not mounted
- [ ] 4.5 Disable "Remote" toggle button if credentials are invalid or not mounted

## 5. Console UI — Remote Data Indicators

- [ ] 5.1 Add "Cloud" badge with blue accent to data explorer header when showing remote data
- [ ] 5.2 Add blue accent to table header row when displaying remote GCP data
- [ ] 5.3 Add cost warning banner for remote BigQuery queries — show estimated bytes from dry-run before execution
- [ ] 5.4 Show "Permission denied" inline error when remote API returns 403

## 6. Build & Test

- [ ] 6.1 Run Java server tests: `cd localcloud-server && ./gradlew test`
- [ ] 6.2 Build console: `cd localcloud-console && npm run build`
- [ ] 6.3 Build Docker image
- [ ] 6.4 Manual test: mount ADC credentials, enable remote GCS, verify real buckets appear in data explorer
- [ ] 6.5 Manual test: enable remote BigQuery, run a query against a real dataset
- [ ] 6.6 Manual test: toggle service back to local, verify emulated data reappears
