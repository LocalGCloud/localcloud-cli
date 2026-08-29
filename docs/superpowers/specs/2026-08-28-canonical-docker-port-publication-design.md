# Canonical Docker Port Publication Design

**Date:** 2026-08-28  
**Status:** Approved  
**Repositories:** `localcloud-cli`, `../localcloud`

## Context

LocalCloud owns the canonical listener contract in `localcloud.defaults.yaml`, its maintained documentation, and `start.sh`. The CLI currently treats Docker image `Config.ExposedPorts` as the host-publication policy. That metadata is generated from the image's static Dockerfile `EXPOSE` instruction and describes possible image capabilities, not which ports the effective runtime configuration requires.

This conflation currently causes four concrete errors:

- standard CLI runs publish `24093/tcp`, although LocalCloud reserves `24093/udp` for optional transparent DNS;
- TLS ports are published even when TLS is disabled;
- transparent networking still targets removed Caddy ports `24094` and `24095` instead of the Java gateway;
- a configured non-default `tls.port` cannot drive Docker publication or endpoint discovery.

LocalCloud's reference launcher publishes `24080-24092/tcp` in standard mode, conditionally adds TLS listeners, and conditionally adds transparent host aliases. The CLI must implement that same policy while retaining its support for dynamic host-port allocation across multiple data volumes.

## Goals

- Make CLI Docker bindings match LocalCloud's current host-publication contract.
- Preserve standard plaintext endpoints when TLS or transparent networking is enabled.
- Support a configured Gateway TLS port.
- Keep image port metadata useful for compatibility diagnostics without making minor metadata drift block users by default.
- Correct LocalCloud's Docker DNS metadata and document every canonical port and activation condition.

## Non-goals

- Change LocalCloud service availability, listener implementations, or canonical port numbers.
- Publish only the subset of `24080-24092` belonging to currently enabled services; LocalCloud intentionally publishes the complete base block.
- Introduce an image port-manifest format or image-label schema.
- Document service API compatibility; existing service compatibility documents remain authoritative.

## Sources of truth

The authoritative order is:

1. `../localcloud/localcloud.defaults.yaml` for listener identity, port, and protocol.
2. `../localcloud/start.sh` and transparent-networking design for host-publication profiles.
3. Effective `localcloud.yaml` and explicit CLI overrides for TLS activation and the configured Gateway TLS port.
4. Docker image `Config.ExposedPorts` for compatibility diagnostics only.

`EXPOSE` never decides whether the CLI publishes a port.

## Port publication profiles

### Standard

Always request loopback bindings for container TCP ports `24080-24092`. If the complete ordinary set is free, use the same host ports. If any ordinary port is occupied, request dynamic Docker host ports for the complete ordinary set so generated endpoint relationships remain consistent.

### TLS

When effective TLS is enabled, add ordinary loopback bindings for:

- configured `tls.port` (`24443` by default);
- Cloud Storage TLS `24481`;
- Pub/Sub TLS `24482`;
- Memorystore TLS `24489`.

TLS ports participate in the same all-canonical-or-all-dynamic allocation decision as the standard block. Changing TLS activation or `tls.port` changes immutable Docker bindings and therefore changes managed runtime identity.

### Transparent networking

Transparent networking requires TLS. It adds fixed loopback aliases:

- host `53/udp` to container `24093/udp`;
- host `80/tcp` to container `24080/tcp`;
- host `443/tcp` to container `tls.port/tcp`.

The ordinary direct bindings remain present. Consequently, `24080/tcp` and `tls.port/tcp` each have two requested host bindings. Host ports `53`, `80`, and `443` must be free; they never receive dynamic substitutes.

SDK endpoint discovery prefers the ordinary canonical or dynamically assigned binding over transparent aliases `80` and `443`.

## Effective TLS configuration

`LocalCloudConfig` gains explicit `tls_enabled` and `tls_port` values. Resolution follows existing runtime precedence:

1. explicit CLI `--tls` or `--no-tls` override;
2. `host.environment.LOCALCLOUD_TLS_ENABLED` and `LOCALCLOUD_TLS_PORT` overrides;
3. top-level `tls.enabled` and `tls.port`;
4. defaults `false` and `24443`.

The CLI validates that the effective TLS flag is boolean and the effective TLS port is an integer in `1..65535`. Transparent networking with effective TLS disabled fails before Docker mutation.

