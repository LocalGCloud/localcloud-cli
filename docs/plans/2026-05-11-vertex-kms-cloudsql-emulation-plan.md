# Vertex AI, KMS, and Cloud SQL Emulation Plan

**Date:** 2026-05-11  
**Status:** Partially implemented. Registry, REST facades, metadata schema, console visibility, and focused tests are implemented. OpenHalo data-plane replacement remains a gated follow-up.  
**Goal:** Add optional LocalCloud support for Vertex AI, Cloud KMS, and Cloud SQL while preserving the current service-registry, gateway, PostgreSQL, supervisord, and console architecture.

## Executive Summary

LocalCloud can add all three services using existing extension points:

- `services.yaml` remains the source of truth for service IDs, ports, env vars, gcloud overrides, Terraform endpoint exports, default enablement, and health behavior.
- Java/Armeria remains the API gateway and facade host.
- PostgreSQL remains the metadata store.
- supervisord remains the runtime process manager.
- Console service lists, toggles, health, browsing, and docs must be updated because several service lists are still hard-coded.

Chosen product direction:

| Service | Direction |
|---|---|
| Vertex AI | GenAI-first emulator for Gemini-compatible REST workflows |
| KMS | Local cryptographic facade backed by Java crypto and PostgreSQL metadata |
| Cloud SQL | Cloud SQL Admin API facade plus shared PostgreSQL/OpenHalo-backed data plane |
| Packaging | New services optional/default-off initially |

Important Cloud SQL constraint: the MySQL-on-Postgres direction requires OpenHalo/Pigsty-style PostgreSQL kernel compatibility, not a normal PostgreSQL 17 extension. OpenHalo is PostgreSQL 14-based and exposes MySQL wire protocol on `3306`. Replacing LocalCloud's existing PostgreSQL 17 runtime with OpenHalo must be proven before implementation proceeds.

## Current LocalCloud Context

Key implementation anchors:

- Registry: `services.yaml`
- Registry loader: `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
- Enablement/defaults: `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- Gateway/facade registration: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- External processes: `supervisord.conf`
- Runtime PostgreSQL: `Dockerfile`, `docker-entrypoint.sh`
- Console hard-coded service lists: `localcloud-console/src/pages/Dashboard.jsx`, `localcloud-console/src/pages/ServiceExplorer.jsx`, `localcloud-console/src/app.jsx`

Graphify note: `graphify-out/GRAPH_REPORT.md` was not present in this checkout when this plan was created, so this plan is grounded in the concrete runtime/config/source files above.

## Decision Log

| Decision | Chosen | Alternatives rejected/deferred |
|---|---|---|
| Vertex AI scope | GenAI-first | Full Vertex AI platform shell is too broad; generic Prediction API can follow later |
| Vertex backend | Default deterministic stub plus optional local model backend | Bundling a model runtime by default would increase image size and startup cost |
| KMS shape | REST facade first, gRPC later | External process adds little value for v1 |
| KMS crypto | Real local crypto, local-only security model | Pure metadata fake would not validate encryption/decryption workflows |
| Cloud SQL Admin API | REST facade for v1 and v1beta4 | gRPC is not the primary developer path here |
| Cloud SQL Postgres | Shared runtime, isolated logical DBs/users | Separate Postgres per instance is heavier |
| Cloud SQL MySQL | OpenHalo-style MySQL wire compatibility on Postgres | Installing MySQL/MariaDB is intentionally out of scope |
| Packaging | Optional/default-off | Enabling by default risks image/runtime instability |

## Implemented Slice

This implementation establishes the platform surface without replacing PostgreSQL:

- Added optional `vertexai`, `kms`, and `cloudsql` service registry entries.
- Added `LOCALCLOUD_ENABLE_VERTEXAI`, `LOCALCLOUD_ENABLE_KMS`, and `LOCALCLOUD_ENABLE_CLOUDSQL` handling.
- Added PostgreSQL metadata tables for Vertex request traces, KMS key hierarchy, and Cloud SQL Admin API resources.
- Added REST facades:
  - Vertex AI: Gemini-style `generateContent`, `streamGenerateContent`, `embedContent`, `countTokens`, `computeTokens`.
  - KMS: key rings, crypto keys, versions, AES-GCM encrypt/decrypt, primary version update, destroy/restore.
  - Cloud SQL: Admin API metadata for instances, databases, users, operations, flags, and tiers.
- Added console service visibility and fallback SQL schemas.

Current Cloud SQL MySQL behavior is intentionally explicit: MySQL instances are marked `OPENHALO_MYSQL_COMPAT` and `CONTROL_PLANE_ONLY_UNTIL_OPENHALO`. This avoids pretending PostgreSQL 17 speaks the MySQL wire protocol.

## Feature Coverage

### Vertex AI

MVP coverage:

- REST endpoints for:
  - `generateContent`
  - `streamGenerateContent`
  - `embedContent`
  - `countTokens`
  - `computeTokens`
- Publisher model paths such as:
  - `/v1/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:generateContent`
- Request/response shape compatible with Gemini-on-Vertex developer flows.
- Deterministic stub responses.
- Request/response traces stored in `vertexai_requests`.

Deferred gaps:

- Ollama/OpenAI-compatible backend execution.
- Model registry, tuning, batch prediction, endpoints deployment.
- RAG, vector search, grounding, code execution, Live API.
- True multimodal fidelity unless the selected local backend supports it.
- Safety policy parity and quota enforcement.

### Cloud KMS

MVP coverage:

