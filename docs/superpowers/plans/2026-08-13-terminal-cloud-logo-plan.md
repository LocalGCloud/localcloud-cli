# Terminal Cloud Logo Implementation Plan

> **Superseded geometry:** This plan implemented the original 20×5 mark. The active 16×5 contract and migration plan are in `../specs/2026-08-27-terminal-cloud-compaction-design.md` and `2026-08-27-terminal-cloud-compaction-plan.md`.

## Objective

Implement the approved 20×5 curved outline from `docs/superpowers/specs/2026-08-13-terminal-cloud-logo-design.md` without changing rendering, animation, or responsive panel behavior.

## Files

- Update `tests/test_output.py` to make the exact unstyled cloud artwork an observable contract.
- Update `_CLOUD` in `src/localcloud_cli/output.py` with the approved five rows.
- Update the cloud artwork and description in `docs/superpowers/specs/2026-08-13-cli-output-ux-design.md` so the earlier output specification does not contradict the approved replacement.

Do not touch the user's unrelated `tests/integration/test_agent_workflow.py` changes or commit visual-companion artifacts under `.superpowers/`.

## Step 1: Lock the approved artwork in a focused test

Extend `test_cloud_has_equal_visible_width_and_animation_changes_color` with the exact five-row tuple from the approved specification. Assert that `render_cloud(color=ColorMode.NONE)` equals this tuple and that ANSI-stripped animated frames equal it.

Run only that test first and confirm it fails against the current dense cloud. This establishes that the test detects the requested visual change rather than merely checking dimensions.

## Step 2: Replace the source artwork

Replace `_CLOUD` in `src/localcloud_cli/output.py` with:

```text
       ╭────╮       
   ╭───╯    ╰───╮   
 ╭──╯          ╰──╮ 
╭╯                ╰╮
╰──────────────────╯
```

Keep the tuple name, rendering functions, gradient stops, animation, centering, and breakpoints unchanged.

## Step 3: Reconcile the earlier output design

In `docs/superpowers/specs/2026-08-13-cli-output-ux-design.md`, replace the old filled artwork with the approved outline and describe it as a compact curved outline rather than a filled mark. Preserve the existing gradient, animation, and responsive-layout requirements.

## Step 4: Verify behavior

Run these checks in order:

1. The focused cloud test, confirming the previously failing exact-art assertion passes.
2. `uv run --extra test python -m pytest -q tests/test_output.py` for color, width, responsive-panel, and lifecycle regressions.
3. Render no-color and true-color wide panels and verify every panel row remains 100 columns, the cloud rows remain 20 columns before centering, and the approved contour appears unchanged after stripping ANSI.
4. Run `localcloud start` in an interactive terminal when the local Docker runtime is available and visually confirm the centered curved outline in the real startup panel. If the runtime is unavailable, report that limitation explicitly and retain the renderer smoke output as evidence.
5. Invoke the repository code-review workflow, address material findings, and rerun affected focused checks.
