# LocalCloud Common Integration Guide

This guide is the common contract for repository integration automation. LocalCloud is a local/CI test dependency, never a production replacement.

All service-specific notes live in `services/<service>.md`. Per-service guides do **not** repeat any setup from this document — they refer back here.

## 1. Runtime contract

### 1.1 Container image

Use the published image, always:

```
jaysen2apache/localcloud:latest
```

The `localcloud/localcloud:latest` tag exists in the LocalCloud source repo as a developer convenience; **never** use it for repository integration automation. The published image is built and pushed from the `main` branch (see `.github/workflows/docker-publish.yml`).

Pin a specific tag (e.g. `jaysen2apache/localcloud:v0.7.0`) when reproducibility matters.

### 1.2 Reuse vs. own the container

1. **Reuse a supplied container.** If `LOCALCLOUD_URL` (or any of the `*_EMULATOR_HOST` env vars) is already set and the endpoint `GET ${LOCALCLOUD_URL}/health` returns 200, reuse it as-is. Never reconfigure, restart, or stop a container that was supplied to the test process.
2. **Otherwise start an owned container.** Run `docker run` with the image, ports, and mounts in §1.3. The container name MUST be `localcloud` (start.sh uses `--name localcloud` so that idempotent restart works).

### 1.3 Owned-container command (single source of truth)

The fixed port set comes from `services.yaml` in the LocalCloud source. **Do not change ports** — the LLM-driven test process relies on these exact host-side ports when it parses `/env?format=json`.

**Minimal (no DNS, no Terraform) — use this for most integration repos:**

```bash
docker run -d --name localcloud \
  -p 127.0.0.1:8080:8080 \
  -p 127.0.0.1:4443:4443 \
  -p 127.0.0.1:8085:8085 \
  -p 127.0.0.1:8086:8086 \
  -p 127.0.0.1:8087:8087 \
  -p 127.0.0.1:9010:9010 \
  -p 127.0.0.1:9020:9020 \
  -p 127.0.0.1:9050:9050 \
  -p 127.0.0.1:9060:9060 \
  -p 127.0.0.1:6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  -e LOCALCLOUD_PROJECT="${LOCALCLOUD_PROJECT:-local-project}" \
  -e LOCALCLOUD_SERVICES="<see §1.4>" \
  jaysen2apache/localcloud:latest
```

This drops LocalCloud-source-specific mounts (`seed.yaml`, `services.yaml`, `Caddyfile`, certs, `docker.sock`) and uses a named Docker volume for persistence. The internal defaults for seed data and service definitions are sufficient for most integration repos.

**Full variant (with DNS-redirect, Terraform, GKE support):**

```bash
docker run -d --name localcloud \
  -p 127.0.0.1:8080:8080 \
  -p 127.0.0.1:443:443 \
  -p 127.0.0.1:80:80 \
  -p 127.0.0.1:8053:53/udp \
  -p 127.0.0.1:4443:4443 \
  -p 127.0.0.1:8085:8085 \
  -p 127.0.0.1:8086:8086 \
  -p 127.0.0.1:8087:8087 \
  -p 127.0.0.1:9010:9010 \
  -p 127.0.0.1:9020:9020 \
  -p 127.0.0.1:9050:9050 \
  -p 127.0.0.1:9060:9060 \
  -p 127.0.0.1:6379:6379 \
  -p 127.0.0.1:16443:6443 \
  -m 4g \
  -v "$PWD/data:/var/lib/localcloud" \
  -v "$PWD/seed.yaml:/etc/localcloud/seed.yaml:ro" \
  -v "$PWD/services.yaml:/etc/localcloud/services.yaml:ro" \
  -v "$PWD/docker/conf/security/certs:/etc/caddy/certs:ro" \
  -v "$PWD/docker/conf/network/Caddyfile:/etc/caddy/Caddyfile:ro" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e LOCALCLOUD_PROJECT="${LOCALCLOUD_PROJECT:-local-project}" \
  -e LOCALCLOUD_SERVICES="<see §1.4>" \
  jaysen2apache/localcloud:latest
```

Use the full variant only when Custom CA certificates, custom seed/service definitions, GKE, or DNS-redirect flows are required. For DNS-redirect and Terraform, the additional ports 443, 80, and 8053/udp are needed — add them to the minimal command if DNS is required without the other full-variant mounts.