- Key rings: create, get, list.
- Crypto keys: create, get, list, primary version metadata.
- Crypto key versions: get, list, destroy, restore.
- Crypto operations:
  - symmetric encrypt/decrypt with AES-GCM
  - local ciphertext header carrying key version
  - optional AAD support
- Local key material stored in PostgreSQL as development-only software keys.

Deferred gaps:

- KMS gRPC binding.
- Asymmetric sign/decrypt, MAC sign/verify, random bytes endpoint.
- HSM, EKM, Autokey, import jobs.
- Real IAM, org/folder policies, audit logs, quota semantics.
- Attestation and production-grade key custody.

### Cloud SQL

MVP coverage:

- Cloud SQL Admin API REST facade for `/sql/v1beta4` and `/sql/v1`:
  - instances
  - databases
  - users
  - operations
  - flags
  - tiers
- Control plane metadata stored in LocalCloud tables.
- MySQL flavor explicitly marked as OpenHalo-dependent until the runtime swap is proven.

Deferred gaps:

- OpenHalo runtime image path.
- Real PostgreSQL data-plane database/user creation.
- MySQL wire protocol on `3306`.
- HA, replicas, failover, backups/PITR, maintenance.
- Private IP, Cloud SQL Auth Proxy parity, SSL cert semantics.
- IAM DB auth.
- Per-instance network isolation.
- Exact MySQL dialect parity beyond OpenHalo's compatibility subset.

## Remaining Implementation Phases

### Phase 0: OpenHalo Feasibility Gate

Do this before claiming Cloud SQL MySQL data-plane support.

- Replace hard-coded PostgreSQL paths with configurable `PG_BIN`, `PG_VERSION`, and `PGDATA`.
- Build an OpenHalo-based runtime image variant.
- Initialize the existing `localcloud` metadata database on OpenHalo.
- Enable PostgreSQL protocol on `5432` and MySQL protocol on `3306`.
- Validate:
  - LocalCloud server starts.
  - Existing schema initialization succeeds.
  - Existing Java tests pass.
  - `psql` can access LocalCloud metadata.
  - MySQL client can connect to `3306`.
  - Existing Docker volume behavior is understood.
  - amd64 and ARM64 packaging are confirmed.
- If PG17 volumes are detected, fail with a clear migration/reinit error. Do not silently reuse incompatible data.

### Phase 1: Vertex Backend Execution

- Add backend abstraction:
  - `VertexBackend`
  - `StubVertexBackend`
  - `OllamaVertexBackend`
  - `OpenAiCompatibleVertexBackend`
- Add env config:
  - `LOCALCLOUD_VERTEX_BACKEND`
  - `LOCALCLOUD_VERTEX_BACKEND_URL`
  - `LOCALCLOUD_VERTEX_MODEL_MAP`
- Convert current deterministic response generation into `StubVertexBackend`.
- Return structured `UNIMPLEMENTED` for unsupported Vertex features.

### Phase 2: KMS API Expansion

- Add KMS proto/gRPC dependencies and binding.
- Add asymmetric key algorithms where Java crypto supports the selected algorithm.
- Add MAC sign/verify.
- Add random bytes endpoint.
- Add get public key for asymmetric key versions.

### Phase 3: Cloud SQL Data Plane

- For Postgres flavor:
  - create isolated physical databases and roles in the shared runtime.
  - map Cloud SQL database names to physical names.
- For MySQL flavor:
  - use OpenHalo's MySQL listener on `3306`.
  - map Cloud SQL databases to OpenHalo-compatible schemas/databases.
  - return connection metadata that explains the physical mapped database/schema.
- Add reset/seed/export support only after basic instance/database/user operations are stable.

### Phase 4: Console UX

- Add dedicated panels:
  - Vertex prompt playground.
  - KMS key/key-version browser.
  - Cloud SQL connection panel.
- Keep the generic Service Explorer SQL view for metadata tables.

## Test Plan

Implemented tests:

- Registry includes 18 services and keeps new services default-off.
- Service gating resolves Vertex/KMS/Cloud SQL paths.
- KMS symmetric encrypt/decrypt round trip.
- Vertex `generateContent` response shape.
- Cloud SQL Admin API metadata lifecycle.
- Cloud SQL MySQL flavor is explicitly OpenHalo-dependent.

Remaining tests:

- Docker startup with `LOCALCLOUD_SERVICES=cloudsql,kms,vertexai`.
- gcloud endpoint override smoke tests.
- Terraform custom endpoint smoke tests where provider support exists.
- OpenHalo image build and PostgreSQL metadata compatibility.
- MySQL client connection once OpenHalo is introduced.

## Risks and Guardrails

- OpenHalo replacement is the highest-risk item. Treat it as a prerequisite experiment, not an implementation detail.
- Do not fake MySQL by only adding PostgreSQL functions if MySQL clients cannot connect on `3306`.
- Do not let Cloud SQL operations mutate LocalCloud metadata tables outside explicit Cloud SQL mappings.
- Keep all three services default-off until Docker build size, startup time, and compatibility are stable.
- Document every unsupported API with explicit `UNIMPLEMENTED` responses.
- Keep KMS clearly labeled as local-development crypto, not production key custody.

## External References

- Vertex AI GenAI REST methods: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/projects.locations.publishers.models
- Vertex AI embedContent: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/projects.locations.publishers.models/embedContent
- Cloud KMS REST API: https://docs.cloud.google.com/kms/docs/reference/rest
- Cloud SQL Admin API: https://docs.cloud.google.com/sql/docs/mysql/admin-api
- OpenHalo kernel: https://pigsty.io/docs/pgsql/kernel/openhalo/
- Pigsty MySQL template: https://pigsty.io/docs/conf/mysql/
