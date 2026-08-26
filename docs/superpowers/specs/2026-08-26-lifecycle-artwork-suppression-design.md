# Lifecycle Artwork Suppression Design

## Goal

Hide the large LocalCloud artwork panel from `start`, `restart`, and `stop` command output without changing command behavior or removing useful lifecycle feedback.

## Scope

- `start`, `restart`, and `stop` must continue to show their spinner or plain progress messages, elapsed time, streamed diagnostic lines, and final success or error status.
- These three commands must not render the boxed LocalCloud artwork panel in interactive or non-interactive output.
- `doctor`, `status`, and `reset` retain their existing panel behavior.
- The shared panel renderer remains available. This change does not delete artwork code or add a new public CLI option.

## Design

The CLI execution observer controls whether a lifecycle update includes a `PanelContext`. It will stop attaching a panel for `start`, `restart`, and `stop`:

- `start` continues to emit its initial volume check and later starting message, but the starting update carries no panel.
- `restart` and `stop` continue to emit their existing action messages, but those updates carry no panel.
- `reset` and `status` continue to construct and pass their current `PanelContext`.
- `doctor` remains unchanged.

This keeps the change local to command-to-output policy. `LifecycleReporter` and `render_panel` remain generic and unchanged.

## Alternatives Considered

1. Add a reporter-level `show_panel` flag. This adds state and configuration for a three-command policy that the observer already knows.
2. Delete or globally disable panel rendering. This would unintentionally remove the panel from commands outside the requested scope.
3. Stop passing `PanelContext` for the three commands. This is the smallest and most reversible approach, so it is selected.

## Verification

Regression tests will verify that:

1. `start`, including the later `starting` transition, never installs a panel context.
2. `restart` and `stop` never install a panel context.
3. Their lifecycle progress messages remain present.
4. `status` and `reset` still install a panel context.
5. Existing panel renderer and reporter tests continue to pass.

The focused CLI tests and the complete unit test suite must pass.