**Why fixed ports (not `-P`):** `-P` publishes all `EXPOSE`d ports to random host ports. The test process then has to call `docker port` and rewrite its environment, which is fragile. With fixed ports, the test process can hard-code the host port map and pass `/env?format=json` outputs through unchanged.

**Why `127.0.0.1:` on every mapping:** LocalCloud is a local/CI test dependency and must not be exposed beyond the test machine. Bind to loopback only.

**Why port 80 → 80 and 443 → 443 (full variant only):** Caddy (inside the container) listens on `:443` (HTTPS) and `8081` (HTTP, mapped to host 80 via the `-p 127.0.0.1:80:80`). These are needed for the DNS-redirect and Terraform flows in §3. Do not run your own services on host ports 80 or 443 while LocalCloud is up.

**Why 16443:6443 (full variant only):** the GKE k3d subprocess binds to host 6443 inside the container. Host 16443 keeps the user free to use 6443 on the host.

### 1.4 LOCALCLOUD_SERVICES — restrict the container to what the repo needs

`LOCALCLOUD_SERVICES` is a comma-separated list of service IDs (the same IDs in `services.yaml` and the per-service guides). The LocalCloud gateway uses it to decide which facades register gRPC stubs and which external emulators start under supervisord.

- If the variable is **unset**, every community-tier service in `services.yaml` starts. This is what `start.sh` defaults to.
- If the variable is **set**, only the listed services are enabled. Pro-tier services (`gke`, `cloudrun`, `compute`, `vertexai`, `kms`) are gated behind a license key in any case; the controller in `ServicesConfigService` honors the gate regardless of `LOCALCLOUD_SERVICES`.
- The repo's integration automation **must** set `LOCALCLOUD_SERVICES` to exactly the services its tests exercise. This keeps the container small and avoids provisioning cost from services that would never be used.

Example for a repo that only uses GCS, Pub/Sub, BigQuery, and Secret Manager:

```bash
-e LOCALCLOUD_SERVICES="gcs,pubsub,bigquery,secretmanager"
```

Available service IDs (26 total, all are also `services.yaml` keys):

```
gcs, pubsub, firestore, bigtable, spanner, bigquery,
secretmanager, cloudtasks, cloudscheduler, cloudfunctions, alloydb, dataproc,
cloudiam, cloudresourcemanager, serviceusage, cloudbilling, logging, monitoring,
gke, compute, cloudrun, memorystore, workflows, vertexai, kms, cloudsql
```

To pick a list, look at the env vars and import paths in the repo's test code; the service ID is the lowercase name before `_EMULATOR_HOST`. The per-service guides list each service's `Generated test environment` so a script can grep them out of `/env?format=json`.

### 1.5 Required startup verification

Before tests run, the integration process must:

1. Wait for `GET http://127.0.0.1:8080/health` to return 200.
2. `GET http://127.0.0.1:8080/readiness` to confirm every enabled service is up.
3. (For Terraform flows) `GET http://127.0.0.1:8080/terraform/readiness` to confirm Caddy is up and certs are present.
4. `GET http://127.0.0.1:8080/compatibility` (or `/coverage`) to confirm the LocalCloud version is at least as capable as the repo's tests expect.

## 2. Permitted repository changes

The repo integration automation may create or edit only these integration surfaces. Everything else is off-limits:

- `.github/workflows/localcloud.yml` — additive LocalCloud CI lane. Never replace real-GCP jobs.
- Existing test-only helpers such as `conftest.py` or `test_helpers.py` — only conditional LocalCloud/emulator setup, guarded by `LOCALCLOUD_URL` or a generated `*_EMULATOR_HOST`.
- `.localcloud/` — generated LocalCloud artifacts (see §4.1). Always gitignored.

The automation must not edit production source, credentials, lock files, generic CI workflows, Dockerfiles, host DNS/resolver configuration, or any other file. Do not write real credentials. Do not commit `.localcloud/`.

## 3. DNS guidance

LocalCloud runs `dnsmasq` inside the container (config: `docker/conf/network/dnsmasq.conf`) on port 53. It resolves `*.googleapis.com` and `metadata.google.internal` to `127.0.0.1`, where Caddy (port 443) routes the request to the LocalCloud gateway. The container's port 53 is mapped to host `127.0.0.1:8053`.

