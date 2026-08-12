# Remove CLI Workspaces and Share the Default LocalCloud Instance

Date: 2026-08-12
Status: Approved design

## Goal

Remove workspace-derived identity from the LocalCloud CLI. A zero-configuration invocation must operate on one shared LocalCloud Docker instance and the default GCP project `local-gcp-project`. Projects and caller identities remain independently selectable without creating another Docker stack. Users can request explicit Docker isolation with a named instance.

## Non-goals

- Do not derive Docker resources from a project ID.
- Do not retain workspace aliases, path hashes, labels, environment variables, or compatibility shims.
- Do not expose standard Google service API specifications through the new MCP management catalog.
- Do not adopt or delete existing workspace-labelled Docker resources automatically.

## Identity model

LocalCloud has three independent identities.

### Instance

An instance identifies Docker infrastructure only. The unnamed default instance uses:

- container: `localcloud`
- network: `localcloud`
- volume: `localcloud-data`
- lifecycle lock: `localcloud.lock`

`--instance NAME` selects an isolated deterministic resource set. Instance names must match `[a-z0-9][a-z0-9._-]{0,62}`. A named instance uses container and network `localcloud-<name>`, volume `localcloud-data-<name>`, and lock `localcloud-<name>.lock`. Advanced `container_name`, `network_name`, and `volume_name` overrides are accepted through CLI/config. Managed resources carry stable managed, instance, and resource-role labels. Lifecycle lookup uses the instance label, allowing custom names without host-side metadata.

The implementation removes workspace paths, workspace hashes, workspace labels, `LOCALCLOUD_WORKSPACE`, `LOCALCLOUD_WORKSPACE_KEY`, and controller-UID ownership. Access to the local Docker daemon is already the effective trust boundary, so UID labels must not prevent intentional sharing.

### Project

A project identifies logical GCP data inside an instance. The default is `local-gcp-project` everywhere, including the Java server bootstrap. `--project-id ID` overrides YAML `project:`, which overrides the default. Starting against a selected project idempotently creates it through the existing LocalCloud project API.

Project selection is not part of Docker resource naming or the instance configuration hash. Changing project alone must not recreate the container. The selected project is carried through LocalCloud headers, MCP arguments, generated environment configuration, and seed/reset operations.

### Caller

A caller identifies the developer or agent performing an operation. The default username is `local-developer`. `--user NAME` overrides YAML `user:`, which overrides the default. A simple username maps to `<name>@localcloud.invalid` where an email-shaped Google principal is required; an explicitly supplied email remains unchanged.

Caller selection is not part of Docker identity or the instance configuration hash.

## Configuration

The current directory remains a configuration source, never an identity source.

For commands that load configuration, precedence is:

1. CLI options and explicit positional config path
2. `./localcloud.yaml`
3. the instance's remembered config path
4. built-in defaults

Relative seed paths resolve beside the selected config file. Without a config file, `seed: auto` checks `./seed.yaml`. The former `localcloud-agent.yaml` name is removed in a clean cutover.

Instance-wide settings include enabled services, image, memory, persistence mode, Docker socket access, transparent networking, environment variables, and explicit Docker resource names. Project, caller, and seed are request/session settings and are excluded from the Docker configuration hash.
Container labels remember instance-wide configuration and its source path, but never represent an active project or caller. Command results report the invocation's selected project and caller separately from instance state.

If an invocation changes instance-wide settings for a running shared instance, LocalCloud performs its existing safe replacement flow, preserves a persistent volume, and returns `reconfigured` with the changed fields.

## Command behavior

All lifecycle commands remove `--workspace` and `LOCALCLOUD_WORKSPACE` support.

- `localcloud start [CONFIG]` resolves instance/project/caller, starts or reconfigures the instance, ensures the project exists, and seeds it when required.
- `localcloud restart [CONFIG]` is instance-scoped and restores runtime readiness while retaining the selected project context for volatile reseeding.
- `localcloud stop`, `status`, and `logs` accept instance selection only.
- `localcloud console`, `env`, and `mcp` accept instance/project/caller selectors and target the selected project and caller.
- `localcloud reset [CONFIG]` starts the instance if necessary, resets only the selected project, and reapplies its configured seed.
- `localcloud reset [CONFIG] --all-projects` performs the former whole-instance data reset and recreates the instance volume.

Every instance command accepts `--instance`. `start`, `restart`, `reset`, `console`, `env`, and `mcp` accept `--project-id` and `--user`. CLI project/caller options override YAML context. Advanced Docker resource-name flags apply only to configuration-loading lifecycle commands; later lookup uses instance labels.

With `data: ephemeral`, stopping an instance removes that instance's container, network, and volume. This remains an explicitly instance-wide policy.

Starting a second project on the same instance must leave the container, network, and volume unchanged. Starting a named instance must create a separate deterministic set.

## Project creation and seeding

The Java server bootstraps `local-gcp-project`. During `start`, the CLI checks the project catalog after instance readiness and calls the idempotent `localcloud_create_project` tool for any absent selected project, including an explicit project. Other context-selecting commands do not silently mutate project metadata; they return a structured unknown-project error directing the caller to `localcloud start --project-id ID`.

A project-creation failure leaves a healthy instance running and returns a structured project error. It never rolls back or removes shared Docker resources.

A newly created project may receive the selected seed. An already-running, existing project is not implicitly reseeded by repeated `start`; project-scoped `reset` is the explicit reseed operation. Runtime restart may reapply only the volatile seed subset required by in-memory emulators.

## Docker ownership and locking

Names are deterministic for the default and named instances. Instance names and explicit Docker resource names are validated before Docker access.

The CLI fails closed when:

- a deterministic or explicit name is occupied by an unmanaged resource;
- multiple managed containers claim the same instance label;
- labels identify a different instance or resource role;
- an instance or resource name is invalid.

