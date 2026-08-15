# LocalCloud CLI Consolidation and Safety Implementation Plan

## Objective

Implement `docs/superpowers/specs/2026-08-14-cli-consolidation-safety-design.md` as five ordered, behavior-preserving changes. The purge safety fix lands first; boundary refactors follow only after their focused tests pass.

## Constraints

- Preserve the current working tree and adapt all uncommitted user changes.
- Keep public CLI commands, payloads, ownership rules, and documented errors stable.
- Use a clean internal cutover: migrate every caller and test; retain no aliases.
- Do not remove pre-mutation Docker revalidation.
- Do not run Docker-marked acceptance tests automatically.
- Add no runtime dependency.

## Step 1: Stop purge after child-cleanup failure

Files:

- `src/localcloud_cli/docker_runtime.py`
- `tests/test_docker_runtime.py`

Changes:

1. Add the same immediate `cleanup_failed` barrier used by `remove()` directly after orphan-child cleanup in `purge()`.
2. Add a regression test with an orphaned managed network/volume and malformed child ownership.
3. Assert the error code and prove the child, network, and volume remain untouched.

Focused verification:

```bash
uv run --extra test python -m pytest -q tests/test_docker_runtime.py -k 'purge or child_cleanup'
```

## Step 2: Canonical runtime settings and typed record

Files:

- `src/localcloud_cli/config.py`
- `src/localcloud_cli/docker_runtime.py`
- `src/localcloud_cli/controller.py`
- `tests/test_config.py`
- `tests/test_controller.py`
- `tests/test_docker_runtime.py`
- any focused test fake that constructs runtime records

Changes:

1. Add `runtime_settings(config)` in `config.py`.
2. Make `LocalCloudConfig.config_hash` an `init=False` field derived in `__post_init__`.
3. Replace the controller and Docker copies of the runtime-settings mapping with the canonical encoder.
4. Add frozen `RuntimeRecord` with the approved operational fields and creation flags.
5. Convert Docker lifecycle signatures and internals from dictionary access to record attributes.
6. Use `dataclasses.replace()` for creation flags.
7. Convert controller orchestration and payload serialization to record attributes.
8. Let `_payload()` handle absence without fabricating a Docker dictionary.
9. Narrow `target()` to URL and endpoint map.
10. Remove unused runtime-record fields and migrate test fakes.

Focused verification:

```bash
uv run --extra test python -m pytest -q \
  tests/test_config.py tests/test_controller.py tests/test_docker_runtime.py
```

## Step 3: Filter and snapshot Docker discovery

Files:

- `src/localcloud_cli/docker_runtime.py`
- `tests/test_docker_runtime.py`

Changes:

1. Change container listing to `all=True`, `filters={"volume": data_volume}`, and `sparse=True`.
2. Update the Docker fake collection to honor the volume filter and sparse/list arguments.
3. Reload each returned candidate exactly once.
4. Pass captured labels/attrs through classification and metadata helpers rather than reloading.
5. Retain exact mount, collision, image, and ownership validation.
6. Add tests proving unrelated containers are not inspected and selected candidates still fail closed.

Focused verification:

```bash
uv run --extra test python -m pytest -q tests/test_docker_runtime.py \
  -k 'resolve or collision or mount or ownership'
```

## Step 4: Move scenario support and consolidate Java transport

Files:

- `src/localcloud_cli/java_client.py`
- `tests/test_java_client.py`
- `tests/integration/_support.py`
- `tests/integration/test_full_olap_acceptance.py`
- new `tests/integration/scenario_support.py`
- new `tests/integration/test_scenario_support.py`

Changes:

1. Add one private Java MCP HTTP/JSON decoder shared by `forward()` and `rpc()`.
2. Distinguish connectivity failures from malformed JSON/protocol responses.
3. Preserve notification 202/empty handling and JSON-RPC error translation.
4. Add malformed-object/list response tests.
5. Remove the unused `request()` alias.
6. Move scenario application, checkpoint, verification, and helper logic into test support using `JavaMcpClient.tool()`.
7. Move scenario-specific unit tests with that helper and update acceptance imports/calls.

Focused verification:

```bash
uv run --extra test python -m pytest -q \
  tests/test_java_client.py tests/integration/test_scenario_support.py
```

## Step 5: Consolidate endpoint policy and remove obsolete wrappers

Files:

- `src/localcloud_cli/endpoints.py`
- `src/localcloud_cli/mcp_stdio.py`
- `src/localcloud_cli/controller.py`
- `src/localcloud_cli/docker_runtime.py`
- `src/localcloud_cli/output.py`
- `tests/test_endpoints.py`
- `tests/test_mcp_stdio.py`
- affected controller/runtime/output tests

Changes:

1. Move MCP endpoint classification, recursive transformation, stale-port checks, and public-endpoint validation into `endpoints.py`.
2. Expose one endpoint transformer for MCP results.
3. Keep `mcp_stdio.py` limited to target acquisition, forwarding, transformation, error conversion, and stdio integration.
4. Replace `mcp_target()` with `target()` and remove the no-op release/close path.
5. Remove the `resource_names()` pass-through and migrate callers to `default_resource_names()` where still needed.
6. Remove unused `urlunsplit` and redundant `_EXTRA_DOCTOR` definitions.
7. Move endpoint-policy tests to `test_endpoints.py`; retain adapter integration assertions in `test_mcp_stdio.py`.

Focused verification:

```bash
uv run --extra test python -m pytest -q \
  tests/test_endpoints.py tests/test_mcp_stdio.py tests/test_controller.py \
  tests/test_docker_runtime.py tests/test_output.py
```

## Final verification

```bash
uv run --extra test python -m pytest -q -m 'not docker'
uv run localcloud --help
uv run localcloud guide
```

Then dispatch an independent code reviewer against the final working tree. Fix every Critical and Important issue and rerun the affected focused tests plus the complete non-Docker suite.
