# LocalCloud CLI Consolidation and Safety Design

## Status

Approved in conversation on 2026-08-14.

## Goal

Implement the five reviewed changes in order:

1. prevent destructive purge after child-cleanup failure;
2. centralize runtime-settings serialization and replace the internal runtime dictionary with a typed record;
3. filter Docker discovery by the selected volume and inspect each candidate once;
4. move scenario verification to test support and consolidate Java MCP transport handling;
5. consolidate endpoint policy and remove obsolete internal wrappers.

The refactor must preserve public CLI commands, payloads, ownership rules, and documented error codes except that malformed Java MCP responses will now consistently produce `java_mcp_invalid_response` instead of escaping as raw Python exceptions.

## Non-goals

- Do not split `LocalCloudConfig` into runtime and invocation dataclasses.
- Do not merge `start`, `restart`, or `reset`; their project, seed, and active-record semantics intentionally differ.
- Do not remove repeated pre-mutation ownership revalidation.
- Do not make the guide depend on the test service fixture; the fixture remains an independent contract oracle.
- Do not run Docker acceptance tests automatically. They require a LocalCloud image and mutate Docker resources.

## Architecture

The dependency direction remains:

```text
cli -> controller -> docker_runtime
                 -> java_client
cli -> output
mcp_stdio -> controller, java_client, endpoints
```

`config.py` owns the canonical runtime-settings encoder. `docker_runtime.py` owns Docker discovery, mutation, and the typed runtime record. `controller.py` owns command orchestration and conversion from runtime records to public CLI payloads. `endpoints.py` owns all endpoint rewriting and validation. `mcp_stdio.py` remains a protocol adapter.

No compatibility aliases are retained. Every internal caller and test migrates in the same change.

## Ordered changes

### 1. Purge cleanup barrier

`DockerRuntime.purge()` must treat child cleanup as a prerequisite for every parent-resource mutation.

Flow:

1. Resolve the selected runtime.
2. If a parent exists, retain the existing `remove()` path.
3. For an orphaned runtime, call `_remove_children()`.
4. If it records any failure, raise the existing `cleanup_failed` error immediately.
5. Only after successful child cleanup may managed network and volume removal begin.

The error retains the selected data volume and complete failure list. A regression test must create a managed orphan network and volume plus a malformed managed child, call `purge()`, and prove that neither parent resource was removed.

### 2. Canonical settings and typed runtime record

Add one `runtime_settings(config)` encoder beside `LocalCloudConfig`. It returns the JSON-compatible runtime field mapping currently duplicated in `config.py`, `controller.py`, and `docker_runtime.py`.

`LocalCloudConfig.config_hash` becomes an `init=False` derived field populated in `__post_init__` from that encoder. The same encoder drives:

- configuration hashing;
- `com.localcloud.config` Docker metadata;
- managed drift calculation;
- controller changed-field calculation;
- test fixture construction.

Add a frozen `RuntimeRecord` dataclass in `docker_runtime.py`. It contains only operationally consumed state:

- selection and identity: data volume, origin, ownership, container name and ID;
- health and connection: state, health, gateway URL, endpoint map;
- Docker context: network name, mount, configured/actual image identity;
- recorded configuration: hash, path, runtime settings, services, data mode;
- safety and diagnostics: labels and drift;
- creation outcome flags: volume-created and network-created.

`resolve()` returns `RuntimeRecord | None`. Lifecycle methods accept and return `RuntimeRecord`. Creation flags are applied with `dataclasses.replace()` rather than mutating the record.

Remove record fields that have no production consumer: legacy-ownership summary, full network list, configured-image ID, image labels, and preferred-container-match status. Internal validation still retains any local values needed to enforce ownership and image compatibility.

`Controller` reads record attributes and owns public response serialization. `_payload()` accepts `RuntimeRecord | None` so absent state no longer requires fabricating a Docker-shaped dictionary. `target()` returns only the URL and endpoint map; project and user remain available from `LocalCloudConfig`.

### 3. Filtered, single-inspection Docker discovery