One host lock per instance serializes lifecycle and reconfiguration operations. Project mutations do not create separate host locks.

`doctor` reports legacy workspace-labelled containers, networks, volumes, and locks with manual cleanup guidance. It never adopts or deletes them.

## Agent guide

`localcloud guide` remains offline, Docker-free, and side-effect free. It uses workspace-free commands and `localcloud.yaml`.

The YAML example contains every canonical service ID from `services.yaml`:

- default-enabled services are active list entries;
- default-disabled services are commented entries with text such as `# - gke  # deactivated service: GKE`;
- each entry includes the display name.

A contract test compares the guide inventory with the canonical service registry to prevent drift. The guide explains default instance/project/caller behavior, explicit isolation, project creation, project-scoped reset, and the MCP API-catalog-first workflow.

## MCP LocalCloud management API coverage

The checked-in LocalCloud OpenAPI contract remains the source of truth. Its generated operation records gain an `x-localcloud-safety` value of `read`, `write`, or `destructive`. MCP adds:

- `localcloud://api/catalog`: concise management operation index;
- `localcloud://api/openapi`: the management OpenAPI document;
- `localcloud_get_api_catalog`: searchable tool access to the same catalog by text, method, path, or safety level;
- `localcloud_call_api`: guarded execution of a documented LocalCloud management operation.

Catalog rows include operation ID, method, path template, summary, parameters, request content types, response content types, and safety level so an agent can select and form a request without guessing.

The management catalog includes every operation classified as a LocalCloud management API by the generated contract. It excludes standard Google service APIs, OAuth endpoints, metadata endpoints, API-document files, `/mcp` recursion, console/static routes, and arbitrary raw routes. Existing service discovery continues to report local Google-compatible service endpoints and compatibility metadata.

The guarded caller requires an `operation_id` and accepts typed path parameters, query parameters, an optional allowed-header map, and an optional JSON or text body. It resolves the method and path only from the catalog; callers cannot supply a scheme, host, traversal path, or alternate method. It rejects undocumented parameters and identity-header overrides. The matched operation's generated safety value is enforced through existing `LOCALCLOUD_MCP_WRITE` and `LOCALCLOUD_MCP_DESTRUCTIVE` settings.

The caller injects selected project and caller identity. Its result includes operation ID, status, content type, a safe response-header allowlist, and parsed JSON, text, or base64-encoded binary content. Existing server request/response limits remain authoritative, and the MCP result reports truncation rather than returning an unbounded payload.

## Caller propagation and audit attribution

The canonical LocalCloud caller header is `X-LocalCloud-User`.

- CLI Java/MCP clients send it.
- The stdio MCP bridge carries its selected caller on every forwarded request.
- The guarded API caller injects it and prevents caller override through arbitrary headers.
- Generated local environment configuration includes `LOCALCLOUD_USER`, `LOCALCLOUD_PRINCIPAL`, and a deterministic `localcloud-user.<base64url-principal>` bearer token for SDK transports that honor access-token configuration.

Request logs add `user` and canonical `principal` fields. Resolution order is explicit LocalCloud caller header, a supported local bearer/basic authorization identity, then `local-developer`. OAuth userinfo/tokeninfo return the resolved local principal when the request can carry it. Genuine service-account fields remain service-account identities.

No claim is made that every external emulator records user identity internally. Attribution is required in the LocalCloud gateway request/audit log and in emulated Google fields that already model a caller or principal.

## Failure behavior

- Invalid instance, project, caller, config, seed, or Docker names fail before mutation.
- Shared-instance reconfiguration is lock-protected and preserves persistent data.
- Project reset failures never fall back to deleting the shared volume.
- API-catalog and guarded-call errors use structured MCP errors with the matched operation and required safety level when available.
- Legacy workspace resources remain untouched.

## Verification

### Python contracts

- CLI help/parser and outputs contain no workspace surface.
- Config precedence and `localcloud.yaml` discovery work independently of identity.
- Default and named resource names, labels, locking, custom-name lookup, collisions, and concurrency are deterministic.
- Default project/caller values and CLI/YAML precedence are correct.
- Project selection creates missing projects without replacing the instance.
- Project-scoped reset preserves other projects; `--all-projects` performs explicit instance reset.
- Guide service IDs exactly match `services.yaml` and default-disabled entries are commented.
- MCP bridge propagates project/caller context and rewrites endpoints.

### Java contracts

- Server bootstrap uses `local-gcp-project`.
- Management catalog exposes all intended LocalCloud operations and excludes standard Google/OAuth/metadata routes.
- Guarded API calls enforce route, method, project/caller injection, and safety gates.
- Request log entries expose user/principal attribution.
- OAuth identity responses honor resolvable local caller identity.
- Generated API/MCP contracts remain current.

### Smoke scenarios

1. Run workspace-free help and guide.
2. Start the default instance and observe `local-gcp-project`.
3. Create/select a second project and confirm identical Docker resources.
4. Start a named instance and confirm separate Docker resources.
5. Discover the LocalCloud management catalog and execute a guarded API request through MCP.
6. Perform an attributed request and observe its user/principal in recent requests.

## Acceptance criteria

- No active CLI code, help, guide, test, or generated command contains the workspace concept.
- Zero-config commands share one fixed Docker instance and `local-gcp-project`.
- Optional project IDs are created and selected without Docker isolation.
- Optional named instances provide explicit Docker isolation.
- Local YAML configuration still controls startup behavior without defining identity by path.
- The guide names every enableable service and clearly comments default-disabled services.
- MCP exposes and can safely call the complete LocalCloud management API surface without publishing standard Google service specifications.
- LocalCloud request/audit output attributes operations to the selected caller, defaulting to `local-developer`.
