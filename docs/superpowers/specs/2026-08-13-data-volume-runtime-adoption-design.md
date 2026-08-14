# LocalCloud Data-Volume Runtime Adoption Design

## Status

Design approved for specification. Implementation has not started.

## Goal

Make the CLI operate on the LocalCloud runtime using the configured Docker data volume even when another tool created the container. Remove the separate LocalCloud instance identity so container discovery cannot disagree with the data that the runtime serves.

The CLI must preserve its current safety boundary: it may control the lifecycle of a compatible external container, but it must not claim or destroy Docker resources it did not create.

## Verified Runtime Model

The LocalCloud image declares `/var/lib/localcloud` as its persistent volume. PostgreSQL, Cloud Storage, Spanner, BigQuery, logging, and other durable service state live below that directory. Container-local logs and process state are intentionally replaceable.

A single LocalCloud server stores multiple projects in the same data volume. Project IDs are selected per request and are persisted as project-scoped records; they do not identify a Docker runtime. Caller identity is also request context only.

Therefore:

- the Docker volume mounted at `/var/lib/localcloud` is the durable runtime identity;
- the container ID is the identity of the current process serving that volume;
- the configured image is a compatibility constraint;
- project and user values select context within the runtime;
- container and network names are creation details, not discovery keys.

## Terms

- **Data volume**: the named Docker volume mounted read-write at `/var/lib/localcloud`.
- **Managed resource**: a container, network, or volume carrying valid CLI ownership, role, and data-volume labels.
- **Attached resource**: a resource used by the selected runtime but not owned by the CLI.
- **Managed runtime**: a runtime whose container, network, and data volume are all managed.
- **Attached runtime**: any runtime with at least one attached resource. Resource-level ownership remains visible so the CLI can clean up only what it owns.
- **Active runtime**: the last runtime successfully started or restarted by the CLI.

## Configuration and CLI Contract

`data_volume` becomes the sole runtime selector in `localcloud.yaml`. Its default is `localcloud-data`.

All container-related commands accept the same explicit selector:

```text
--data-volume VOLUME
```

This applies to `start`, `restart`, `reset`, `status`, `stop`, `logs`, `console`, `env`, and `mcp`. Commands that resolve a runtime also load the current project configuration so image and runtime checks are consistent.

Clean cutover:

- remove the `instance` config field and every `--instance` option;
- replace `volume_name` and `--volume-name` with `data_volume` and `--data-volume`;
- remove instance fields from command payloads and errors;
- key locks, discovery, and ownership checks by data volume;
- retain `container_name` and `network_name` only as optional managed-resource creation overrides.

Old configuration is rejected with actionable migration details. If an old configuration specified `instance: NAME` without `volume_name`, the recovery is `data_volume: localcloud-data-NAME`. If it specified `volume_name`, that value becomes `data_volume`.

The existing `data: persistent|ephemeral` setting remains a lifecycle policy. It does not identify the runtime and never grants ownership of an attached volume.

## Runtime Selection

The selected data volume is resolved in this order:

1. `--data-volume` on the current command;
2. an explicitly declared `data_volume` in `localcloud.yaml`;
3. the active runtime stored under `LOCALCLOUD_HOME`;
4. `localcloud-data`.

The configured image is resolved independently in this order:

1. `image` explicitly declared in the selected project configuration;
2. `LOCALCLOUD_IMAGE`;
3. the image stored in the active record, but only when that active data volume was selected;
4. `jaysen2apache/localcloud:latest`.

This preserves a custom image selected through configuration or environment after `start` establishes the active runtime. Selecting a different data volume never inherits the previous volume's image.

A successful `start` or `restart` records the selected volume, configured image reference, and validated container ID as the active runtime. `stop` deliberately retains that selection so a later `start` resumes the same runtime. Read-only commands and failed commands do not change it.

The active record is a hint, not authority. It is written atomically with a schema version and contains only the data-volume name, configured image reference, and last container ID. Every use revalidates Docker state. If the recorded container disappeared, discovery may select one unique replacement using the same volume. A stale record never causes fallback to a different volume.

## Container Discovery

For the selected data volume, discovery performs these steps:

1. Validate the Docker volume name and inspect whether the volume exists.
2. List running and stopped containers.
3. Select containers with exactly one read-write mount of that named volume at `/var/lib/localcloud`.
4. Record every container using the volume, including incompatible containers, before choosing a candidate.
5. Verify each candidate against the configured image.
6. Revalidate the active container ID if it is among the compatible candidates.
7. Require exactly one compatible container and no competing user of the same volume.

