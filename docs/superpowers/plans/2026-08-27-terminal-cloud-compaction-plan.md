# Terminal Cloud Compaction Implementation Plan

**Design:** `docs/superpowers/specs/2026-08-27-terminal-cloud-compaction-design.md`

**Goal:** Replace the 20×5 terminal cloud with the approved 16×5 geometry while preserving animation, color fallbacks, responsive layouts, and all user-facing context.

## Task 1: Lock the new geometry in tests

**Files:** `tests/test_output.py`

1. Replace the exact artwork fixture with the approved five 16-column rows.
2. Change the cloud-width assertion from 20 to 16.
3. Assert `_CLOUD_PERIMETER_COORDS` covers every non-space glyph exactly once.
4. Keep color-mode and ANSI-stripped glyph-equivalence assertions.

**Acceptance:** The focused cloud test fails against the old geometry and fully describes the new observable contract.

## Task 2: Replace artwork and perimeter coordinates

**Files:** `src/localcloud_cli/output.py`

1. Replace `_CLOUD` with the approved 16×5 tuple.
2. Replace `_CLOUD_PERIMETER_COORDS` with the 40-glyph clockwise path for the new contour.
3. Leave gradient interpolation, phase selection, shine behavior, breakpoints, centering, and panel rendering unchanged.
4. Run the focused artwork and output tests.

**Acceptance:** Every perimeter glyph animates, no coordinate points to whitespace, and no rendered cloud row exceeds 16 visible columns.

## Task 3: Update public preview and current contracts

**Files:**
- `README.md`
- Current artwork and UI design documents

1. Regenerate the README's 40-column terminal preview from `render_panel`.
2. Ensure current design documents identify 16×5 as the active contract and point to the compaction rationale.
3. Preserve older 20×5 history only where it is explicitly marked superseded.

**Acceptance:** The README preview equals deterministic renderer output and no active documentation claims 20×5 is current.

## Task 4: Verify and clean up

1. Run focused output tests.
2. Run the complete non-Docker unit suite.
3. Render deterministic 40- and 100-column panels and assert row-width invariants.
4. Run PTY `uv run lc doctor` and visually confirm the compact colorful mark.
5. Remove `.superpowers/brainstorm` artifacts.
6. Commit the specification, plan, implementation, tests, and documentation.

**Acceptance:** Tests pass, the real CLI displays the centered 16×5 mark, and no temporary visual-companion files remain.
