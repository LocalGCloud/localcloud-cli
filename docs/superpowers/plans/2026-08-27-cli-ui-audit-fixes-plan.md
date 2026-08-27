# CLI UI Audit Fixes Implementation Plan

**Design:** `docs/superpowers/specs/2026-08-27-cli-ui-audit-fixes-design.md`

**Goal:** Implement every prioritized UI audit fix without changing controller payloads, redirected output contracts, verbose JSON, or lifecycle-panel command policy.

## Task 1: Make panel service state and copy truthful

**Files:**
- Modify `src/localcloud_cli/output.py`
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_output.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Extend `PanelContext` with a neutral-default `heading` field.
2. Have `_ExecutionObserver` supply command-aware headings for `doctor`, `status`, project reset, and all-project reset.
3. Change `_format_services_row` so enabled services use `●` and disabled services use `○` in every color mode.
4. Add a service-count formatter derived from `_resolve_services_rows`; describe configuration as selected/off, not healthy/running.
5. Rename the fixed wide-panel heading from `Google Cloud Services` to `Featured services`.
6. Add focused tests proving no-color output distinguishes selected/off services and observer headings match command scope.
7. Run the focused service/panel and observer tests.

**Acceptance:** No service state depends on color, fixed service copy is accurate, and prior start/restart/stop panel suppression remains intact.

## Task 2: Implement the approved responsive composition

**Files:**
- Modify `src/localcloud_cli/output.py`
- Modify `tests/test_output.py`

**Steps:**
1. Add a single-field context-row formatter that is ANSI-aware and width-bounded.
2. Rework 40–49 column rendering to keep the exact full 16×5 cloud centered, followed by heading, data volume, project, user, config, data mode, and service summary rows.
3. Rework 50–79 column rendering to retain the full cloud and complete context; pair fields only when values fit, otherwise use one row per field.
4. Add data mode and service summary to the wide panel while retaining the split composition and 100-column cap.
5. Keep the existing minimal safety layout below 40 columns.
6. Update breakpoint tests to cover 40, 49, 50, 79, 80, 100, and 120 columns in every color mode.
7. Assert the exact cloud appears at 40 columns and every required context label is present.
8. Run focused renderer tests.

**Acceptance:** Every required field is present at 40 columns, the artwork remains exact and colorful, and every row fits its visible-width budget.

## Task 3: Make fields errors recoverable

**Files:**
- Modify `src/localcloud_cli/output.py`
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_output.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Add `valid_fields` to concise error rendering and safely format scalar sequences without admitting arbitrary nested diagnostics.
2. Import and use `valid_field_paths` when creating `--fields` help so each supported command lists its valid paths from the existing registry.
3. Keep `HostError.details["valid_fields"]` as a list so verbose JSON is unchanged.
4. Test concise invalid-field output, verbose JSON structure, and representative `status --help` text.
5. Run focused error/help tests.

**Acceptance:** Invalid `--fields` input names the invalid value and deterministic valid choices at the point of failure; JSON clients see the existing structured list.

## Task 4: Wrap interactive concise summaries safely

**Files:**
- Modify `src/localcloud_cli/output.py`
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_output.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Add an optional width budget to `render_summary`.
2. Implement ANSI-aware visible-text wrapping with hanging indentation under the value column.
3. In `_print_result`, detect terminal capabilities once and pass terminal width only for interactive stdout.
4. Leave redirected summaries unwrapped and preserve JSON/native output paths.
5. Test long image/config values at narrow widths, colored wrapping, Unicode width, and redirected compatibility.
6. Run focused summary and CLI-output tests.

**Acceptance:** Interactive summaries never overflow their terminal width; continuation lines align under values; redirected output remains unchanged.

## Task 5: Split the public documentation without content loss

**Files:**
- Modify `README.md`
- Create `docs/cli-reference.md`
- Create `docs/configuration.md`
- Create `docs/integrations.md`

**Steps:**
1. Reduce README to positioning, installation, Quick Start, an accessible fenced-text terminal preview, a compact command table, reference links, development, license, and support.
2. Move detailed command and output-mode material into `docs/cli-reference.md`.
3. Move configuration, service inventory, runtime identity, and multi-project material into `docs/configuration.md`.
4. Move SDK, Terraform/OpenTofu, AI-agent, and MCP material into `docs/integrations.md`.
5. Preserve every factual command/configuration example unless the implementation change makes it stale.
6. Check every relative link and heading anchor.
7. Run any existing documentation/agent-guide tests affected by the split.

**Acceptance:** README presents the adoption path before reference detail; all former reference content remains reachable through valid relative links.

## Task 6: Full verification and cleanup

**Files:**
- Modify only files required by defects found during verification.

**Steps:**
1. Run focused output and CLI tests covering every changed contract.
2. Run the complete non-Docker unit suite once.
3. Smoke `uv run lc --help` and `uv run lc status --help`.
4. Smoke invalid `uv run lc status --fields nope` and confirm recovery choices.
5. Run PTY `uv run lc doctor` and `uv run lc status`; inspect the actual colorful wide panel and final summary.
6. Render deterministic 40- and 100-column panels and assert width/content invariants.
7. Run the Impeccable detector once against `README.md` after documentation changes.
8. Remove the temporary `.superpowers/brainstorm` companion artifacts.
9. Request a final code review, address verified findings, and commit the implementation.

**Acceptance:** All focused and non-Docker tests pass, every smoke scenario exercises the shipped command path, no temporary companion artifacts remain, and the final diff contains no compatibility shim or stale documentation.
