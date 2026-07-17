# LocalCloud Canonical Port Migration Implementation Plan

**Design:** `docs/superpowers/specs/2026-07-17-localcloud-canonical-port-migration-design.md`  
**Cutover:** Atomic and breaking; no legacy listeners or aliases  
**Canonical range:** 24080–24095 active, 24096–24127 reserved

## Port Map

| Service | Old | New |
|---|---:|---:|
| Gateway, API, console, facades | 8080 | 24080 |
| Cloud Storage | 4443 | 24081 |
| Pub/Sub | 8085 | 24082 |
| Firestore | 8086 | 24083 |
| Bigtable | 8087 | 24084 |
| Spanner gRPC | 9010 | 24085 |
| Spanner REST | 9020 | 24086 |
| BigQuery REST | 9050 | 24087 |
| BigQuery gRPC | 9060 | 24088 |
| Memorystore Redis/Valkey | 6379 | 24089 |
| Cloud SQL PostgreSQL | 5432 | 24090 |
| Cloud SQL MySQL | 3306 | 24091 |
| GKE Kubernetes API | 6443 / host 16443 | 24092 |
| LocalCloud DNS | 53 / host 8053 | 24093/udp |
| Caddy HTTPS | 443 | 24094 |
| Caddy HTTP | 8081 / host 80 | 24095 |

## Execution Rules

- Complete tasks in order. Runtime proof precedes documentation cleanup.
- Treat `services.yaml` as authoritative.
- Default Docker mappings bind to `127.0.0.1` and use identical host/container ports.
- Do not introduce port overrides, aliases, or automatic fallback.
- Historical files under `docs/plans/` and archived generated artifacts are not rewritten.
- Do not declare the cutover complete until the built container passes protocol-level smoke tests.

## Task 1: Establish the canonical manifest and consistency validator

**Files**

- Modify: `services.yaml`
- Create: `scripts/validate-port-map.py`
- Modify: the repository CI/build entry point that runs existing static validation

**Changes**

1. Change gateway and service ports to the approved map.
2. Add an `infrastructure` section containing DNS 24093, Caddy HTTPS 24094, and Caddy HTTP 24095.
3. Keep facade services represented as references to the shared gateway, not independent listeners.
4. Implement `scripts/validate-port-map.py` using the repository's existing Python runtime.
5. Validate unique listener assignments, contiguous allocation through 24095, reserved-range bounds, facade sharing, additional ports, and infrastructure entries.
6. Parse and compare the structured or stable duplicated surfaces: application YAML, Docker `EXPOSE`, default `start.sh` mappings, CI Compose mappings, Testcontainers constants, console Docker command, and the current integration-guide port table.
7. Add an explicit historical exclusion list for `docs/plans/`, `_bmad-output/`, `.superpowers/brainstorm/`, and captured browser artifacts. Do not exclude active source, scripts, examples, guides, or current docs.
8. Make the validator print every stale file and expected replacement in one run.

**Checks**

- Run the validator before consumer updates and capture the stale-surface inventory.
- Confirm duplicate listener assignments fail, while facade references to gateway 24080 pass.
- Confirm any active legacy literal fails validation.

## Task 2: Cut over gateway and server configuration

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- Modify: `localcloud-server/src/main/resources/application.yaml`
- Modify: corresponding configuration and registry tests

**Changes**

1. Change the gateway default to 24080.
2. Change the PostgreSQL default to 24090.
3. Update the reference application YAML to the complete canonical map.
4. Ensure `ServiceRegistry` resolves every facade to gateway 24080 and exposes infrastructure listeners without treating them as cloud services.
5. Preserve environment variables only where they configure non-port behavior; remove port override behavior from `LOCALCLOUD_PORT` and `LOCALCLOUD_PG_PORT` if present.
6. Update constructor fixtures and expected endpoint values in registry/configuration tests.

**Checks**

- Run targeted configuration and registry tests.
- Assert shell-style and URL-style endpoint construction for REST, gRPC, Redis, PostgreSQL, and additional ports.

## Task 3: Change all managed process listeners

**Files**

- Modify: `docker/conf/supervisord.conf`
- Modify: `docker/conf/valkey.conf`
- Modify: PostgreSQL startup/configuration files under `docker/`
- Modify: Spanner gateway/wrapper configuration or arguments
- Modify: GKE/k3d listener allocation code

**Changes**

1. Pub/Sub emulator: 24082.
2. Firestore emulator: 24083.
3. Bigtable emulator: 24084.
4. Spanner wrapper/gateway: gRPC 24085 and REST 24086; pass explicit arguments rather than depending on upstream defaults.
5. fake-gcs-server: listener and `-public-host` 24081.
6. BigQuery emulator: REST 24087 and gRPC 24088.
7. Valkey: configure 24089 in `valkey.conf`.
8. PostgreSQL: configure TCP 24090 while preserving Unix-socket startup dependencies.
9. MySQL-compatible listener: configure 24091.
10. GKE/k3d API allocation: begin at 24092 and emit 24092 in connection metadata.
11. Keep supervisor's private control port 9001 unchanged because it is loopback-only, not published, and not part of the LocalCloud service contract.

