# Docker Command Planning and Debug Implementation Plan

**Design:** `docs/superpowers/specs/2026-08-27-docker-command-planning-debug-design.md`

**Goal:** Make Docker command previews exact and read-only, remove misleading duplicate debug commands, report protocol-aware ports, and cover every mutating lifecycle command with `--dry-run`.

## Task 1: Centralize Docker run planning and port inspection

**Files:**
- Modify `src/localcloud_cli/docker_runtime.py`
- Modify `tests/test_docker_runtime.py`

**Steps:**
1. Add an immutable Docker run-plan value containing the exact image, name, network, memory, volumes, ports, environment, and labels used by `containers.run`.
2. Move run argument construction into one planner used by both shell rendering and SDK execution.
3. Keep shell quoting in one renderer and retain valid compact port-range syntax.
4. Replace endpoint-only inspection with protocol-aware published bindings that prefer live `NetworkSettings.Ports` and fall back to `HostConfig.PortBindings` for stopped containers.
5. Derive the backward-compatible endpoint map from published bindings deterministically, preferring TCP on protocol collisions.
6. Add read-only local-image planning that never pulls and reports an actionable missing-image error.
7. Add exact resource command renderers needed by lifecycle previews.
8. Cover fixed/dynamic TCP, UDP, stopped-container fallback, protocol collision, shell quoting, and rendered-plan/SDK-argument parity.

**Acceptance:** One run plan supplies both the exact preview and `containers.run`; port diagnostics preserve protocol and configured stopped-container bindings without changing the public endpoint-map shape.

## Task 2: Plan and execute lifecycle actions once

**Files:**
- Modify `src/localcloud_cli/controller.py`
- Modify `src/localcloud_cli/docker_runtime.py`
- Modify `tests/test_controller.py`
- Modify `tests/test_docker_runtime.py`

**Steps:**
1. Add immutable lifecycle plan/action values for create, replace, start, restart, stop, project reset, all-project reset, and no-op.
2. Refactor `Controller.start`, `restart`, `stop`, and `reset` so each selects one plan before mutation and normal execution consumes that selected plan.
3. Keep existing locks, ownership checks, immutable identity revalidation, replacement rules, persistent-volume preservation, readiness, seed, and active-state behavior.
4. Render ordered Docker resource/container commands and labeled LocalCloud API/seed/state operations for dry-run.
5. Make dry-run return before image pulls, Docker mutations, Java API calls, seed execution, and active-state writes.
6. Reject `--dry-run --pull` and unavailable local images with specific HostErrors.
7. Initialize restart status when no runtime exists.
8. Prove each dry-run branch performs zero mutations and each no-op explains its reason.

**Acceptance:** Every mutating lifecycle command can build an accurate read-only ordered plan, while non-dry-run behavior retains current safety and payload contracts; missing-runtime restart succeeds.

## Task 3: Wire CLI flags and concise diagnostics

**Files:**
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Add `--dry-run` to `start`, `restart`, `reset`, and `stop`; retain cleanup's existing option.
2. Pass `dry_run` and observer arguments through the command dispatcher based on method signatures.
3. Print lifecycle dry-run plans as native stdout text and use dry-run-specific progress/success messages.
4. Expand `_ExecutionObserver` with structured action, resource, port, readiness, and project-operation diagnostics.
5. Log effective config source and relevant command options without dumping environment or label values.
6. Remove synthetic `_format_effective_run_command` output for existing-container start/restart.
7. Emit the actual selected mutation once and list requested/resolved ports separately.
8. Cover parsing, routing, stdout/stderr separation, no duplicate run command, and port visibility.

**Acceptance:** `--dry-run` is discoverable and correctly routed for all mutating lifecycle commands; `--debug` is more informative while shorter and truthful.

## Task 4: Update CLI reference

**Files:**
- Modify `docs/cli-reference.md`

**Steps:**
1. Document lifecycle `--dry-run` coverage and strict read-only behavior.
2. State that `--dry-run --pull` is rejected and a local image is required for exact create/recreate previews.
3. Warn that exact dry-run commands include configured environment values and should be handled as sensitive output.
4. Document the expanded debug event categories and stdout/stderr separation.
5. Keep cleanup dry-run documentation distinct from lifecycle command planning.

**Acceptance:** Operators can predict dry-run side effects, exact-output sensitivity, and debug behavior from the public CLI reference.

## Task 5: Verify behavior and review the cutover

**Files:**
- Modify only files required by defects found during verification.

**Steps:**
1. Run focused Docker runtime tests for plan parity and port inspection.
2. Run focused controller tests for every dry-run action, zero mutation, and missing-runtime restart.
3. Run focused CLI tests for flag parsing, routing, native plan output, debug deduplication, and port diagnostics.
4. Run the complete relevant unit suite once.
5. Smoke actual `start`, `restart`, `reset`, and `stop` help output.
6. Smoke a strict read-only lifecycle dry-run against available local Docker state; if no suitable image exists, exercise and record the actionable missing-image path.
7. Request final code review and address verified correctness or maintainability findings.

**Acceptance:** Focused and relevant suites pass, the actual CLI demonstrates the new flags and read-only behavior, debug output contains ports without synthetic duplicate commands, and the final changes preserve unrelated working-tree edits.