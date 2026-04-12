## Why

Developers using LocalCloud often run a hybrid setup: some GCP services are emulated locally (e.g., BigQuery, Pub/Sub) while others connect to real Google Cloud (e.g., Cloud Storage in a staging bucket). Today the console shows service health status but has no visibility into whether traffic is routed locally or to real GCP. Developers must manually track which environment variables are set, leading to confusion and accidental cloud usage during local development. Additionally, there is no way to enable or disable individual services from the console — all services run regardless of whether they're needed, wasting container resources.

## What Changes

- Add a **routing indicator** (Local / Cloud / Unknown) to each service in the Dashboard, Services page, and sidebar, showing whether that service is currently pointing at LocalCloud or real Google Cloud
- Add a **service enable/disable toggle** allowing users to start/stop individual emulator services from the console, freeing resources for unused services
- Add a **backend API endpoint** (`GET /_localcloud/routing`) that returns the routing status per service by checking whether the emulator host environment variables are set and pointing at localhost
- Add a **user override mechanism** so users can explicitly mark a service as "Local" or "Cloud" when automatic detection isn't possible (e.g., env vars are set in a different shell session)
- Persist user routing overrides in localStorage so they survive page refreshes

## Capabilities

### New Capabilities
- `service-routing-detection`: Backend logic to detect whether each service's env var points to LocalCloud or real GCP, exposed via `/_localcloud/routing` API
- `service-routing-indicator-ui`: Console UI showing Local/Cloud/Unknown badge per service with user override capability
- `service-enable-disable`: Ability to enable/disable individual emulator services via console toggle and backend API

### Modified Capabilities

## Impact

- **Backend (Java)**: New `/_localcloud/routing` endpoint in AdminService; new `/_localcloud/services/{id}/enable` and `/_localcloud/services/{id}/disable` endpoints; supervisord control for external services
- **Console (Solid.js)**: Dashboard service cards, Services table, and sidebar sub-items gain routing badge and enable/disable toggle
- **API contract**: New REST endpoints added to the admin API surface
- **services.yaml**: May need a `userRouting` field for persisted overrides (or handle purely client-side)
