# LocalCloud Data-Volume Runtime Adoption Implementation Plan

## Objective

Implement `docs/superpowers/specs/2026-08-13-data-volume-runtime-adoption-design.md` across the LocalCloud server image, host CLI, release workflow, and public documentation.

The finished CLI must discover a LocalCloud runtime by the named Docker volume mounted at `/var/lib/localcloud`, safely operate a compatible container created by another tool, and expose no separate LocalCloud instance selector.

## Repository and rollout dependency

This is a coordinated change across three repositories:

1. `LocalGCloud/localcloud` — change embedded Docker-child ownership from instance to data volume and publish a capable image.
2. `LocalGCloud/localcloud-cli` — implement storage-first discovery, safe attachment, active-runtime state, and the command/config cutover.
3. `LocalStack-Google/localcloud-site` — update the public contract, generated agent context, user documentation, and installer-facing verification.

The server/image contract lands first. The new image must advertise:

```text
com.localcloud.runtime-ownership=data-volume-v1
```

The CLI may attach to an older compatible container, but managed creation or replacement must require that capability. Release the CLI only after a qualified image with the label is available.

## Constraints

- Support Docker named volumes only. Do not add bind-path or anonymous-volume selection.
- Remove `--instance`, `instance`, `--volume-name`, and `volume_name`; do not add aliases or deprecation shims.
- Add `--data-volume` and `data_volume`, defaulting to `localcloud-data`.
- Do not rename Google Cloud resource concepts such as Spanner, Bigtable, AlloyDB, or Cloud SQL instances. Only the host CLI's LocalCloud runtime identity is removed.
- Keep project and user as request context; they must not affect Docker discovery.
- Never relabel, remove, replace, or upgrade an attached container. Never remove an attached network or volume.
- Preserve `mcp` stdout as protocol-only JSON-RPC and keep generated MCP commands canonical as `localcloud`, not `lc`.
- Preserve existing output/logo and alias work in the current working trees; adapt it rather than reverting it.
- Add no runtime dependency. Use the standard library and the existing Docker SDK.
- Keep the current loopback endpoint and fail-closed ownership protections.

## Files

### LocalCloud server/image repository

- Update `localcloud-server/src/main/java/com/localcloud/runtime/RuntimeOwnership.java`.
- Update the runtime-ownership use in `localcloud-server/src/main/java/com/localcloud/emulators/dataproc/DataprocEmulator.java`.
- Retain the generic propagation in `localcloud-server/src/main/java/com/localcloud/runtime/DockerRuntimeProvider.java`, but make its labels come from the renamed ownership record.
- Update `localcloud-server/src/test/java/com/localcloud/runtime/DockerRuntimeOwnershipTest.java`.
- Update `localcloud-server/src/test/java/com/localcloud/emulators/dataproc/DataprocRuntimeOwnershipTest.java` and the ownership assertions in `DataprocEmulatorEndpointTest.java`.
- Update `Dockerfile` with the capability label.
- Extend `scripts/validate-port-map.py` to require the label from the built image contract.

### Host CLI repository

- Rework `src/localcloud_cli/config.py` for `data_volume`, deterministic resource names, active-runtime state, and volume-keyed locks.
- Rework `src/localcloud_cli/docker_runtime.py` for mount-based discovery, resource-level ownership, external lifecycle operations, capability validation, and legacy-child cleanup.
- Rework `src/localcloud_cli/controller.py` so every runtime operation receives a full `LocalCloudConfig`, uses one resolver, and updates active state only after successful start/restart.
- Rework `src/localcloud_cli/cli.py` to remove instance options and route all runtime commands through configuration and `--data-volume`.
- Rework `src/localcloud_cli/mcp_stdio.py` to pin a data volume.
- Update `src/localcloud_cli/output.py` to render data-volume identity, origin, ownership, container ID, and image details instead of LocalCloud instance fields.
- Update `src/localcloud_cli/agent_guide.py` for the volume-first mental model and multi-project behavior.
- Update focused unit tests in `tests/test_config.py`, `test_docker_runtime.py`, `test_controller.py`, `test_cli.py`, `test_mcp_stdio.py`, `test_output.py`, and `test_agent_guide.py`.
- Update Docker integration support in `tests/integration/_support.py` and existing workflows that create isolated runtimes.
- Add `tests/integration/test_external_runtime_adoption.py` for the new end-to-end contract rather than overloading unrelated agent workflow assertions.
- Update `README.md`, `RELEASING.md`, `.github/workflows/cli-release.yml`, and release-packaging assertions where the public command contract is embedded.

