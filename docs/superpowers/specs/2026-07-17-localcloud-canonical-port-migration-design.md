# LocalCloud Canonical Port Migration Design

**Date:** 2026-07-17  
**Status:** Approved design; implementation planning pending  
**Scope:** Repository-wide hard cutover from legacy LocalCloud ports to one fixed canonical port block

## Problem

LocalCloud currently exposes standard and commonly occupied ports such as 8080, 4443, 6379, 5432, and 3306. Other emulator ports overlap standalone Google emulators or registered services. Port values are repeated across runtime configuration, process launch commands, Java fallbacks, Docker mappings, CI, Testcontainers, the console, integration scripts, generated endpoint APIs, examples, and documentation.

This creates two risks:

1. LocalCloud cannot start when another development service owns one of its fixed host ports.
2. A port migration can drift because no repository guard verifies that duplicated consumers match the canonical service registry.

## Goals

- Give every LocalCloud listener one fixed canonical port in a contiguous, low-collision block.
- Use the same port inside the container, on the default host mapping, and in native mode.
- Update every active runtime, automation, test, example, generated endpoint, console, guide, and current document in one hard cutover.
- Keep `services.yaml` authoritative.
- Add a focused consistency validator so future drift fails CI.
- Avoid standard host ports during normal startup.
- Preserve transparent `*.googleapis.com` interception only as an explicit compatibility mode.

## Non-goals

- No old-port aliases or transition listeners.
- No `LOCALCLOUD_PORT_BASE`.
- No per-service port overrides.
- No automatic fallback to an available port.
- No generated port-artifact framework.
- No rewriting historical design and implementation plans as though their original port values had never existed.
- No default claim on host ports 53, 80, or 443.

## Decisions

### Hard cutover

All active consumers switch in one release. Old listeners and aliases are removed. Startup fails with a direct service-specific diagnostic when a canonical host port is occupied.

### Fixed ports

Ports are not configurable. This keeps support, generated endpoints, documentation, and troubleshooting deterministic.

### Canonical manifest plus validation

`services.yaml` remains the source of truth. Existing language- and runtime-specific consumers are updated directly. A focused validator checks the necessary duplicated surfaces against the manifest. The design does not add generated artifacts.

### Same internal and external ports

Normal Docker mappings use identical host and container ports. Native mode uses the same values. Container isolation is not used as a reason to preserve legacy internal ports.

## Canonical Port Map

| Endpoint | Canonical port | Protocol | Default host mapping |
|---|---:|---|---|
| Gateway, API, console, facade services | 24080 | HTTP, REST, gRPC | `127.0.0.1:24080:24080` |
| Cloud Storage | 24081 | REST | `127.0.0.1:24081:24081` |
| Pub/Sub | 24082 | gRPC | `127.0.0.1:24082:24082` |
| Firestore | 24083 | gRPC | `127.0.0.1:24083:24083` |
| Bigtable | 24084 | gRPC | `127.0.0.1:24084:24084` |
| Spanner data plane | 24085 | gRPC | `127.0.0.1:24085:24085` |
| Spanner control plane | 24086 | REST | `127.0.0.1:24086:24086` |
| BigQuery | 24087 | REST | `127.0.0.1:24087:24087` |
| BigQuery Storage API | 24088 | gRPC | `127.0.0.1:24088:24088` |
| Memorystore Redis/Valkey | 24089 | Redis | `127.0.0.1:24089:24089` |
| Cloud SQL PostgreSQL | 24090 | PostgreSQL | `127.0.0.1:24090:24090` |
| Cloud SQL MySQL | 24091 | MySQL | `127.0.0.1:24091:24091` |
| GKE Kubernetes API | 24092 | HTTPS | `127.0.0.1:24092:24092` |
| LocalCloud DNS | 24093 | DNS/UDP | not published by minimal profile |
| Caddy HTTPS | 24094 | HTTPS | not published by minimal profile |
| Caddy HTTP | 24095 | HTTP | not published by minimal profile |
| Reserved | 24096–24127 | — | — |

The assigned ports 24080–24095 were verified as IANA-unassigned on 2026-07-17 and sit below the default ephemeral ranges used by Linux, macOS, and Windows. This lowers collision risk but does not create an exclusive standards assignment.

## Transparent Network Compatibility Mode

Normal SDK, Terraform, and integration flows use generated custom endpoints and never require standard host ports.

