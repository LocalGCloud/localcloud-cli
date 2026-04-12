## 1. Database Schema — Routing Persistence

- [x] 1.1 Add `service_routing` table to `SchemaManager.initialize()`: columns `project_id`, `service_id`, `mode` (local/remote), `remote_project`, `remote_region`, `updated_at`, primary key `(project_id, service_id)`
- [x] 1.2 Create `ServiceRoutingRepository` class with methods: `getAll(projectId)`, `get(projectId, serviceId)`, `upsert(projectId, serviceId, mode, remoteProject, remoteRegion)`
- [x] 1.3 Write unit tests for ServiceRoutingRepository (insert, update, get, default-to-local)

## 2. Backend — Routing Detection API

- [x] 2.1 Add `GET /_localcloud/routing` endpoint to AdminApiService that returns per-service routing status: `emulatorRunning`, `healthy`, `routing` (auto-detected: local/cloud/unknown), `mode` (configured: local/remote), `port`, `envVar`
- [x] 2.2 Implement routing detection logic: check supervisord process status for external services (via `supervisorctl status` or XML-RPC), check in-memory flag for facade services
- [x] 2.3 Merge auto-detected routing with persisted mode from `service_routing` table
- [x] 2.4 Add `PUT /_localcloud/routing/{service}` endpoint: accepts `{ "mode": "local|remote", "remote_project": "...", "remote_region": "..." }`, persists to `service_routing` table, returns updated routing status
- [x] 2.5 Validate service ID against registry, return 404 for unknown services
- [x] 2.6 Write unit tests for routing endpoints (all-local, mixed, remote-mode, invalid service)

## 3. Backend — Service Enable/Disable API

- [x] 3.1 Add supervisord XML-RPC client utility class (`SupervisorClient`) that can start/stop named processes via HTTP call to `http://localhost:9001/RPC2`
- [x] 3.2 Add `POST /_localcloud/services/{id}/enable` endpoint: for external services call supervisord start, for facade services toggle in-memory `enabledServices` map
- [x] 3.3 Add `POST /_localcloud/services/{id}/disable` endpoint: for external services call supervisord stop, for facade services toggle map and gate with 503
- [x] 3.4 Return `{ "status": "enabled" }`, `{ "status": "disabled" }`, or `{ "status": "already_enabled|already_disabled" }` for idempotent calls
- [x] 3.5 Add in-memory `enabledServices` concurrent map to `LocalCloudConfig` initialized from `LOCALCLOUD_ENABLE_*` env vars, checked by facade service handlers
- [x] 3.6 Write unit tests for enable/disable endpoints (external, facade, idempotent, unknown service)

## 4. Backend — Credential Bridging

- [x] 4.1 Add `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` env var to `LocalCloudConfig` with values `none` (default), `adc`, `service-account`
- [x] 4.2 Create `CredentialBroker` class that on startup: detects credential source, reads credential file (`/credentials/adc/application_default_credentials.json` for ADC, `/credentials/sa-key.json` for SA key), validates format (JSON with `client_email` or `type` field), extracts identity/project
- [x] 4.3 Add `getAccessToken()` method to CredentialBroker using `com.google.auth.oauth2.GoogleCredentials` to generate OAuth2 tokens from the credential file
- [x] 4.4 Add `GET /_localcloud/credentials` endpoint returning `{ "source", "valid", "identity", "project", "error" }`
- [x] 4.5 Handle startup gracefully: if credential file missing, log warning and set source to `none`
- [x] 4.6 Write unit tests for CredentialBroker (ADC detection, SA key detection, missing file, invalid format)

## 5. Backend — Credential Injection into Containers

- [x] 5.1 Extend `ContainerManager.createAndStart()` to accept an optional credential file path parameter
- [x] 5.2 When credentials are available, add bind mount (`credentialFilePath:/credentials/gcp.json:ro`) and env var `GOOGLE_APPLICATION_CREDENTIALS=/credentials/gcp.json` to the container
- [x] 5.3 Also inject `GOOGLE_CLOUD_PROJECT` into spawned containers (from remote config or `LOCALCLOUD_PROJECT`)
- [x] 5.4 Update `ComputeEmulator.insertInstance()` to pass credential file path from CredentialBroker
- [x] 5.5 Update `CloudRunEmulator.createService()` to pass credential file path from CredentialBroker
- [x] 5.6 Update `GkeEmulator` / `K3dManager` to mount credentials into k3d cluster nodes (via k3d volume mount flag)
- [x] 5.7 Write unit tests for ContainerManager credential injection (with credentials, without credentials)

## 6. Backend — Remote Service Proxy