### Public website repository

- Replace `product.defaultInstance` with `product.defaultDataVolume` in `src/data/docs-contract.snapshot.json`, `src/data/docs-contract.ts`, and the contract verifier.
- Update `scripts/generate-distributed-docs.mjs` and regenerate `public/llms.txt` and `public/llms-full.txt`.
- Update LocalCloud-runtime wording in `src/components/InstallationMethods.astro`, `src/pages/index.astro`, `src/pages/gcp-integration-testing.astro`, `src/pages/how-to-run-google-cloud-locally.astro`, and relevant `src/pages/docs/*.mdx` files.
- Extend `scripts/verify-cli-docs.mjs` or the closest existing contract verifier to reject obsolete LocalCloud selectors and require `--data-volume` examples.
- Leave genuine Google Cloud service-instance terminology unchanged.

## Step 1: Cut over server-side child ownership

Start in `LocalGCloud/localcloud` so the image capability exists before the CLI can create new managed runtimes.

1. Change `RuntimeOwnership` to carry `dataVolume` instead of `instance`.
2. Replace:
   - environment `LOCALCLOUD_INSTANCE` with `LOCALCLOUD_DATA_VOLUME`;
   - label `com.localcloud.instance` with the existing `com.localcloud.volume-name`.
3. Keep `com.localcloud.managed` and `com.localcloud.config-hash` unchanged.
4. Make `verifyNetwork()` require the exact data-volume and configuration-hash labels on the configured runtime network.
5. Update Dataproc child-container naming to derive its ownership component from `dataVolume()`; retain project, region, and cluster segments.
6. Let `DockerRuntimeProvider.childLabels()` and Dataproc cluster labels inherit the new map without introducing a second label path.
7. Add `com.localcloud.runtime-ownership="data-volume-v1"` to the common runtime image layer so both core and full variants inherit it.
8. Add or update focused tests proving:
   - environment parsing rejects a missing data-volume value;
   - child labels contain volume/config ownership and no instance label;
   - network verification rejects a different volume or config hash;
   - Dataproc names and cleanup use the data-volume value;
   - unrelated Bigtable/Spanner `instance()` APIs are untouched.

Focused verification:

```bash
cd ../localcloud/localcloud-server
./gradlew test \
  --tests com.localcloud.runtime.DockerRuntimeOwnershipTest \
  --tests com.localcloud.emulators.dataproc.DataprocRuntimeOwnershipTest \
  --tests com.localcloud.emulators.dataproc.DataprocEmulatorEndpointTest
cd ..
python3 scripts/validate-port-map.py
```

## Step 2: Replace CLI identity and add active-runtime state

In `config.py`, make volume identity explicit before changing Docker behavior.

1. Replace `DEFAULT_INSTANCE` with `DEFAULT_DATA_VOLUME = "localcloud-data"`.
2. Replace `LocalCloudConfig.instance` and `.volume_name` with `.data_volume`.
3. Remove `validate_instance()` and add strict Docker-volume validation under the new field name.
4. Replace `default_resource_names(instance)` with deterministic names derived from `data_volume`:
   - default volume -> `localcloud` container/network;
   - `localcloud-data-SUFFIX` -> `localcloud-SUFFIX` when valid;
   - all other names -> `localcloud-volume-<sha256-prefix>`.