Outcomes:

- no volume and no container: `status` reports not created; `start` creates a managed volume and runtime;
- existing volume with no container: `status` reports not created for that volume; `start` creates a container without claiming an unlabeled volume;
- one compatible running container: attach and use it;
- one compatible stopped container: report stopped; `start` starts that same container;
- any second container using the volume: fail with a data-volume collision, even if one candidate is remembered or stopped;
- a container using the volume with an incompatible image: fail rather than start another container against the same data.

Discovery never depends on container name or CLI instance labels.

## Image Compatibility

The resolver compares both the container's declared image reference and immutable image ID with the configured image:

- normalized equivalent references are compatible;
- different references resolving to the same local image ID are compatible;
- a mutable tag whose running container uses an older image ID remains compatible when the declared reference still matches, and status reports the actual ID;
- unrelated references and IDs are incompatible.

Actual image labels and exposed ports are collected for diagnostics. The configured reference or immutable image ID is the compatibility authority; optional image labels are not required for locally built images. Gateway-dependent commands separately require a published `24080/tcp` endpoint and a successful response from the LocalCloud `/health` endpoint.

## Resource Naming for Managed Creation

The data volume is never renamed. When the CLI must create a container or network and no explicit name is configured, it derives stable names as follows:

- `localcloud-data` uses `localcloud`;
- `localcloud-data-SUFFIX` uses `localcloud-SUFFIX` when the result is a valid Docker name;
- any other data volume uses `localcloud-volume-` followed by the first 12 hexadecimal characters of the SHA-256 digest of the volume name.

Container and network namespaces are separate, so they may use the same derived name. Existing name collisions retain the current fail-closed ownership checks.

## Ownership and Mutation Safety

Ownership is evaluated independently for the container, network, and data volume. The high-level runtime origin is `managed` only when all three are managed; otherwise it is `attached`.

Existing CLI-created resources remain manageable because their managed, role, and volume-name labels are sufficient. The obsolete instance label is ignored for runtime selection and current ownership; its only permitted use is the legacy child-cleanup case below. An unlabeled legacy default volume is attached, not implicitly owned.

Before every mutation, the runtime re-fetches the container by immutable ID and revalidates:

- selected data-volume name and mount destination;
- read-write mount mode;
- configured image compatibility;
- current container state;
- ownership labels for every resource the operation may remove or replace.

An attached container is never relabeled, removed, replaced, or upgraded. An attached network or volume is never removed. A managed container created over an existing attached volume may be stopped or replaced, but the attached volume remains protected and the overall runtime remains attached.

## Embedded Runtime Ownership

The LocalCloud server currently propagates the CLI instance through `LOCALCLOUD_INSTANCE` and `com.localcloud.instance` when it creates Dataproc and other embedded runtime containers. Removing instance identity therefore requires a coordinated LocalCloud server and image change:

- rename the server's runtime-ownership value from instance to data volume;
- replace `LOCALCLOUD_INSTANCE` with `LOCALCLOUD_DATA_VOLUME`;
- use the existing `com.localcloud.volume-name` label on runtime networks and child containers;
- verify embedded runtime networks by data-volume and configuration-hash labels;
- discover and clean managed child containers by data-volume label.

New resources must not emit instance ownership. For a fully managed legacy parent only, the CLI may read its old instance label together with its configuration hash to find and safely clean already-existing legacy child containers. That legacy label is never a runtime selector and is never copied to new resources.

The server image carrying the new ownership contract must be released before or with the CLI release. Attached older containers continue running with their original environment; the CLI does not attempt to rewrite them.

The new image advertises `com.localcloud.runtime-ownership=data-volume-v1`. The CLI may attach to and safely restart a compatible older container without that capability, but it must refuse to create or replace a managed container from an image that lacks it. This prevents a new managed runtime from silently emitting obsolete instance-owned child resources.

## Command Behavior

| Command | Fully managed | Managed container on attached volume | Attached container |
| --- | --- | --- | --- |
| `status`, `logs` | Supported | Supported | Supported |
| `env`, `console`, `mcp` | Supported when healthy | Supported when healthy | Supported when gateway port 24080 is published and healthy |
| `start` | Create or start | Create or start without claiming the volume | Start the same stopped container; no-op when already running |
| `stop` | Stop; owned ephemeral cleanup remains allowed | Stop without removing the volume | Stop only; remove nothing |
| `restart` | Restart or perform owned configuration replacement | The managed container may be replaced; preserve the attached volume | Restart the same container only |
| `reset` for selected project | Supported | Supported | Supported through the running LocalCloud API |
| `reset --all-projects` | Supported through existing owned recreation semantics | Rejected | Rejected |