The TLS values are part of runtime settings because they alter immutable port bindings. The validation strictness flag described below is not runtime identity.

## Run-plan representation

A Docker run plan must represent one or more host bindings for each container port. The normalized internal representation stores a tuple of bindings for every `container-port/protocol` key. Docker SDK arguments convert a single binding to the existing pair form and multiple bindings to Docker SDK's binding-list form.

Shell rendering expands every binding, while continuing to collapse contiguous one-to-one ordinary runs. Existing-container inspection preserves every configured binding instead of keeping only the first.

Endpoint-map selection follows this order:

1. same-number ordinary binding;
2. non-transparent dynamically allocated binding;
3. transparent alias only when no ordinary binding exists.

## Image metadata validation

The CLI adds `--strict-port-validation` to lifecycle commands that can create or replace a runtime.

Validation compares image metadata with the canonical image capability contract: `24080-24092/tcp`, default TLS capabilities `24443/tcp`, `24481/tcp`, `24482/tcp`, and `24489/tcp`, plus `24093/udp`. A configured custom `tls.port` does not need an `EXPOSE` entry because Docker can publish an undeclared container port.

- By default, mismatches produce a visible warning and creation continues using the explicit LocalCloud policy. Docker remains the final authority.
- With `--strict-port-validation`, the same mismatch raises an actionable error before Docker resources are mutated.
- A missing `24080/tcp` remains a hard incompatible-image error because the CLI cannot operate without the Gateway.

Developer and CI tests remain strict. They are not executed by installed LocalCloud users and therefore cannot block runtime use.

## LocalCloud image metadata

The LocalCloud Dockerfile changes the DNS exposure from bare `24093` (implicitly TCP) to `24093/udp`. Other static `EXPOSE` entries continue describing possible image capabilities even when their listeners are disabled by configuration.

`scripts/validate-port-map.py` validates protocol-qualified Docker exposure metadata. This remains a developer/CI check rather than a runtime prerequisite.

## User documentation

Create `../localcloud/docs/PORTS.md` as a reference for operators, integration authors, and CLI maintainers. It covers:

- canonical source ownership;
- every standard, secure, additional, and infrastructure listener;
- standard, TLS, and transparent host-publication profiles;
- configured versus active listeners;
- Docker `EXPOSE` metadata versus `-p` publication versus process listeners;
- `/services` and `/tls/status` runtime discovery;
- port conflicts, dynamic CLI mappings, and multiple runtimes;
- `--strict-port-validation` behavior;
- maintenance invariants for launchers and CLIs.

The LocalCloud docs index links to the new reference. Service API compatibility remains outside this document.

## Error handling

- Invalid effective TLS values fail as configuration errors.
- Transparent networking without TLS fails before Docker mutation.
- Occupied transparent host ports identify the conflicting port and protocol.
- Image metadata drift warns by default and identifies missing or unexpected protocol-qualified entries.
- Strict metadata validation converts that warning into a pre-mutation error.
- Docker allocation races retain the existing actionable container-create error path.

## Verification

Focused CLI tests cover:

- default publication ending at `24092`;
- TLS ports absent while TLS is disabled;
- default and custom TLS ports while TLS is enabled;
- transparent aliases targeting `24093/udp`, `24080`, and effective `tls.port`;
- multiple Docker SDK and rendered-shell bindings per container port;
- endpoint discovery preferring ordinary bindings;
- warning-by-default and opt-in strict image validation;
- transparent networking rejected without TLS;
- runtime replacement when effective binding settings change.

LocalCloud checks cover the Dockerfile's `24093/udp` metadata and the canonical port validator. Verification runs only focused tests and the port validator; installed runtime startup never depends on running the developer checks.

## Rejected alternatives

### Filter the image's exposed ports

This is smaller but leaves static image metadata in control of runtime policy, cannot reliably support custom `tls.port`, and remains vulnerable to dormant capability drift.

### Add a versioned image port manifest

A manifest could provide a stronger cross-repository contract, but it introduces build generation, schema versioning, and release compatibility work that is unnecessary while the canonical contract remains small and stable.

### Remove ordinary bindings in transparent mode

This avoids multiple Docker bindings per container port but breaks LocalCloud's documented HTTP/TLS coexistence and makes SDK endpoint generation depend on privileged aliases. Transparent routing must be additive.
