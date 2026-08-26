# Lifecycle Artwork Suppression Implementation Plan

## Objective

Disable the boxed LocalCloud artwork for `start`, `restart`, and `stop` while preserving lifecycle progress and leaving panel behavior for other commands unchanged.

## Step 1: Add command-policy regression tests

**File:** `tests/test_cli.py`

- Exercise `_ExecutionObserver.config` for `start`, `restart`, `stop`, `status`, and `reset` with a reporter test double that records messages and panel arguments.
- Exercise `_ExecutionObserver.starting` separately because a pulled or newly created runtime can invoke it after the initial `start` update.
- Assert that `start`, `restart`, and `stop` pass no panel.
- Assert that their existing progress messages remain intact.
- Assert that `status` and `reset` still pass a `PanelContext`.

Run the focused tests and confirm they fail against the current behavior for `restart`, `stop`, and the `starting` transition.

## Step 2: Suppress panels at the observer boundary

**File:** `src/localcloud_cli/cli.py`

- Keep the early `start` volume-check update unchanged.
- For `restart` and `stop`, call `reporter.update(message)` without constructing or passing `PanelContext`.
- Keep panel construction for `status` and `reset`.
- Change `_ExecutionObserver.starting` to call `reporter.update(message)` without a panel.
- Do not modify `LifecycleReporter`, `render_panel`, or public command options.

Run the focused tests and confirm they pass.

## Step 3: Validate integration and isolate the commit

- Run all CLI and output tests.
- Run the complete unit suite.
- Run `git diff --check` on the implementation files.
- Review the diff to ensure existing unrelated worktree changes are not staged.
- Commit only the artwork suppression implementation and its regression tests.
