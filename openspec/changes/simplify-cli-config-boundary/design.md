## Context

See `proposal.md` for motivation and `specs/config-facilitation/spec.md` for behavior. The CLI currently parses one YAML file into host settings, request context, service selection, and a runtime hash. It also performs shallow validation of Java-owned sections and carries a service-catalog-shaped fixture.

The coordinated LocalCloud design makes Java authoritative for defaults, merge, nested server validation, immutable wiring, and service normalization. The repositories remain independently released and are coupled only through the public YAML and image schema capability label.

## Goals / Non-Goals

**Goals:**

- Make the CLI authoritative only for host/container-management semantics.
- Preserve one shared YAML file and exact read-only pass-through.
- Allow the CLI to inspect raw Java-owned values without treating them as effective state.
- Keep image capability gating and secret-safe runtime identity.
- Source effective runtime status from LocalCloud after startup.

**Non-Goals:**

- Loading LocalCloud packaged defaults in the CLI.
- Validating nested server, service catalog, availability, tier, or infrastructure semantics.
- Invoking Java or running the image to resolve configuration before container creation.
- Changing public YAML version 1 or restoring flat/legacy aliases.

## Decisions

### Partition semantic ownership by YAML section

The CLI validates and applies `host`, reads `version` for capability gating, and reads `context` for client request targeting. It parses the complete document with strict YAML syntax so duplicate keys, unsafe aliases, and malformed structures still fail safely.

`server`, `services`, and `infrastructure` remain in the raw document but are not merged with defaults or semantically validated by the CLI. The CLI may inspect raw values for diagnostics, provided output labels them as configured input rather than effective runtime state.

Alternative rejected: reject every nested field against a copied CLI schema. This blocks independently introduced image fields and recreates cross-repository semantic drift.

### Mount the original file rather than a generated derivative

Docker construction mounts the exact selected path read-only at the canonical destination and sets `LOCALCLOUD_CONFIG`. The CLI does not generate a reduced file because doing so could drop fields unknown to that CLI version and would make it part of server resolution.

Alternative rejected: serialize a CLI-normalized file. That freezes the CLI's understanding of a Java-owned schema and risks secret copies.

### Keep runtime identity host-focused

Runtime hashing includes config path/presence and CLI-owned settings that determine container resources. It excludes Java-owned file content and secrets. Same-path server changes are applied through restart, which preserves the mount and reruns LocalCloud preflight.

Alternative rejected: hash full file content. It forces recreation for server-only edits and risks making sensitive content part of controller metadata.

### Use image capability rather than packaged defaults

When a user file is selected, the CLI reads its public version and inspects the image's `com.localcloud.config-schema` label. It does not need the image's defaults or service inventory.

Alternative rejected: bundle `localcloud.defaults.yaml` with the CLI. Independent image releases could change Java-owned fields and make the bundled copy stale.

### Source effective state from LocalCloud APIs

After readiness, controller/status output uses LocalCloud-reported services and safe resolved runtime context. Before readiness, the CLI reports effective state as unavailable and may separately show raw configured input.

Alternative rejected: calculate effective services from `services.enabled`. That requires defaults, catalog deletion, availability, tier, and environment precedence logic the CLI does not own.

### Preserve focused legacy migration diagnostics

The CLI may retain explicit detection of removed flat configuration keys to provide exact migration guidance. These checks identify unsupported CLI input shape; they do not resolve Java-owned semantics.

## Risks / Trade-offs

- **[Some Java-owned errors occur after container creation begins]** → Surface preflight stderr clearly and avoid duplicating the rule in the CLI.
- **[Raw configuration and effective state may be confused]** → Label raw values as configured input and effective values as server-reported state.
- **[Server status API lacks one needed field]** → Add only a secret-safe runtime summary on the LocalCloud side; do not reintroduce CLI merge logic.
- **[Older images lack the Java-authoritative behavior]** → Keep image schema capability gating and coordinated release coverage.
- **[Strict syntax parsing still requires PyYAML]** → Treat this as a CLI-local parser dependency, not a server configuration dependency.

## Migration Plan

1. Characterize existing CLI-owned host/context/version precedence and runtime identity in focused tests.
2. Split public-document syntax/ownership checks from host semantic extraction.
3. Remove nested semantic validation and normalization for Java-owned sections while retaining exact flat-schema migration guidance.
4. Remove effective service selection from container labels, generated environment, and runtime identity.
5. Keep exact read-only mounting, canonical `LOCALCLOUD_CONFIG`, data-directory wiring, and image capability gating.
6. Update controller/status paths to use LocalCloud-reported effective service/runtime state after readiness.
7. Rename and reshape the release-contract fixture to canonical `localcloud.defaults.yaml` and `services.catalog` form.
8. Update README, help, packaging tests, and release CI to document the ownership boundary and independent-use contract.
9. Verify against a compatible Java-authoritative image plus an unsupported older image.

Rollback is a CLI code rollback. No user configuration migration is required because the public YAML and host settings remain unchanged.