DNS is **only** required for tools that hard-code the public `*.googleapis.com` hostnames and cannot be redirected via env vars or a custom endpoint. Most cases do not need DNS.

### 3.1 SDK-based integration (no DNS)

The Google Cloud client libraries read these env vars and bypass DNS entirely. This is the **preferred path** for unit/integration tests:

| Service | Env var |
|---|---|
| Cloud Storage | `STORAGE_EMULATOR_HOST` |
| Pub/Sub | `PUBSUB_EMULATOR_HOST` |
| Firestore | `FIRESTORE_EMULATOR_HOST` |
| Bigtable | `BIGTABLE_EMULATOR_HOST` |
| Spanner | `SPANNER_EMULATOR_HOST` |
| BigQuery | `BIGQUERY_EMULATOR_HOST` |

The Python SDK honors every listed env var natively. Adoption varies by language — `STORAGE_EMULATOR_HOST` originated in the fake-gcs-server project (not Google) and `BIGQUERY_EMULATOR_HOST` was added in the Python SDK v3.1.0 (2022). Verify your language's SDK before assuming Level 1 for these services. For services where the SDK has no built-in emulator env var, use Level 2 (§6.2).

### 3.2 Terraform-based integration (DNS-redirect required)

The Terraform Google provider always reaches the public `googleapis.com` endpoints. To make it hit LocalCloud, point the test process's DNS for `googleapis.com` to the in-container dnsmasq. There are three supported patterns:

**Pattern C (recommended, end-to-end test):** eval the output of `GET /env?format=terraform` — it sets `GOOGLE_*_CUSTOM_ENDPOINT` for every service. The provider's `custom_endpoint` argument short-circuits the hostname lookup for that one resource type. Pattern C is sufficient for most repos and is the **default** in `.localcloud/terraform-env.sh`. No DNS configuration is needed — the custom endpoint env vars bypass the hostname lookup entirely.

**Pattern A (test process only, Linux/CI):** set `RESOLV_WRAPPER_CONF` or use `bind-tools` to forward `*.googleapis.com` queries to `127.0.0.1:8053` for the duration of the test. The repo's CI helper may use [`socat`](https://github.com/3ndG4me/socat) or a `docker run --dns=127.0.0.1 --dns-search=...` wrapper to achieve this.

**Pattern B (host DNS, macOS):** write `/etc/resolver/googleapis.com` with `nameserver 127.0.0.1` and `port 8053`. macOS picks the file up automatically. This affects the whole host, so use only on dev machines, not shared CI runners.

After applying any DNS or endpoint change, verify with:

```bash
curl http://127.0.0.1:8080/terraform/readiness
```

The endpoint returns 200 only when Caddy, dnsmasq, and the cert chain are all in place.

### 3.3 gcloud CLI

`gcloud` also hard-codes the public endpoints. Use Pattern A or B above, or set `CLOUDSDK_API_ENDPOINT_OVERRIDES_*` per service (less tested; prefer DNS).

## 4. The `.localcloud/` directory (generated, never hand-edited)

LocalCloud emits three files that the repo's test helper sources. The directory is created at the start of every owned-container run; the test process treats it as ephemeral.

### 4.1 `.localcloud/terraform-env.sh`

This is the **only** supported way to inject LocalCloud into the test process. It is a shell script emitted by LocalCloud that the test helper `source`s before running tests. It contains:

- `GOOGLE_*_CUSTOM_ENDPOINT` for every enabled service, pointing at the LocalCloud gateway
- `GOOGLE_PROJECT` and `GOOGLE_CLOUD_PROJECT` set to `LOCALCLOUD_PROJECT`
- `GOOGLE_OAUTH_ACCESS_TOKEN`, `GOOGLE_OAUTH_CUSTOM_ENDPOINT`, `GOOGLE_OPENID_CONNECT_CUSTOM_ENDPOINT` set to the LocalCloud OAuth2 stub
- `BIGTABLE_EMULATOR_HOST=localhost:8087` (the gRPC data plane port — note the host port, not the container port)
- `GOOGLE_APPLICATION_CREDENTIALS=/dev/null` to disable real auth

Regenerate it on every container start:

```bash
mkdir -p .localcloud
curl -sf http://127.0.0.1:8080/env?format=terraform -o .localcloud/terraform-env.sh
# Verify before sourcing:
curl -sf http://127.0.0.1:8080/terraform/readiness
source .localcloud/terraform-env.sh
```

The script is not in the LocalCloud repo because it is **emitted by the running container** — the version of `/env?format=terraform` in the running image is the source of truth. Add `.localcloud/` to `.gitignore`.

### 4.2 `.localcloud/env.json`

Same content as `terraform-env.sh` but JSON. Useful for non-shell helpers (Python, Go, Node).

```bash
curl -sf http://127.0.0.1:8080/env?format=json -o .localcloud/env.json
```

### 4.3 `.localcloud/services.yaml.snapshot`

A copy of the in-container `services.yaml` at the moment of capture. Useful when reproducing a failure: `diff` against the in-repo `services.yaml`.

## 5. Resource verification

After the test runs, verify that the resources the test claimed to create actually exist. LocalCloud exposes read-only admin endpoints at `/browse/...`. Every endpoint:

- Returns **HTTP 200** even on error, with a JSON body that has `{"error": true, "message": "..."}` for failures and the resource payload for success. **Always parse the body for `error` — never rely on status code alone.**
- Accepts `?project=<project_id>` to scope to a project. The default is the configured `LOCALCLOUD_PROJECT` (typically `local-project`).
- Is GET-only. There is no admin write endpoint for resources; tests must create them through the SDK or `*_EMULATOR_HOST` flow.

Each per-service guide lists the exact URLs for that service's browse tree. The browser view in the LocalCloud console (the Solid.js app served at `/`) is a thin wrapper over the same URLs.

## 6. SDK integration levels

The integration process picks one of three levels per service in the repo. The default is Level 1 where supported; Level 2 is the universal fallback; Level 3 (DNS-redirect) is for Terraform/gcloud only.

### 6.1 Level 1 — environment auto-detection

The SDK reads `*_EMULATOR_HOST` and diverts traffic to LocalCloud with zero code changes.

- **Supported services:** `gcs`, `pubsub`, `firestore`, `bigtable`, `spanner`, `bigquery`
- **Supported SDKs:** Python, Go, Node.js, Ruby, PHP, Java, C# (all official Google client libraries; the env var is documented in the upstream SDK for each language)

BigQuery's `BIGQUERY_EMULATOR_HOST` is natively supported in the Google Cloud Python SDK (v3.1.0+) and Go SDK, but adoption varies across other languages. Verify your language's SDK before assuming Level 1 for BigQuery.

When `.localcloud/terraform-env.sh` is sourced, the test process inherits these env vars automatically. No code change in the repo is needed.

### 6.2 Level 2 — code endpoint

For services without a built-in `*_EMULATOR_HOST` (Secret Manager, Cloud Tasks, Logging, Monitoring, Cloud Run, Compute, GKE, AlloyDB, Dataproc, Cloud IAM, Cloud Resource Manager, Service Usage, Cloud Billing, Workflows, Cloud Functions, Cloud SQL, Memorystore, Vertex AI, Cloud KMS), the test helper guards client construction:

```python
# Python — Secret Manager example (applies to all Level 2 services)
import os
from google.cloud import secretmanager

if os.environ.get("LOCALCLOUD_URL"):
    client = secretmanager.SecretManagerServiceClient(
        client_options={"api_endpoint": os.environ["SECRET_MANAGER_EMULATOR_HOST"]}
    )
else:
    client = secretmanager.SecretManagerServiceClient()
```

The guard is the only allowed code change in production source. Never remove the guard for production-only builds. The pattern is identical in every language.

### 6.3 Level 3 — DNS redirect / universal fallback

For Terraform and `gcloud` (see §3). Application code does not need to change; the network layer redirects `*.googleapis.com` to LocalCloud.

## 7. Patch contract for model-assisted integration

The model must emit a Git unified diff. It may:

- Add or modify `.github/workflows/localcloud.yml` as an additive LocalCloud CI lane
- Add or modify a test helper file (`conftest.py`, `test_helpers.py`, `TestMain.java`, etc.) at a recognized insertion point
- Reference the per-service guides and `/compatibility` to decide which services are needed

It must not:

