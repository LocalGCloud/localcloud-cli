## Context

LocalCloud runs 14 GCP emulators in a single Docker container. Services like Compute, Cloud Run, and GKE spawn real Docker containers via the host Docker socket, but those containers have no GCP credentials and cannot reach real GCP resources. The console shows service health but has no visibility into routing (local vs. cloud) and no ability to toggle services.

The `service-routing-indicator` change designed the routing API, UI badges, and enable/disable — but was never implemented. This change subsumes that design and adds credential bridging and remote proxying.

**Existing auth:** IamMiddleware supports three modes (`permissive`/`strict`/`gcp-live`). The `gcp-live` mode validates tokens against Google's tokeninfo endpoint but doesn't distribute credentials to spawned containers.

**Existing container management:** `ContainerManager.createAndStart(image, name, ports, env, labels)` creates Docker containers with env vars and labels. It does NOT mount volumes or inject credential files today.

## Goals / Non-Goals

**Goals:**
- Implement routing detection API and console badges (from `service-routing-indicator` design)
- Implement service enable/disable API and console toggles (from `service-routing-indicator` design)
- Mount real GCP credentials (ADC or SA key) into the LocalCloud container
- Inject credentials into spawned Compute/CloudRun/GKE containers
- Allow per-service switching between local emulator and remote GCP proxy
- Credential status visible in console Settings page
- All features work in both light and dark theme

**Non-Goals:**
- Building a full GCP API proxy for all 200+ GCP services (only the 14 LocalCloud services can be toggled)
- Implementing Workload Identity Federation (future enhancement)
- Metadata server emulation at 169.254.169.254 (future enhancement)
- Credential rotation or automatic refresh (user manages their own credentials)

## Decisions

### D1: Credential source hierarchy — ADC > SA key > none

**Choice:** Check for credentials in order: (1) ADC at `/credentials/adc/application_default_credentials.json`, (2) SA key at `/credentials/sa-key.json`, (3) none (fully isolated, current behavior). The active source is determined by `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` env var (`adc`, `service-account`, or `none`).

**Rationale:** ADC is the standard for development (every GCP developer has `gcloud auth application-default login`). SA keys are standard for CI/CD. Explicit env var control prevents accidental credential leakage.

**Alternatives considered:**
- Auto-detect without env var: Risky — credentials could be accidentally mounted and used without the user's intent. Explicit opt-in is safer.

### D2: Credential injection via volume mount + env var

**Choice:** When spawning containers via ContainerManager, if credentials are available:
1. Bind-mount the credential file into the container at `/credentials/gcp.json`
2. Set `GOOGLE_APPLICATION_CREDENTIALS=/credentials/gcp.json` in container env
3. Set `GOOGLE_CLOUD_PROJECT` from the remote config or fallback to `LOCALCLOUD_PROJECT`

**Rationale:** This is how GCP SDKs discover credentials in any environment. Works with all Google Cloud client libraries across all languages.

**Alternatives considered:**
- Token injection via env var: Short-lived, requires refresh logic. Volume mount is simpler.
- Metadata server emulation: More realistic but significantly more complex (requires network interception). Deferred to future.

### D3: Remote proxy architecture — reverse proxy in Java

**Choice:** When a service is set to `remote` mode, the Armeria gateway acts as a reverse proxy:
1. Intercept the incoming request (same local port/protocol)
2. Add `Authorization: Bearer <token>` header using the configured credential
3. Forward to the real GCP API endpoint (e.g., `storage.googleapis.com`)
4. Return the GCP response to the client

For gRPC services, use Armeria's gRPC client to forward calls. For REST services, use Armeria's HTTP client.

**Rationale:** The client SDK already talks to LocalCloud ports. Proxying transparently means zero client-side changes — just flip the routing mode.

**Limitations:** Not all services can be proxied equally:
- **REST services (GCS, BigQuery, Compute):** Straightforward HTTP proxying
- **gRPC services (Pub/Sub, Firestore, Spanner):** Requires gRPC channel forwarding with TLS
- **Spawned-container services (Compute, CloudRun, GKE):** These create local resources — "remote" mode means the management API proxies to real GCP but containers still run locally. Hybrid behavior.

### D4: Routing state persisted in PostgreSQL

**Choice:** Store per-service routing mode (`local`/`remote`) and remote config (project, region) in a `service_routing` table. Default to `local` for all services. Changes via `PUT /_localcloud/routing/{service}` update the DB.

**Rationale:** Routing state must survive container restarts (persistent volume). PostgreSQL is already the persistence layer for all facade services.

**Schema:**
```sql
CREATE TABLE service_routing (
    project_id VARCHAR(255) NOT NULL,
    service_id VARCHAR(255) NOT NULL,
    mode VARCHAR(20) DEFAULT 'local',
    remote_project VARCHAR(255),
    remote_region VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, service_id)
);
```

### D5: Console UI — Settings page for credentials, Services page for routing

**Choice:**
- **Settings page:** New "GCP Credentials" section showing credential source, status, identity, and how to configure
- **Services page:** Routing badge + enable/disable toggle per service row. Clicking the routing badge opens an inline dropdown (Local/Remote) with remote config fields when Remote is selected
- **Dashboard:** Routing badge on service cards (read-only indicator)
- **Sidebar:** Small routing dot alongside health dot

**Rationale:** Credentials are a global config (Settings). Routing is per-service (Services page). Badges everywhere for visibility.

### D6: Supervisord XML-RPC for external service control (from service-routing-indicator)

**Choice:** Use supervisord's XML-RPC API (`http://localhost:9001/RPC2`) to start/stop external emulator processes (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery). For facade services (Secret Manager, Cloud Tasks, etc.), toggle an in-memory `enabledServices` map and return 503 when disabled.

**Rationale:** Supervisord already manages these processes and supports programmatic control. No need for a custom process manager.

## Risks / Trade-offs

- **[Risk] Credential file permissions in Docker** → Mitigated by mounting read-only (`:ro`), running as `localcloud` user
- **[Risk] Accidental real GCP writes** → Mitigated by defaulting `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=none`; remote mode is explicit opt-in per service
- **[Risk] gRPC proxy complexity** → Start with REST-only remote proxy (GCS, BigQuery). gRPC proxy (Pub/Sub, Spanner) is phase 2
- **[Trade-off] Remote mode doesn't work for all services** → Compute/CloudRun/GKE in "remote" mode proxy the management API, not the workload. Document clearly.
- **[Trade-off] No metadata server emulation** → Spawned containers get file-based credentials, not instance metadata. This differs from real GCP but works with all SDKs.