Transparent interception is an explicit opt-in profile. The LocalCloud processes still listen on canonical ports, while Docker adds these boundary mappings:

```text
host 53/udp -> container 24093/udp
host 80     -> container 24095
host 443    -> container 24094
```

Requirements:

- The profile is disabled by default.
- Startup preflight reports which standard port is occupied and aborts without a partial configuration.
- Stopping the profile releases all three standard ports.
- Current documentation recommends custom endpoints before transparent interception.
- These boundary mappings are the only approved host/container port mismatch.

## Configuration Model

### Service registry

`services.yaml` stores:

- Gateway port 24080.
- Each emulator's primary port.
- Spanner and BigQuery additional ports.
- Memorystore, Cloud SQL, and GKE ports.
- Infrastructure listeners for DNS and Caddy.

The Java `ServiceRegistry` resolves facade services to the gateway port and constructs emulator endpoints from the manifest. Internal Java callers use registry definitions instead of fallback literals when the registry is available.

### Process listeners

Every managed process is configured to listen directly on its canonical port:

- Armeria gateway.
- fake-gcs-server, including its advertised public host.
- Pub/Sub emulator.
- Firestore emulator.
- Bigtable emulator.
- Spanner wrapper and REST gateway.
- BigQuery REST and gRPC servers.
- Valkey.
- PostgreSQL and the MySQL compatibility listener.
- k3d/GKE API allocation.
- dnsmasq.
- Caddy HTTP and HTTPS listeners.

Health checks, readiness probes, process dependencies, and startup ordering use the same manifest values.

## Affected Surfaces

### Runtime and container

- `services.yaml`
- Application YAML and environment defaults
- LocalCloud configuration classes and Java fallback values
- Process manager and emulator commands
- Caddy and dnsmasq configuration
- Dockerfile comments, `EXPOSE`, and `HEALTHCHECK`
- Docker entrypoint health and seed calls
- `start.sh`
- Native-mode launch configuration

### Internal consumers

- Gateway startup
- Health and readiness services
- Workflow connector endpoints
- Browse, query, mutation, seed, export, and GraphQL services
- Synchronization adapters
- Diagnostics, compatibility, MCP, and endpoint-export services
- Cloud SQL connection metadata
- GKE kubeconfig and API endpoint generation

### Distribution and automation

- CI Docker Compose template
- GitHub Actions service template
- Testcontainers helper
- Persistence, compatibility, security, and integration scripts
- Terraform examples and CI fixtures
- Sample applications

Testcontainers may continue to allocate random host ports. Its clients must derive them through `getMappedPort()`. The fixed-port requirement applies to the published LocalCloud runtime contract, not isolated Testcontainers execution.

### Console and generated output

- Console service metadata
- Dashboard, data browser, service explorer, and settings commands
- Download and direct-service URLs
- `/env?format=shell`
- `/env?format=json`
- `/env?format=terraform`
- `/services`
- `/compatibility`
- `/diagnostics`
- MCP service inventory and environment output
- Readiness endpoint payloads

### Current documentation

- README and developer guide
- Architecture and service-status documents
- Terraform setup and compatibility guides
- MCP integration guide
- Native-mode documentation
- Common integration guide and every per-service guide
- CI and Testcontainers examples
- Troubleshooting commands
- Release notes and the breaking-change migration table

Historical plans remain unchanged and are excluded from the permanent legacy-literal check.

## Consistency Validator

The repository adds one focused validator that loads `services.yaml` and checks the duplicated distribution surfaces that cannot consume it directly.

It verifies:

1. Every distinct listener assignment is unique; facade services reference the shared gateway listener instead of claiming separate ports.
2. Every canonical listener port is inside 24080–24127.
3. Active assignments are contiguous from 24080 through 24095.
4. Gateway and infrastructure listeners are present.
5. Application configuration matches the manifest.
6. Docker `EXPOSE` matches the active listener set.
7. Default `start.sh` mappings match enabled default services.
8. CI Compose mappings match their configured service set.
9. Testcontainers exposes the correct container ports and derives mapped host ports.
10. Console setup commands match the manifest.
11. The common integration guide's current fixed-port table matches the manifest.
12. Legacy LocalCloud port literals do not remain in active runtime, test, example, guide, or current documentation paths.

The validator has an explicit historical-path exclusion list. Any new exclusion requires a code-review-visible change; it cannot silently ignore an entire source or documentation tree.

## Implementation Sequence

### Phase 1: Manifest and validator