5. Replace `instance_lock()` with `data_volume_lock()` using a digest-based lock filename so long or unusual valid volume names cannot collide or escape the lock directory.
6. Add an immutable `ActiveRuntime` record containing schema version, data volume, configured image, and container ID.
7. Add load/save helpers under `HostPaths`:
   - missing state returns no active runtime;
   - malformed or unknown-schema state produces a structured diagnostic;
   - writes use a temporary file, flush/fsync, `os.replace`, and a separate global state lock;
   - no state function grants Docker ownership.
8. Extend `load_config()` so selection follows the approved precedence. The active image is used only when the active volume is selected and neither config nor `LOCALCLOUD_IMAGE` supplies an image.
9. Reject legacy config keys with targeted recovery details rather than a generic unknown-field message.
10. Keep project, user, seed, and service configuration out of Docker identity exactly as today.

Tests defend default/custom naming, selector precedence, active image scoping, state corruption, atomic replacement, lock isolation, and exact migration errors.

## Step 3: Implement storage-first Docker discovery

Refactor `DockerRuntime` around one data-volume resolver. Do not bolt a fallback onto the label-first `inspect(instance)` path.

1. Introduce a resolver accepting `LocalCloudConfig` and an optional preferred container ID.
2. Inspect the selected Docker volume, then list running and stopped containers and examine `Mounts`.
3. Track every container using the named volume. A compatible candidate must mount it exactly once, read-write, at `/var/lib/localcloud`.
4. Fail with dedicated `HostError` codes and details for:
   - more than one volume user;
   - wrong destination or read-only use;
   - incompatible image occupying the volume;
   - missing runtime when the caller requires one.
5. Compare normalized declared image references and immutable image IDs. Never pull an image merely to discover a container.
6. Treat the preferred container ID as a hint only; re-fetch and validate it, and never let it bypass a collision.
7. Build a common runtime record containing:
   - `data_volume`;
   - `origin` and resource-level ownership;
   - container name, immutable ID, state, image reference/ID, mount, health, and endpoint map;
   - managed config metadata when valid;
   - inspectable drift for attached containers.
8. Classify ownership per resource:
   - valid managed/role/volume labels -> managed;
   - no managed claim -> attached;
   - partial or contradictory managed metadata -> fail closed, never silently downgrade.
9. Preserve old CLI-managed parents by accepting their existing volume metadata while ignoring the old instance label for current selection.
10. Remove the unlabeled-default-volume deletion exception. An unlabeled volume is attached.
11. Rework create/preflight behavior:
    - create and label a missing volume;
    - reuse but never claim an existing unlabeled volume;
    - derive/create a managed network and container;
    - require `com.localcloud.runtime-ownership=data-volume-v1` before managed create or replacement;
    - allow safe attachment/restart of older compatible images without that capability.
    - export `LOCALCLOUD_DATA_VOLUME` to managed containers and emit no `LOCALCLOUD_INSTANCE`.
12. Add start/restart/stop operations that re-fetch by immutable ID and validate volume/image immediately before mutation. Label ownership is required for removal/replacement, not for safe lifecycle control.
13. Rework removal and rollback to act on each managed resource independently and preserve every attached resource.
14. Change embedded-child cleanup to prefer `com.localcloud.volume-name=<selected>` plus the existing child marker. Permit old instance/config-hash discovery only for children of a fully managed legacy parent.
15. Extend `doctor()` with stale active state, volume collisions, incompatible users, and malformed ownership metadata.

Update the Docker test fakes to model container IDs, `Config.Image`, immutable image IDs, mounts, published ports, resource labels, and state transitions. Tests must demonstrate both positive attachment and every fail-closed branch; source-text assertions are insufficient.

## Step 4: Apply ownership-aware lifecycle orchestration

Refactor `Controller` after the runtime resolver is stable.

1. Make `start`, `restart`, `reset`, `stop`, `status`, `logs`, `target`, `mcp_target`, and release methods accept or derive from `LocalCloudConfig`; remove instance-only signatures.
2. Centralize active-state lookup and pass the preferred ID only when its recorded volume matches the selected config.
3. Acquire the data-volume lock for every mutation and perform discovery inside the lock.
4. Implement the three ownership cases from the design:
   - fully managed;
   - managed container over attached volume;
   - attached container.