- Edit production source outside the test-helper guard
- Invent service support (use `/compatibility` and the per-service guides)
- Modify lock files, generic CI workflows, Dockerfiles, or host DNS/resolver configuration
- Write real credentials

The prospector (or a CI step) validates paths, diff shape, guard presence, and patch application before staging.

## 8. Validation contract

1. Run the repository-native command in its declared environment (`uv run`, `pixi run`, `npm`, `go test`, etc.). Never substitute a host-global version.
2. Clear real Google credential inputs. Source `.localcloud/terraform-env.sh` (or set its env vars by another means) before the test command.
3. After the test passes, verify with GET-only LocalCloud API assertions. See the per-service guide for URLs.
4. Both the test and the resource verification must pass before a PR is ready.

## 9. Fixed port reference (mirror of `services.yaml`)

This is the single source of truth for port mapping. The Docker `-p` flags in §1.3 must stay in lockstep with this table. The LocalCloud source `services.yaml` is the canonical version; this table is regenerated from it.

| Service ID | Protocol | Port (host = container) | Type | Tier |
|---|---|---|---|---|
| gateway | rest | 8080 | facade | community |
| gcs | rest | 4443 | external | community |
| pubsub | grpc | 8085 | external | community |
| firestore | grpc | 8086 | external | community |
| bigtable | grpc | 8087 | external | pro |
| spanner (gRPC) | grpc | 9010 | external | pro |
| spanner (REST) | rest | 9020 | external | pro |
| bigquery (REST) | rest | 9050 | external | community |
| bigquery (gRPC) | grpc | 9060 | external | community |
| secretmanager | grpc | 8080 (gateway) | facade | community |
| cloudtasks | grpc | 8080 (gateway) | facade | community |
| cloudscheduler | grpc | 8080 (gateway) | facade | community |
| cloudfunctions | grpc | 8080 (gateway) | facade | community |
| alloydb | grpc | 8080 (gateway) | facade | community |
| dataproc | grpc | 8080 (gateway) | facade | community |
| cloudiam | grpc | 8080 (gateway) | facade | community |
| cloudresourcemanager | rest | 8080 (gateway) | facade | community |
| serviceusage | rest | 8080 (gateway) | facade | community |
| cloudbilling | rest | 8080 (gateway) | facade | community |
| logging | grpc | 8080 (gateway) | facade | community |
| monitoring | grpc | 8080 (gateway) | facade | community |
| cloudrun | grpc | 8080 (gateway) | facade | pro |
| compute | rest | 8080 (gateway) | facade | pro |
| gke (gateway) | grpc | 8080 (gateway) | facade | pro |
| gke (k3d) | https | 16443 | external | pro |
| memorystore | redis | 6379 | external | community |
| workflows | grpc | 8080 (gateway) | facade | community |
| vertexai | rest | 8080 (gateway) | facade | pro |
| kms | rest | 8080 (gateway) | facade | pro |
| cloudsql (gateway) | rest | 8080 (gateway) | facade | community |
| cloudsql (postgres) | pgsql | 5432 | external | community |
| cloudsql (mysql) | mysql | 3306 | external | community |
| dnsmasq | dns | 8053 (host) → 53 (container) | external | community |
| caddy (https) | https | 443 | external | community |
| caddy (http) | http | 80 (host) → 8081 (container) | external | community |

**Notes for the LLM:**

- All `gateway`-tagged services ride on the same 8080 port; the gateway routes by `Host` header and gRPC service name. There is one health check (`/health`); the readiness endpoint (`/readiness/{serviceId}`) reports per-service status.
- Pro-tier services (`gke`, `cloudrun`, `compute`, `vertexai`, `kms`) require a LocalCloud Pro license. The license server validates the request and either enables the facade or refuses registration; `/readiness/{serviceId}` reports `disabled` if no license is present. The repo's tests should skip Pro services unless a license is configured.
- Caddy listens on container ports 443 (HTTPS) and 8081 (HTTP). Host ports 80 and 443 are mapped to it. **Do not start another web server on host ports 80 or 443** while LocalCloud is running — the Caddy reverse proxy is exclusive on those ports.
- `16443:6443` is the k3d HTTPS port for the in-container GKE cluster. The host's 6443 stays free for the user's own k3d/k8s work.