`start` and `restart` succeed only after the gateway is published, healthy, and the selected project is ready. Failure while creating a fully managed runtime retains the existing owned rollback behavior. Failure while creating a managed container over an attached volume may roll back only the managed resources. Failure after starting or restarting an attached container never removes or replaces that container. No failed operation updates the active-runtime record.

Image, data-volume mount, and shared-volume conflicts are selection failures. Other inspectable configuration differences on an attached container are reported as drift but do not block safe lifecycle commands; `restart` restarts that same configuration and never implies that drift was applied. A managed container may apply drift through owned replacement even when its data volume is attached, but the attached volume remains protected. The CLI gives manual replacement instructions for settings that cannot take effect without recreating an attached container. Changing `data_volume` selects a different runtime; it never migrates or mutates the previously selected volume.

Generated MCP configuration always pins `--data-volume` so a long-lived bridge cannot silently follow a later active-runtime change.

## Output and Errors

Verbose and JSON runtime output includes:

- `origin`: `managed` or `attached`;
- resource-level ownership for container, network, and data volume;
- data-volume name and `/var/lib/localcloud` mount details;
- container name, immutable ID, state, and health;
- configured image reference and actual image ID;
- gateway URL and resolved endpoint map;
- selected project and user where applicable;
- inspectable attached-configuration drift and the settings that remain unapplied.

Concise human output remains task-oriented and adds the data-volume name only where it disambiguates the selected runtime.

Dedicated failures distinguish:

- invalid or absent data volume;
- no container for an existing volume;
- multiple containers sharing one data volume;
- incompatible image using the selected volume;
- missing gateway publication;
- unhealthy LocalCloud runtime;
- destructive operation forbidden by resource ownership.

`doctor` reports stale active-runtime records, shared-volume collisions, incompatible users of LocalCloud data volumes, and invalid managed metadata.

## Concurrency and State

Runtime locks use a collision-resistant digest of the full data-volume name. Selection, creation, start, stop, restart, replacement, and reset occur under the same per-volume lock. Destructive operations perform a final Docker revalidation after acquiring the lock.

A separate global active-state lock serializes successful `start` and `restart` updates across different volumes. The active-runtime file is then replaced atomically. Corrupt or unknown-schema state produces a diagnostic and falls back only to an explicit/configured selector or the documented default; it never guesses another volume.

## Migration and Compatibility

This is a command/config clean cutover, not a Docker-data migration:

- existing data volumes remain unchanged;
- existing compatible containers are rediscovered by their actual mount;
- existing valid ownership labels continue to protect CLI-created resources;
- old instance labels are ignored for selection and are never rewritten;
- old CLI/config spellings fail with direct migration guidance;
- generated docs, MCP arguments, tests, installer examples, and release verification must use `--data-volume` and contain no LocalCloud instance selector.
- the LocalCloud server image, runtime-child ownership labels, and CLI child cleanup must cut over together from instance to data-volume ownership.

## Verification Requirements

Implementation is complete only when focused and end-to-end checks demonstrate:

1. a manually started official LocalCloud container using `localcloud-data` is reported as attached;
2. `start`, `stop`, and `restart` operate on its same container ID without removing its volume or network;
3. two project IDs use the same selected container ID and data volume while remaining separate project contexts;
4. a custom volume selected by `--data-volume` becomes active after `start` and is reused without the flag;
5. a stopped matching external container is started rather than replaced;
6. multiple containers referencing one volume fail deterministically;
7. an incompatible container using the volume blocks creation;
8. managed configuration replacement still preserves persistent owned data;
9. attached `reset --all-projects` is rejected without Docker mutation;
10. old managed containers are adopted by volume while obsolete CLI/config selectors are rejected;
11. generated MCP configuration pins the selected data volume;
12. embedded runtime children and networks use data-volume ownership, while managed legacy children remain safely cleanable;
13. attached legacy images remain operable while managed creation rejects images lacking the data-volume ownership capability;
14. the frozen executable and official installation/documentation checks expose the same contract.

## Non-goals

- Supporting host bind mounts or anonymous-volume discovery.
- Migrating data between Docker volumes.
- Treating project ID, user, image tag, container name, or network name as runtime identity.
- Claiming ownership of externally created Docker resources.
- Automatically replacing or upgrading attached containers.
- Allowing concurrent containers to share one LocalCloud data volume.