5. Preserve idempotence:
   - running compatible container -> `already_running`;
   - stopped compatible container -> start same ID;
   - already stopped -> no-op stop result.
6. Require gateway publication, `/health`, and selected-project readiness before successful start/restart or active-state update.
7. On attached-container readiness failure, report the resulting state without rollback. On mixed ownership, roll back only resources created by the failed operation.
8. Permit selected-project reset through the API for all healthy runtimes.
9. Permit `reset --all-projects` only when every resource required by recreation is managed; otherwise fail before Docker mutation.
10. Replace config-drift comparison with runtime-config comparison that omits project/user and treats data-volume changes as selecting another runtime.
11. Change payloads cleanly:
    - remove `instance`;
    - add `data_volume`, `origin`, and `ownership`;
    - include actual/configured image and immutable container ID;
    - preserve existing project, user, SDK environment, endpoint, and status contracts.
12. Always generate MCP args containing `--data-volume <selected-volume>`.

Controller tests must assert exact Docker mutation counts and resource preservation, not just returned status strings.

## Step 5: Cut over parser, MCP bridge, and output

1. In `cli.py`, replace `_add_instance()` with `_add_data_volume()` and make it available on every runtime command.
2. Route `status`, `stop`, and `logs` through `_command_config()` instead of bypassing config resolution.
3. Keep existing optional `CONFIG` placement for lifecycle commands; other commands continue using the current directory, remembered managed config, or defaults.
4. Load active state when computing config defaults. A managed container's remembered config path remains optional metadata, not identity.
5. Remove LocalCloud instance wording from parser help, progress messages, success summaries, and error details.
6. In `mcp_stdio.py`, replace adapter/run `instance` parameters with `data_volume`, update recovery instructions, and retain protocol behavior unchanged.
7. In `output.py`:
   - replace `PanelContext.instance` with `data_volume`;
   - replace instance field specs with data-volume/origin/ownership/container-ID fields;
   - keep concise output compact and reserve detailed ownership/drift for verbose or explicitly selected fields;
   - preserve current cloud art, animation, widths, ANSI behavior, and `lc` alias statement.
8. In `agent_guide.py`, explain that one data volume hosts multiple project contexts and show `--data-volume` only for selecting a different persisted runtime.
9. Add a repository-wide focused assertion that public CLI help, generated MCP args, and config examples contain no obsolete LocalCloud selector.

## Step 6: Add real Docker adoption coverage

Create `tests/integration/test_external_runtime_adoption.py` using unique names and exact-ID cleanup.

Cover these observable scenarios against the qualified image:

1. Create a named volume and manually run a container without CLI ownership labels.
2. Confirm `status --data-volume` reports `origin=attached`, the exact volume, and the same container ID.
3. Start two different project contexts and prove both resolve to that same container ID while project API records remain distinct.
4. Stop, start, and restart through the CLI and verify the external container, network, and volume are never removed or relabeled.
5. Start with a custom volume, then omit the flag and verify active-state reuse.
6. Stop the container externally and verify CLI `start` starts it rather than creating another.
7. Create two stopped containers referencing one volume and verify deterministic collision failure.
8. Mount the volume into an incompatible image and verify creation is blocked.
9. Verify attached `reset --all-projects` fails without any before/after Docker identity change.
10. Verify a legacy compatible image can attach/restart but cannot be used for managed creation without the capability label.
11. Verify a CLI-created runtime over an existing unlabeled volume owns only its new container/network.

The fixture must remove only resources it created explicitly. It must not call the CLI's destructive cleanup for external-resource assertions.

## Step 7: Update release and public contracts

### CLI repository

