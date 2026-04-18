## Context

LocalCloud already has the building blocks for remote cloud access:

| Component | Status | Location |
|-----------|--------|----------|
| `CredentialBroker` | Built, loads ADC/SA keys, extracts tokens | `CredentialBroker.java` |
| `ServiceRoutingRepository` | Built, persists local/remote mode per project+service | `ServiceRoutingRepository.java` |
| `RemoteProxyService` | Built but **NOT wired up** — maps service IDs to GCP base URLs, rewrites paths, adds auth headers | `RemoteProxyService.java` |
| Routing API | Built — `GET/PUT /_localcloud/routing/{service}` | `AdminApiService.java` |
| Settings UI | Partial — shows routing table but can't edit remote_project/remote_region | `Settings.jsx` |
| Console routing badges | Built — shows "Local"/"Cloud" per service | `Services.jsx`, `app.jsx` |
| Docker credential mounts | Ready (commented out in docker-compose.yml) | `docker-compose.yml` |

The gap: nothing connects the proxy to the browse/query flow, credentials expire, and the UI can't configure remote projects.

## Goals / Non-Goals

**Goals:**
- Browse real GCP project resources (GCS buckets/objects, BigQuery datasets/tables) from the LocalCloud console
- Discover and select GCP projects accessible with mounted credentials
- Per-service routing toggle (local emulator vs remote GCP) with persistence
- Token refresh for long-running sessions
- Clear visual distinction between local and remote data

**Non-Goals:**
- Writing/mutating data on remote GCP (read-only browsing)
- gRPC passthrough to remote GCP (REST-only for browse; gRPC proxying is a separate future change)
- Supporting all 14 services remotely (start with GCS and BigQuery; others can be added incrementally)
- Replacing the GCP Console (this is a developer convenience tool, not a full admin panel)
- Production data access controls or audit logging

## Decisions

### D1: Start with GCS and BigQuery only

**Choice:** Phase 1 supports remote browsing for GCS (list buckets, list objects, view metadata) and BigQuery (list datasets, list tables, preview rows, run queries). Other services can be added later using the same pattern.

**Why:** GCS and BigQuery are the most commonly used data services from developer laptops. GCS is simple (REST JSON API), BigQuery is the highest-value (query real data). Both have well-documented REST APIs that `RemoteProxyService` already maps to.

### D2: Proxy through the Java gateway, not direct from browser

**Choice:** The browser calls LocalCloud's admin API, which calls `RemoteProxyService`, which calls GCP. The browser never calls GCP directly.

**Why:** (a) Credentials stay server-side — never exposed to the browser. (b) The routing decision is centralized in the Java server. (c) CORS issues avoided. (d) Consistent with the existing browse/query architecture.

### D3: Modify BrowseService to check routing before executing

**Choice:** At the top of each browse handler (GCS, BigQuery, etc.), check `ServiceRoutingRepository.get(projectId, serviceId)`. If mode is "remote", delegate to `RemoteProxyService` instead of querying PostgreSQL.

**Why:** Minimal code change — the routing check is a 5-line guard clause at the top of each browse method. The response format stays the same (the proxy transforms GCP API responses to match the local browse format).

### D4: GCP Project Discovery via Resource Manager API

**Choice:** Add `GET /_localcloud/gcp/projects` that calls `https://cloudresourcemanager.googleapis.com/v1/projects` with the mounted credentials. Returns list of `{ projectId, name, projectNumber }`. Cached for 5 minutes.

**Why:** Users need to pick which GCP project to browse. The Resource Manager API is the standard way to list accessible projects. Caching avoids repeated API calls during UI interactions.

### D5: Token refresh via OAuth2 token endpoint

**Choice:** When `CredentialBroker.getAccessToken()` returns an expired token, refresh it using the `refresh_token` from the ADC file by calling `https://oauth2.googleapis.com/token` with `client_id`, `client_secret`, and `refresh_token`.

**Why:** ADC files from `gcloud auth application-default login` contain refresh tokens. Manual HTTP call avoids adding the google-auth-library dependency (~5 MB). The refresh flow is simple (one POST request).

### D6: Response transformation — GCP API → LocalCloud browse format

**Choice:** Create `GcpResponseTransformer` that maps GCP JSON API responses to the same format as local browse responses. Example: GCS `storage.buckets.list` response → same shape as local `/_localcloud/browse/gcs`.

**Why:** The console data explorer expects a consistent response format regardless of source. The transformer keeps the proxy transparent to the UI.

## Risks / Trade-offs

- **[Risk] Credentials expire during long session** → Mitigated by D5 (token refresh). If refresh also fails, UI shows "Credentials expired, re-run gcloud auth" message.
- **[Risk] GCP API rate limits** → Mitigated by caching project list (D4). Browse requests are user-initiated (not polling), so rate limits are unlikely for dev usage.
- **[Risk] Large GCS buckets with millions of objects** → Mitigated by pagination (GCS API supports `maxResults` and `pageToken`). UI shows first 100 objects with "Load more" button.
- **[Risk] BigQuery query costs on remote project** → Mitigated by showing a "Remote query" warning banner with estimated bytes scanned before execution. Dry-run support via BigQuery's `dryRun` flag.
- **[Risk] Service account key without required IAM permissions** → Mitigated by catching 403 errors and showing clear "Permission denied" messages with the required IAM role.