Use the Docker SDK collection API with:

```python
client.containers.list(
    all=True,
    filters={"volume": selected_data_volume},
    sparse=True,
)
```

The Docker `volume` filter limits candidates server-side. `sparse=True` avoids an automatic inspect for every listed object. Each returned candidate is then reloaded exactly once before its mount, state, labels, image, network, and ports are read.

The filter is only a candidate reduction. Existing fail-closed checks remain authoritative:

- the selected named volume must be mounted exactly once;
- the destination must be `/var/lib/localcloud`;
- the mount must be read-write;
- every container using the selected volume participates in collision detection;
- image and ownership compatibility remain unchanged.

Pass the already captured container labels into ownership classification and metadata parsing so those helpers do not reload the same container. Unrelated container inspection failures must no longer break resolution of the selected volume.

### 4. Java transport and scenario test support

Keep `JavaMcpClient` focused on transport plus production project operations.

Add one private MCP POST/decoder path shared by `forward()` and `rpc()`:

- HTTP/connectivity failures become `java_mcp_unavailable`;
- invalid or non-object JSON becomes `java_mcp_invalid_response`;
- `forward()` may return `None` for HTTP 202 or an empty notification response;
- `rpc()` requires a JSON-RPC object and translates its `error` member to `java_mcp_error`;
- `tool()` continues to unwrap structured or textual tool results.

Remove the unused `request()` alias.

Move `apply_scenario`, `checkpoint_project`, `verify_scenario`, and all scenario declaration/expectation helpers out of production code into `tests/integration/scenario_support.py`. The helper calls `JavaMcpClient.tool()` and preserves current scenario validation coverage. Scenario-specific unit tests move to `tests/integration/test_scenario_support.py` rather than testing production transport through test-only methods.

### 5. Endpoint policy and obsolete wrappers

Move MCP endpoint classification, recursive payload transformation, canonical-port detection, and stale/public-endpoint validation into `endpoints.py`. Expose one focused transformer used by `McpAdapter`; keep environment generation in the same endpoint-policy module.

`mcp_stdio.py` retains only:

- runtime target acquisition;
- Java MCP forwarding;
- endpoint transformation of relevant results;
- JSON-RPC error conversion;
- stdio SDK integration.

Remove no-op or redundant internal surfaces and migrate all callers:

- `Controller.mcp_target()` and `Controller.release_mcp_target()`;
- `McpAdapter.close()` and its no-op release path;
- `docker_runtime.resource_names()` pass-through alias;
- the unused `urlunsplit` import;
- redundant `_EXTRA_DOCTOR` field definitions.

No endpoint safety behavior is weakened. Public Google endpoints and stale canonical ports remain hard failures.

## Error handling

The safety boundary is fail closed:

- child-cleanup failure prevents parent deletion;
- selected Docker candidate inspection failure remains an error;
- unrelated containers are excluded before inspection;
- malformed ownership metadata remains non-mutable;
- malformed Java protocol responses become structured host errors;
- endpoint policy continues to reject non-loopback and public-cloud destinations.

Existing errors retain their code and detail payload unless this design explicitly changes malformed Java response handling.

## Testing

Run focused tests after each ordered change:

1. `tests/test_docker_runtime.py` for the purge barrier.
2. `tests/test_config.py`, `tests/test_controller.py`, and `tests/test_docker_runtime.py` for the canonical encoder and record migration.
3. `tests/test_docker_runtime.py` for volume filtering, unrelated-container isolation, collision detection, and one explicit candidate reload.
4. Java transport and scenario-support tests for malformed responses and unchanged scenario verification.
5. `tests/test_endpoints.py` and `tests/test_mcp_stdio.py` for endpoint rewriting and adapter behavior.

Final verification:

- run the complete suite excluding the `docker` marker;
- smoke `localcloud --help` and `localcloud guide` through the installed entry point;
- dispatch an independent code reviewer;
- fix all Critical and Important review findings before delivery.

Docker-marked acceptance tests remain available for an explicitly provisioned environment but are not part of the default refactor verification.