1. Update `README.md` examples and explain default `localcloud-data`, custom `--data-volume`, active-runtime persistence, and external ownership safety.
2. Update `RELEASING.md` smoke commands and rollout order.
3. Change `.github/workflows/cli-release.yml` release smoke from `--instance release-smoke` to `--data-volume localcloud-data-release-smoke`; keep explicit Docker cleanup as a final safety net.
4. Update agent guide, help snapshots, packaging tests, and any generated completion/documentation surfaces.
5. Do not modify historical design documents that describe already-completed work; only current public contracts change.

### Website repository

1. Rename the docs-contract product field to `defaultDataVolume: "localcloud-data"` in the snapshot, TypeScript type, exact-key verifier, and distributed-doc generator.
2. Replace user-facing “LocalCloud instance” wording with “runtime” or “data volume” where it denotes host-runtime identity.
3. Update parallel-test guidance to require a distinct data volume per isolated job while allowing multiple projects inside one runtime.
4. Regenerate `llms.txt` and `llms-full.txt` from the contract.
5. Make CLI documentation verification reject `--instance`, `--volume-name`, `defaultInstance`, and LocalCloud `instance:` config examples while permitting genuine GCP resource-instance documentation.
6. Leave the installer lifecycle unchanged unless its tests consume exact help/output wording.

## Step 8: Verify in dependency order

### Server and image

```bash
cd ../localcloud/localcloud-server
./gradlew test
cd ..
python3 scripts/validate-port-map.py
./build.sh

docker image inspect jaysen2apache/localcloud:latest \
  --format '{{ index .Config.Labels "com.localcloud.runtime-ownership" }}'
```

The final command must print exactly `data-volume-v1`.

### CLI focused and full checks

```bash
uv run --frozen --extra test python -m pytest -q \
  tests/test_config.py \
  tests/test_docker_runtime.py \
  tests/test_controller.py \
  tests/test_cli.py \
  tests/test_mcp_stdio.py \
  tests/test_output.py \
  tests/test_agent_guide.py \
  tests/test_release_packaging.py

uv run --frozen --extra test python -m pytest -q
uv run --frozen --extra test python -m pytest -q -m docker \
  tests/integration/test_external_runtime_adoption.py
```

Then smoke the actual command surface:

```bash
uv run --frozen localcloud --help
uv run --frozen localcloud start --help
uv run --frozen localcloud status --data-volume localcloud-data --verbose
```

Confirm help has `--data-volume` and no LocalCloud `--instance`/`--volume-name` selector.

### Frozen executable

```bash
uv run --frozen --extra release python -m PyInstaller --clean --noconfirm localcloud.spec
./dist/localcloud --version
./dist/localcloud start --help
```

Run the external-container smoke once with the frozen executable, including MCP initialization, so packaged Docker discovery and stdio behavior are exercised rather than inferred from unit tests.

### Website

```bash
cd ../localcloud-site
pnpm build
pnpm test:installer
```

Verify generated agent text says `Default data volume: localcloud-data` and contains no obsolete LocalCloud selector.

## Step 9: Review and rollout

1. Request code review separately for the server/image ownership cutover and the CLI discovery/ownership cutover.
2. Resolve all material findings and rerun the affected focused checks plus each repository's full verification once.
3. Publish and qualify the server image first; record its immutable digest.
4. Exercise the new CLI against:
   - the new qualified image for managed creation;
   - a currently running older compatible image for safe attachment.
5. Publish the signed CLI release and generated Homebrew formula using the existing release process.
6. Update the website contract provenance to the released runtime and CLI revisions, regenerate public agent text, and deploy the site.
7. Treat CLI rollback as non-transparent after new runtimes exist: an older CLI cannot discover resources that intentionally lack instance labels. Before any new managed runtime is created, a CLI release can be rolled back normally; afterward, retain the new CLI or use explicit Docker recovery. Never make rollback work by adding obsolete instance labels.

## Suggested commit boundaries

- Server repository: `runtime: key child ownership by data volume`
- CLI repository: `runtime: discover and control LocalCloud by data volume`
- Website repository: `docs: publish data-volume runtime contract`

Each cutover commit must keep its own repository tests and public contracts coherent. The release dependency is coordinated by image qualification, not by cross-repository merge commits.