**Checks**

- Start each managed process in the image environment.
- Verify each enabled process binds only its canonical port.
- Verify PostgreSQL socket-dependent startup still works after the TCP-port change.

## Task 4: Cut over DNS and Caddy infrastructure

**Files**

- Modify: `docker/conf/network/Caddyfile`
- Modify: `docker/conf/network/dnsmasq.conf`
- Modify: `docker/conf/supervisord.conf`
- Modify or create: explicit transparent-network launch profile/configuration
- Modify: security and Terraform readiness scripts

**Changes**

1. Configure dnsmasq to listen on 24093.
2. Configure Caddy HTTPS on 24094 and HTTP on 24095.
3. Change Caddy's upstream to gateway 24080.
4. Keep DNS and Caddy disabled or unpublished in the minimal default profile unless their explicit services/profile are enabled.
5. Add the opt-in compatibility mappings 53/udp→24093, 80→24095, and 443→24094.
6. Add preflight checks for host 53, 80, and 443 before compatibility-mode startup. Abort before container creation if any are occupied.
7. Update Terraform readiness to distinguish custom-endpoint mode from transparent-network mode.

**Checks**

- Standard mode does not occupy host 53, 80, or 443.
- Compatibility mode resolves `*.googleapis.com`, completes TLS, and proxies to 24080.
- Occupied standard ports produce one actionable failure and no partial container.

## Task 5: Remove hard-coded internal endpoint fallbacks

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/GraphQLGateway.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/QueryService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java`
- Modify: Cloud SQL and GKE endpoint-producing classes
- Modify: affected adapter and service tests

**Changes**

1. Replace fallback literals such as Spanner REST 9020 and GCS 4443 with registry lookups.
2. Construct workflow connector URLs from service definitions rather than embedded localhost URLs.
3. Return Cloud SQL PostgreSQL 24090 and MySQL 24091 connection metadata.
4. Return GKE 24092 endpoints and kubeconfig values.
5. Ensure seed, browse, mutation, query, export, GraphQL, and sync paths all use the same registry endpoints.

**Checks**

- Run targeted admin, workflow connector, Cloud SQL, GKE, and adapter tests.
- Exercise representative internal calls against processes bound to the canonical ports.

## Task 6: Update generated endpoint and diagnostic contracts

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/admin/EnvService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/CompatibilityRegistry.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/DiagnosticsService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/McpService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/gateway/HealthCheckService.java`
- Modify: associated tests

**Changes**

1. Ensure every output is derived from `ServiceRegistry`.
2. Update shell, JSON, and Terraform exports.
3. Update compatibility, diagnostics, MCP, health, and readiness endpoint payloads.
4. Add observable contract assertions for gateway 24080, REST prefixes, gRPC host:port values, Spanner's additional port, BigQuery's additional port, and database protocols.
5. Remove tests that instantiate the registry with 8080 or assert legacy endpoints.

**Checks**

- Run targeted admin/gateway tests.
- Start the gateway and query each output format over HTTP.
- Assert no legacy port appears in any response.

## Task 7: Update Docker and local launch behavior

**Files**

- Modify: `Dockerfile`
- Modify: `start.sh`
- Modify: `docker/docker-entrypoint.sh`
- Modify: `docker/wait-for-pg.sh`
- Modify: `build.sh` and active local runtime helpers

**Changes**

1. Replace Docker `EXPOSE` with canonical listener ports.
2. Change Docker `HEALTHCHECK` to 24080.
3. Change default `start.sh` mappings to identical loopback-bound host/container ports.
4. Update entrypoint health, readiness, seed, and Terraform calls.
5. Update PostgreSQL readiness to TCP 24090 while retaining socket readiness where required.
6. Add fixed-port preflight for the default published set. Identify the LocalCloud service assigned to an occupied port and print platform-appropriate ownership commands.
7. Remove comments and examples that advertise old ports.

**Checks**

- Build the Docker image.
- Start via `start.sh`.
- Confirm the container publishes only the expected loopback mappings.
- Confirm startup aborts cleanly when one canonical host port is occupied.

## Task 8: Update CI and Testcontainers integrations

**Files**

- Modify: `ci/templates/docker-compose.localcloud.yml`
- Modify: `ci/templates/github-actions-localcloud.yml`
- Modify: `ci/templates/wait-for-localcloud.sh`
- Modify: `ci/templates/assert-localcloud-coverage.py`
- Modify: `ci/testcontainers/java/LocalCloudContainer.java`
- Modify: Testcontainers examples/tests

**Changes**

1. Replace fixed CI service mappings and health URLs.
2. Replace Testcontainers internal constants with canonical ports.
3. Keep Testcontainers host ports dynamic and obtain them through `getMappedPort()`.
4. Add BigQuery gRPC and any missing additional-port exposure required by endpoint generation.
5. Ensure `endpointEnvironment()` returns mapped host ports, not canonical container ports.

