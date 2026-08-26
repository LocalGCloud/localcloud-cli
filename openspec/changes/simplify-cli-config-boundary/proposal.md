## Why

The CLI currently duplicates parts of LocalCloud server configuration validation and service interpretation, coupling independently released repositories and creating drift from the Docker/Java authority. The CLI should facilitate host/container management, pass the shared YAML unchanged, and rely on LocalCloud for effective server behavior.

## What Changes

- Define explicit section ownership for the shared `localcloud.yaml`: the CLI owns `host`, reads `version` and client `context`, and treats `server`, `services`, and `infrastructure` as Java-owned pass-through content.
- Keep strict YAML syntax checks and semantic validation for CLI-owned host settings.
- Stop loading, merging, normalizing, or semantically rejecting Java-owned server/service/infrastructure fields.
- Preserve raw Java-owned fields when mounting the exact file read-only into the container.
- Keep canonical `LOCALCLOUD_CONFIG` injection and image `com.localcloud.config-schema` capability gating.
- Keep server configuration content and secrets out of runtime hashes, labels, and generated container environment.
- Obtain effective service/runtime state from LocalCloud APIs after startup rather than predicting it from raw YAML.
- Rename and reshape the stale `services.yaml` release-contract fixture to the canonical `localcloud.defaults.yaml` / `services.catalog` form.
- Preserve independent CLI packaging and operation without a Java installation, LocalCloud source checkout, or pre-creation image execution.

## Capabilities

### New Capabilities

- `config-facilitation`: Host-owned config interpretation, raw Java-owned pass-through, read-only mounting, capability gating, and server-sourced effective runtime status.

### Modified Capabilities

None.

## Impact

- Affects `src/localcloud_cli/config.py`, `docker_runtime.py`, controller/status integration, config fixtures, tests, README/help, release packaging checks, and CLI release CI.
- Removes duplicated service-catalog/default-resolution assumptions from the CLI but does not change public YAML version 1.
- Coordinates with LocalCloud change `centralize-config-resolution-in-java`; compatibility remains based on the public YAML and image schema label rather than source-level or runtime repository dependencies.