- Apply the canonical map to `services.yaml`.
- Add infrastructure listeners.
- Add validator coverage for manifest invariants and known duplicated surfaces.
- Run the validator to produce the complete stale-consumer inventory.

### Phase 2: Runtime listeners

- Change the gateway and every managed process listener.
- Change Caddy and dnsmasq listeners.
- Change health checks, advertised addresses, and startup probes.
- Remove internal Java fallback literals in favor of registry resolution.

### Phase 3: Runtime consumers

- Update internal clients and service adapters.
- Update generated endpoint APIs.
- Update Cloud SQL and GKE connection metadata.
- Confirm no process listens on a legacy port.

### Phase 4: Distribution surfaces

- Update Docker, start scripts, CI, Testcontainers, native mode, Terraform fixtures, and examples.
- Add the optional transparent-network profile mappings.

### Phase 5: Runtime proof

- Build and launch the actual container.
- Exercise every enabled protocol and generated endpoint path.
- Exercise the optional compatibility profile separately.

### Phase 6: Documentation cleanup

Only after runtime verification succeeds:

- Update current docs and guides.
- Add release notes and the old-to-new migration table.
- Run the consistency validator and final legacy-literal audit.

## Verification

### Static and build checks

- Port consistency validator passes.
- Java build and targeted tests pass.
- Console build passes.
- Shell and configuration syntax checks pass.
- Docker image builds successfully.

The graph currently reports no direct test coverage for the central gateway, registry, environment-export, and health-check surfaces. The implementation must add observable endpoint coverage for the changed contracts rather than relying only on existing unit tests.

### Runtime smoke test

Launch the actual container and verify:

1. `GET http://127.0.0.1:24080/health` succeeds.
2. Readiness reports every enabled service healthy.
3. Expected listeners for the enabled services exist on 24080–24092; optional Cloud SQL and GKE listeners appear only when those services are enabled.
4. Ports 24093–24095 appear only when the corresponding DNS/Caddy profile is enabled.
5. No LocalCloud process listens on 8080, 4443, 8085–8087, 9010, 9020, 9050, 9060, 6379, 5432, 3306, or 6443.
6. Shell, JSON, and Terraform endpoint exports contain only canonical ports.
7. The console loads from port 24080.
8. A representative facade service succeeds through the gateway.
9. Seed, browse, query, mutate, export, diagnostics, compatibility, and MCP flows resolve canonical endpoints.
10. GCS bucket/object operations succeed on 24081.
11. Pub/Sub operations succeed on 24082.
12. Firestore operations succeed on 24083.
13. Bigtable operations succeed on 24084.
14. Spanner gRPC and REST operations succeed on 24085 and 24086.
15. BigQuery REST and gRPC operations succeed on 24087 and 24088.
16. Valkey responds on 24089.
17. PostgreSQL connects on 24090.
18. The MySQL-compatible listener connects on 24091 when available.
19. The GKE API responds on 24092 when licensed and enabled.

### Compatibility-mode smoke test

- Standard mode does not bind host 53, 80, or 443.
- Compatibility mode maps those host ports to 24093–24095.
- DNS resolution reaches LocalCloud.
- HTTPS reaches Caddy and proxies to gateway 24080.
- Occupied standard ports produce an actionable preflight failure.
- Stopping the profile releases all standard ports.

## Failure Behavior

If a canonical host port is occupied, startup fails before creating a partially usable LocalCloud environment. The error identifies:

- The occupied port.
- The LocalCloud service assigned to it.
- The fact that ports are fixed.
- A command suitable for finding the owning process on the current platform.

Optional compatibility mode performs the same preflight for 53, 80, and 443.

## Release and Migration Contract

This is one atomic breaking release. Release notes include:

- The full old-to-new mapping.
- The new gateway URL.
- Updated environment export command.
- Updated Docker and Compose commands.
- The absence of aliases.
- The opt-in transparent-network behavior.

Users migrate once by updating endpoints or regenerating them from `/env` on port 24080.

## Acceptance Criteria

The migration is complete when:

- `services.yaml` contains the approved canonical map.
- Every active listener uses that map internally and in native mode.
- Default Docker host and container ports match.
- All generated endpoints use canonical values.
- Active code, tests, scripts, examples, guides, and current documentation contain no legacy LocalCloud port references.
- The consistency validator passes.
- The actual container smoke test exercises every enabled protocol.
- Optional transparent mode is isolated and disabled by default.
- No old-port alias or override remains.
