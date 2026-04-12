## Why

LocalCloud emulates 14 GCP services locally, but services like Compute, GKE, and Cloud Run create real Docker containers/clusters that often need to reach resources in a real GCP development environment — Artifact Registry for images, Cloud Storage for data, Secret Manager for production-like secrets. Today this is impossible: there is no credential passthrough, no visibility into whether traffic goes to a local emulator or real GCP, and no way to toggle individual services between local and remote mode.

The existing `service-routing-indicator` change designed the routing detection API, UI badges, and enable/disable toggles — but was never implemented. This change subsumes that design and extends it with credential bridging, remote service proxying, and a unified console experience for hybrid local-cloud development.

## What Changes

### Routing & Visibility (from service-routing-indicator)
- **Routing detection API** (`GET /_localcloud/routing`): Returns per-service routing status (local/cloud/unknown) based on emulator process state
- **Routing badges**: Local (green) / Cloud (blue) / Unknown (gray) badges on Dashboard cards, Services table, and sidebar
- **User routing overrides**: Click badge to toggle, persisted in localStorage
- **Service enable/disable**: `POST /_localcloud/services/{id}/enable|disable` with supervisord control for external services and in-memory gating for facades
- **Enable/disable toggles**: Toggle switch on Services page per service

### Credential Bridging (new)
- **GCP credential sources**: Support two modes — ADC (mount `~/.config/gcloud/`) and Service Account key file — configurable via `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` env var
- **Credential detection API** (`GET /_localcloud/credentials`): Reports which credential source is active, whether it's valid, and the authenticated identity
- **Credential injection**: When spawning Compute/CloudRun/GKE containers, mount the credential file and set `GOOGLE_APPLICATION_CREDENTIALS` so workloads can reach real GCP

### Remote Service Proxy (new)
- **Per-service routing mode** (`PUT /_localcloud/routing/{service}`): Switch a service between `local` (emulator) and `remote` (proxy to real GCP)
- **Remote proxy**: When a service is in remote mode, LocalCloud forwards requests to the real GCP API with injected credentials instead of handling them locally
- **Remote config**: Per-service remote settings (target project, region) stored in PostgreSQL

### Console UI (new)
- **Credential configuration section** in Settings: Shows active credential source, validation status, and authenticated identity
- **Service routing panel** in Settings: Per-service dropdown to switch between Local and Remote modes, with remote config fields (project, region)
- **Docker-compose volume mounts** for credential files

## Capabilities

### New Capabilities
- `service-routing-detection`: Backend routing detection API, routing status per service, credential detection
- `service-routing-indicator-ui`: Console routing badges, user overrides, enable/disable toggles across Dashboard, Services, and sidebar
- `service-enable-disable`: Enable/disable individual services via API and console toggle
- `credential-bridging`: GCP credential mounting (ADC + SA key), credential validation, injection into spawned containers
- `remote-service-proxy`: Per-service local/remote mode switching, request proxying to real GCP with credential injection

### Modified Capabilities

_(none)_

## Impact

- **localcloud-server (Java)**: New AdminService endpoints (`/routing`, `/credentials`, `/services/{id}/enable|disable`, `/routing/{service}`), CredentialBroker service, RemoteProxyService, supervisord XML-RPC client, ContainerManager credential injection
- **localcloud-console (Solid.js)**: RoutingBadge component, enable/disable toggles on Services page, credential config in Settings, routing panel in Settings, sidebar routing indicators
- **docker-compose.yml**: Optional volume mounts for `~/.config/gcloud` and SA key file
- **Dockerfile**: Credential directory setup
- **services.yaml**: No changes (routing mode is runtime state, not static config)
- **Database**: New `service_routing` table for persisted routing mode and remote config