- [x] 6.1 Create `RemoteProxyService` class that takes an incoming HTTP request, adds `Authorization: Bearer <token>` header from CredentialBroker, and forwards to the real GCP API endpoint
- [x] 6.2 Map service IDs to GCP API base URLs: `gcs` → `https://storage.googleapis.com`, `bigquery` → `https://bigquery.googleapis.com`, `secretmanager` → `https://secretmanager.googleapis.com`, etc.
- [x] 6.3 Integrate with Armeria gateway: when a request arrives for a service in `remote` mode (from `service_routing` table), delegate to RemoteProxyService instead of the local emulator
- [x] 6.4 Rewrite request paths as needed (e.g., replace project ID in URL path with `remote_project`)
- [x] 6.5 Return HTTP 503 with clear error message when remote mode is active but no credentials are configured
- [x] 6.6 Log proxy requests at INFO level: `"[REMOTE] {method} {path} → {gcpEndpoint} ({statusCode})"`
- [x] 6.7 Write unit tests for RemoteProxyService (mock HTTP client, token injection, error handling)

## 7. Docker Configuration — Credential Mounts

- [x] 7.1 Add commented-out volume mounts to `docker-compose.yml` for ADC and SA key with documentation comments
- [x] 7.2 Add `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` to docker-compose.yml environment section (default: `none`)
- [x] 7.3 Add `/credentials/adc` and `/credentials` directories to Dockerfile with correct ownership
- [x] 7.4 Add `LOCALCLOUD_GCP_SA_KEY` build/env var support for SA key file path

## 8. Console — API Client Extensions

- [x] 8.1 Add `api.routing()` method to fetch `GET /_localcloud/routing`
- [x] 8.2 Add `api.credentials()` method to fetch `GET /_localcloud/credentials`
- [x] 8.3 Add `api.setRouting(serviceId, mode, remoteProject, remoteRegion)` method for `PUT /_localcloud/routing/{service}`
- [x] 8.4 Add `api.enableService(id)` and `api.disableService(id)` methods
- [x] 8.5 Add routing and credential data to the auto-refresh effect in `app.jsx` alongside health data

## 9. Console — RoutingBadge Component

- [x] 9.1 Create `RoutingBadge` component with three visual states: Local (green), Cloud (blue), Unknown (gray)
- [x] 9.2 Add click handler to toggle user override (Local → Cloud → auto-detect cycle)
- [x] 9.3 Add tooltip on hover: "Traffic routes to LocalCloud emulator. Click to override." / "Traffic routes to Google Cloud. Click to override."
- [x] 9.4 Store/load routing overrides in localStorage key `localcloud-routing-overrides`
- [x] 9.5 Add CSS classes `.badge-local`, `.badge-cloud`, `.badge-unknown` to components.css

## 10. Console — Dashboard Integration

- [x] 10.1 Add routing badge to Dashboard service cards next to the health status dot
- [x] 10.2 Merge routing data with health data in the services signal
- [x] 10.3 Show "Disabled" badge (dimmed card) for disabled services replacing health/routing badges

## 11. Console — Services Page Integration

- [x] 11.1 Add "Routing" column to the Services table showing the RoutingBadge
- [x] 11.2 Add enable/disable toggle switch to each service row
- [x] 11.3 Wire toggle to call `api.enableService()` / `api.disableService()` and refresh status
- [x] 11.4 Show disabled services with dimmed row styling and gray dot

## 12. Console — Sidebar Integration

- [x] 12.1 Add small routing indicator dot to sidebar sub-items alongside health dot
- [x] 12.2 Show dimmed text and gray dot for disabled services in sidebar

## 13. Console — Settings Page: Credential Config

- [x] 13.1 Add "GCP Credentials" section to Settings page, below the Environment Variables section
- [x] 13.2 Show credential source, validation status (green check / red X), authenticated identity, and project
- [x] 13.3 When source is `none`, show informational card with docker-compose volume mount instructions for enabling ADC or SA key
- [x] 13.4 Poll `/_localcloud/credentials` on the same refresh interval as health

## 14. Console — Settings Page: Service Routing Panel

- [x] 14.1 Add "Service Routing" section to Settings page showing all services with current mode (Local/Remote dropdown)
- [x] 14.2 When a service is set to Remote, show inline fields for remote project and region
- [x] 14.3 Wire dropdown change to call `api.setRouting()` and refresh routing data
- [x] 14.4 Disable Remote option in dropdown when credentials are not configured (tooltip: "Configure GCP credentials to enable remote mode")
- [x] 14.5 Show credential status indicator at the top of the routing panel

## 15. Styling

- [x] 15.1 Add CSS for `.badge-local` (green, `var(--success)`), `.badge-cloud` (blue, `var(--primary)`), `.badge-unknown` (gray, `var(--text-tertiary)`) badges
- [x] 15.2 Add CSS for the enable/disable toggle switch (reuse existing `.toggle-switch` styles)
- [x] 15.3 Add CSS for the credential status card (success/warning states, info card with code block)
- [x] 15.4 Add CSS for the routing panel (service rows with dropdown, inline config fields)
- [x] 15.5 Ensure all styles work in both light and dark mode (CSS variables only)

## 16. Build & Verification

- [x] 16.1 Build console (`cd localcloud-console && npm run build`) — verify no build errors
- [x] 16.2 Run Java server tests (`cd localcloud-server && ./gradlew test`) — verify all tests pass including new ones
- [x] 16.3 Manual verification: routing badges on Dashboard, Services, sidebar; enable/disable toggles; credential status in Settings; routing panel with Local/Remote switching
