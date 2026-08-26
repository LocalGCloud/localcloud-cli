## 1. Characterize the CLI-Owned Contract

- [x] 1.1 Add focused tests that pin existing precedence and validation for `version`, `context`, and every supported `host` field.
- [x] 1.2 Add tests proving strict YAML syntax still rejects duplicates, merge keys, recursive aliases, non-string keys, and ambiguous scalars.
- [x] 1.3 Add pass-through tests for valid Java-owned nested fields unknown to the CLI and preserve exact flat-schema migration diagnostics.
- [x] 1.4 Document which CLI options are host/client overrides rather than server configuration overrides.

## 2. Split Parsing from Java-Owned Semantics

- [x] 2.1 Refactor public-document parsing into strict syntax checks plus section-scoped extraction for CLI-owned fields.
- [x] 2.2 Keep semantic validation for `host`, public `version`, and required client request context.
- [x] 2.3 Remove nested server, service-catalog, availability, tier, and infrastructure validation from the CLI.
- [x] 2.4 Remove service-profile normalization and packaged server-default assumptions from CLI configuration loading.
- [x] 2.5 Ensure optional raw inspection of Java-owned fields labels them as configured input rather than effective state.

## 3. Preserve Secure Docker Facilitation

- [x] 3.1 Keep exact selected-file mounting at the canonical container destination with read-only mode.
- [x] 3.2 Keep canonical `LOCALCLOUD_CONFIG` injection only when a user file is selected.
- [x] 3.3 Keep image `com.localcloud.config-schema` capability gating based on the public config version without loading image defaults.
- [x] 3.4 Remove Java-owned values from generated container environment, labels, and runtime identity.
- [x] 3.5 Verify config path/presence and CLI-owned host settings still trigger the intended stable-hash or replacement behavior.
- [x] 3.6 Verify same-path Java-owned edits preserve container identity and apply through restart without recreation.

## 4. Source Effective Runtime State from LocalCloud

- [x] 4.1 Inventory controller, status, output, and agent-guide paths that currently infer effective services or server values from raw CLI configuration.
- [x] 4.2 Replace inferred effective state with LocalCloud-reported service/runtime data after readiness.
- [x] 4.3 Report effective state as unavailable before readiness while keeping raw configured input separately identifiable.
- [x] 4.4 Add focused tests for successful server-reported state, unavailable state, and explicit client project/user targeting.
- [x] 4.5 Coordinate any missing secret-safe runtime-summary API field with the LocalCloud change instead of adding CLI-side merge logic.

## 5. Fixtures, Documentation, and Packaging

- [x] 5.1 Rename the stale `tests/fixtures/services.yaml` snapshot to canonical `localcloud.defaults.yaml` naming and nest inventory under `services.catalog`.
- [x] 5.2 Update agent-guide inventory tests to consume the canonical fixture shape without treating it as a resolver input.
- [x] 5.3 Update README, help, release notes, and configuration examples with the section-ownership and pass-through contract.
- [x] 5.4 Add a literal/structural audit proving the CLI no longer contains packaged service defaults or Java-owned normalization logic.
- [x] 5.5 Verify the CLI package has no Java, LocalCloud source-checkout, or image-execution dependency before container creation.

## 6. Coordinated Verification

- [x] 6.1 Run focused config, controller, Docker runtime, agent-guide, and release-packaging tests.
- [x] 6.2 Run the complete CLI test suite.
- [ ] 6.3 Verify a compatible Java-authoritative image accepts a new Java-owned nested field passed through by the CLI.
- [ ] 6.4 Verify an image without the required schema capability is rejected before container creation.
- [ ] 6.5 Verify direct Docker remains usable without the CLI and CLI-managed Docker produces the same effective LocalCloud state for equivalent inputs.

Note: 6.3-6.5 require running an actual built LocalCloud image against the CLI
end-to-end (not just the unit-test double in `tests/test_docker_runtime.py`)
and are left for manual/CI verification once a Java-authoritative image is
available; the equivalent behavior is covered at the unit level by
`test_managed_create_rejects_image_without_config_schema_capability` and
`test_selected_config_is_mounted_read_only_without_freezing_server_values`.
