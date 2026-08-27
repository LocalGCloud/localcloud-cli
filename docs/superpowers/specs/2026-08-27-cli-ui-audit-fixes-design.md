# CLI UI Audit Fixes Design

## Goal

Resolve every prioritized finding in `00-UI-REVIEW.md` while preserving LocalCloud's approved colorful five-row cloud artwork, stable redirected-stream behavior, machine-readable schemas, and existing lifecycle-panel suppression for `start`, `restart`, and `stop`. The mark's current 16×5 geometry is defined by `2026-08-27-terminal-cloud-compaction-design.md`.

## Scope

This change covers five findings:

1. service availability must remain legible without color;
2. invalid `--fields` input must provide an immediate recovery path;
3. panels and concise summaries must prioritize complete task context at narrow widths;
4. the public README must become a focused entry point backed by maintainable reference documents;
5. panel headings and service labels must describe the actual command and displayed subset.

It does not change controller payloads, Docker behavior, MCP behavior, environment output, verbose JSON schemas, or which commands show artwork. `doctor`, `status`, and `reset` retain panels. `start`, `restart`, and `stop` retain progress feedback without panels.

## Approved Visual Direction

The colorful 16×5 cloud remains intact at every panel breakpoint. At 40–49 columns, the panel uses the approved **full mark, stacked context** composition:

1. LocalCloud version border title;
2. command-aware heading;
3. centered full cloud artwork;
4. one context field per row;
5. a textual service-state summary;
6. bottom border and width-appropriate tip when space allows.

The required context rows are data volume, project, user, config source, data mode, and service selection. No required field may disappear at 40 columns. Long values may use ANSI-aware ellipsis so every row remains within the terminal width. Below 40 columns, the minimal safety layout may truncate because the complete artwork and labels cannot fit truthfully.

At 50–79 columns, the full artwork remains centered above stacked context. Pairs may share a row only when both values fit without material truncation; otherwise each field receives its own row. At 80 columns and above, the current split composition remains, with command-aware copy, non-color service markers, data mode, and accurate service labeling.

## Service-State Semantics

Color reinforces service selection but never carries it alone:

- enabled/selected services use a filled marker (`●`);
- disabled/unselected services use an open marker (`○`);
- narrow layouts add a textual count such as `● 3 selected · ○ 7 off`;
- the fixed ten-service display is labeled **Featured services**, not **Google Cloud Services**.

The panel describes configured service selection, not runtime health. Copy must use `selected` or `off`, never claim a service is healthy or running when that state is unavailable. Existing aliases such as `sql`/`cloudsql` and `secretmanager`/`secrets` remain normalized by the renderer.

## Command-Aware Panel Copy

`PanelContext` gains a presentation heading supplied by CLI command policy:

- `doctor`: `Checking your LocalCloud setup`;
- `status`: `Checking LocalCloud status`;
- `reset --all-projects`: `Resetting all LocalCloud data`;
- project reset: `Resetting project data`.

The renderer owns layout only; `_ExecutionObserver` continues to own command-to-copy policy. A neutral default remains available for direct renderer tests and future callers. This preserves the existing boundary established by the lifecycle artwork suppression design.

## Recoverable `--fields` Errors

Validation continues to store invalid and valid paths in structured error details. Concise error rendering gains safe support for scalar sequences and prints the valid paths after the invalid-field message. Verbose JSON remains unchanged.

Command help also lists the valid field paths for commands that support `--fields`. The list is derived from the existing declarative `ALLOWED_FIELDS` registry rather than duplicated in parser code.

Example:

```text
Error [invalid_output_field] Unsupported summary field for status: nope
Fields: nope
Valid Fields: status, data_volume, container.state, container.url, ...
```

## Width-Aware Concise Summaries

`render_summary` gains an optional visible-width budget. When a value exceeds the available first-line width, it wraps on visible character boundaries and continues under the value column with hanging indentation. Labels remain aligned. ANSI codes must not affect wrapping calculations.

The CLI supplies a width only for interactive stdout. Redirected concise output remains byte-for-byte compatible with the existing no-width behavior, preserving scripts that consume stable line-oriented output. JSON and native output paths are unchanged.

## Documentation Architecture

`README.md` becomes the focused public entry point:

1. product positioning and concise benefits;
2. installation choices;
3. the three-step `doctor → start → env` Quick Start;
4. an accessible fenced-text terminal preview generated from representative real CLI output;
5. a compact command summary;
6. links to focused references;
7. development, license, and support essentials.

Long-form content moves without semantic loss:

- `docs/cli-reference.md`: detailed commands, output modes, automation, and color behavior;
- `docs/configuration.md`: `localcloud.yaml`, service inventory, runtime identity, and multi-project context;
- `docs/integrations.md`: SDK examples, Terraform/OpenTofu, AI coding agents, and MCP setup.

Links must be relative and valid from GitHub. The README preview uses text rather than a binary screenshot so it remains accessible, searchable, diffable, and inexpensive to update when terminal output changes.

## Error Handling and Compatibility

- Every panel row must stay within the selected visible width.
- `NO_COLOR`, `TERM=dumb`, ANSI-16, ANSI-256, and truecolor behavior remain supported.
- Unicode marker widths are measured through the existing visible-width helpers.
- Invalid-field recovery details must never expose arbitrary nested values; only the known valid-path registry is rendered.
- Redirected stdout and verbose JSON remain stable.
- No terminal rendering dependency is added.

## Verification

Focused automated tests must cover:

1. enabled and disabled featured services remain distinguishable with `ColorMode.NONE`;
2. 40-column output contains the complete cloud and every required context label;
3. 50-, 79-, 80-, 100-, and 120-column rows remain aligned and within bounds;
4. command-aware headings are supplied for `doctor`, `status`, and both reset scopes;
5. concise invalid-field output includes deterministic valid choices while verbose JSON preserves the list;
6. interactive-width summary wrapping uses hanging indentation and strips ANSI for width calculations;
7. redirected summaries keep the existing unwrapped contract;
8. `start`, `restart`, and `stop` still never install panel context;
9. README and split reference links resolve locally.

Smoke verification must run real CLI help, a failing invalid-field command, PTY `doctor`, PTY `status`, and deterministic panel rendering at 40 and 100 columns. The complete non-Docker unit suite runs after focused tests.

## Alternatives Considered

### Remove artwork below 50 columns

Rejected by the user. It maximizes information density but discards the approved LocalCloud identity precisely where the responsive design should adapt it.

### Use a second reduced cloud only at narrow widths

Rejected by the user. Maintaining two marks creates visual drift and weakens the exact-artwork contract. The selected 16×5 compaction applies consistently to every panel width.

### Keep the README monolithic

Rejected in favor of a focused README plus reference documents. Reordering alone would not resolve the cognitive load or maintenance cost of one document serving every content mode.

### Change structured error details to a preformatted string

Rejected because it would degrade verbose JSON and duplicate formatting concerns inside validation. Concise rendering should adapt the existing structured list.

## Approved Constraints

- Minimum complete panel width: 40 columns.
- Preserve the exact colorful 16×5 cloud artwork.
- Implement all P1, P2, and P3 audit findings.
- Split long-form README content into focused documents.
- Proceed through implementation and full verification without additional approval gates.
