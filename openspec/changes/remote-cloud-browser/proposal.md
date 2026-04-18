## Why

LocalCloud emulates GCP services locally, but developers also need to see real data from their GCP projects — buckets, datasets, instances — alongside local emulated data. Today, a developer must switch between the LocalCloud console and the GCP Console (or `gcloud` CLI) to view their cloud resources. By adding remote cloud browsing, LocalCloud becomes a **unified dev console** for both local emulation and real GCP project data, all from the same UI.

The credential bridging infrastructure (`CredentialBroker`), routing persistence (`ServiceRoutingRepository`), and an HTTP proxy (`RemoteProxyService`) already exist but are not wired up. This change activates and extends them.

## What Changes

- **Activate RemoteProxyService**: Wire the existing (but unused) `RemoteProxyService` into the application, connecting it to the browse/query endpoints so requests can be routed to real GCP when a service is set to "remote" mode
- **GCP project discovery**: Add a new endpoint that uses Google Cloud Resource Manager API to list projects accessible with the mounted credentials, letting users pick which GCP project to browse
- **Remote browse for GCS**: When routing mode is "remote" for GCS, the data explorer fetches real buckets and objects from the remote GCP project using the Storage JSON API
- **Remote browse for BigQuery**: When routing mode is "remote" for BigQuery, the data explorer fetches real datasets, tables, and can run queries against the remote project
- **Console UI — project picker and routing controls**: Enhance the Settings page to allow selecting a remote GCP project (discovered via Resource Manager API), editing remote_project/remote_region per service, and toggling between local/remote routing with clear visual feedback
- **Console UI — remote data indicator**: Show a "Cloud" badge on data explorer and service pages when viewing remote data, with a different color scheme to distinguish real GCP data from local emulated data
- **Token refresh**: Add Google OAuth2 token refresh for ADC credentials so long-running sessions don't expire

## Capabilities

### New Capabilities
- `gcp-project-discovery`: Discover GCP projects accessible with the mounted credentials via Resource Manager API. Includes endpoint `GET /_localcloud/gcp/projects` and UI project picker.
- `remote-data-browse`: Browse real GCP resources (buckets, datasets, tables, objects) when a service is routed to "remote" mode. Extends BrowseService to delegate to RemoteProxyService for remote-routed services.
- `credential-token-refresh`: Refresh expired OAuth2 access tokens from ADC files so remote browsing works beyond the initial token lifetime.

### Modified Capabilities
- `service-routing-detection`: Extend the routing UI in Settings to include editable remote_project/remote_region fields and a GCP project picker dropdown populated from the discovery endpoint.

## Impact

- **Backend (Java)**: Wire `RemoteProxyService` into `LocalCloudApplication`, modify `BrowseService` and `QueryService` to check routing config before executing, add GCP project discovery endpoint, add token refresh to `CredentialBroker`
- **Frontend (Solid.js)**: Enhance Settings page with project picker and editable routing fields, add "Cloud" badge to data explorer, differentiate local vs remote data visually
- **Dependencies**: May need `com.google.auth:google-auth-library-oauth2-http` for token refresh (or implement OAuth2 token exchange manually via HTTP)
- **Security**: Only reads data — no write/delete operations on remote GCP. Credentials are mounted read-only.
- **Docker**: Credential volume mounts already exist (commented out in docker-compose.yml)