**Checks**

- Run the Testcontainers integration scenario.
- Run the CI Compose readiness sequence locally.
- Verify endpoint environment values reach each mapped service.

## Task 9: Update console and sample applications

**Files**

- Modify: `localcloud-console/src/data/services.js`
- Modify: `localcloud-console/src/pages/Dashboard.jsx`
- Modify: `localcloud-console/src/pages/DataBrowser.jsx`
- Modify: `localcloud-console/src/pages/GetStarted.jsx`
- Modify: `localcloud-console/src/pages/ServiceExplorer.jsx`
- Modify: `localcloud-console/src/pages/settings-data.js`
- Modify: active examples under `examples/`

**Changes**

1. Replace service metadata and direct URLs.
2. Update object-download URLs and setup snippets.
3. Update Docker commands and environment exports.
4. Prefer API-provided endpoint metadata over duplicated literals where the console already receives it.
5. Update sample application defaults to the canonical map.

**Checks**

- Build the console.
- Load it from 24080.
- Exercise dashboard, service explorer, settings copy actions, data-browser direct downloads, and setup commands.

## Task 10: Update Terraform, integration scripts, and native mode

**Files**

- Modify: active scripts under `scripts/`
- Modify: `terraform/test-api-compat.sh` and active Terraform examples/CI fixtures
- Modify: `localcloud-integration-guide/COMMON_GUIDE.md` runtime commands after runtime proof
- Modify: native-mode implementation/configuration and current native-mode guide
- Modify: security test scripts under `docker/conf/security/`

**Changes**

1. Replace all active fixed endpoints and Docker mappings.
2. Ensure Terraform custom endpoints use 24080/24081/24086/24087 as appropriate.
3. Update native-mode process commands to the same canonical ports.
4. Remove the claim that host and container ports use upstream defaults.
5. Make custom endpoints the default recommendation; document transparent networking as opt-in.

**Checks**

- Run Terraform readiness and representative provider compatibility scenarios.
- Run persistence and API compatibility scripts.
- Launch native mode and inspect its listeners.

## Task 11: Run the full container protocol smoke matrix

**Files**

- Create or modify: focused smoke harness under `ci/` or existing integration scripts, following current conventions

**Scenario**

1. Build the image.
2. Launch the normal profile.
3. Verify gateway health and readiness at 24080.
4. Verify enabled listeners and absence of legacy listeners.
5. Verify shell, JSON, and Terraform exports.
6. Exercise GCS on 24081.
7. Exercise Pub/Sub on 24082.
8. Exercise Firestore on 24083.
9. Exercise Bigtable on 24084.
10. Exercise Spanner gRPC/REST on 24085/24086.
11. Exercise BigQuery REST/gRPC on 24087/24088.
12. Exercise Valkey on 24089.
13. Exercise PostgreSQL on 24090.
14. Exercise MySQL on 24091 when available.
15. Exercise GKE on 24092 when licensed and enabled.
16. Exercise one facade through gateway 24080.
17. Exercise seed, browse, query, mutate, export, diagnostics, compatibility, and MCP flows.
18. Launch the compatibility profile and verify DNS/TLS proxying separately.

**Acceptance**

- Every enabled protocol passes against the built image.
- No LocalCloud process listens on 8080, 4443, 8085–8087, 9010, 9020, 9050, 9060, 6379, 5432, 3306, or 6443.
- Standard mode leaves host 53, 80, and 443 free.

## Task 12: Documentation and release cleanup

Run only after Task 11 passes.

**Files**

- Modify: `README.md`
- Modify: `DEVELOPER_GUIDE.md`
- Modify: `CONSOLE_QUICKSTART.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/SERVICE_STATUS.md`
- Modify: `docs/MCP_INTEGRATION.md`
- Modify: current Terraform setup and compatibility docs
- Modify: `localcloud-integration-guide/COMMON_GUIDE.md`
- Modify: every current per-service integration guide
- Modify: active example READMEs and CI examples
- Modify: `RELEASE_NOTES.md`

**Changes**

1. Replace every current endpoint, command, diagram, table, and troubleshooting instruction.
2. Add the complete old-to-new mapping.
3. State that the release is a hard cutover with no aliases or overrides.
4. Explain standard versus transparent-network mode.
5. Update the console URL and environment-export commands.
6. Preserve historical plans without rewriting them.

**Final checks**

- Run the port consistency validator.
- Run the active-path legacy-literal audit.
- Verify current docs contain no old LocalCloud endpoint.
- Confirm the migration table is the only current document section intentionally showing legacy values.

## Completion Evidence

The implementation is complete only with all of the following attached to the change:

- Validator output showing a consistent canonical map.
- Targeted Java test results.
- Console build result.
- Docker image build result.
- Normal-profile protocol smoke result.
- Compatibility-profile DNS/TLS smoke result.
- Listener inventory showing no legacy LocalCloud listeners.
- Final current-documentation audit result.
