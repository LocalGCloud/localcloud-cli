# Terminal Cloud Logo Design

> **Geometry updated 2026-08-27:** The 16×5 size below supersedes the original 20×5 dimensions. See `2026-08-27-terminal-cloud-compaction-design.md` for the selection rationale.

## Goal

Replace the current dense startup cloud with a lighter, clearly curved LocalCloud mark. The replacement must fit the existing `localcloud start` and `localcloud restart` panels without changing panel dimensions, responsive behavior, color handling, or animation.

## Selected artwork

The approved mark is a symmetric, empty cloud outline made from single-column Unicode box-drawing characters. It is exactly 16 columns by 5 rows:

```text
     ╭────╮     
  ╭──╯    ╰──╮  
 ╭─╯        ╰─╮ 
╭╯            ╰╮
╰──────────────╯
```

The rounded boundary is the defining requirement. The empty interior avoids the visual weight that made the previous solid mark resemble a blob. The final contour balances the left and right shoulders while retaining a recognizable cloud silhouette.

## Integration

Replace the existing `_CLOUD` tuple in `src/localcloud_cli/output.py` with the approved five rows. Keep `render_cloud` and the panel renderers unchanged.

The existing renderer remains responsible for:

- applying the animated diagonal Google-color gradient to every non-space glyph;
- rendering the exact same artwork without ANSI escapes when color is disabled;
- centering the 16-column mark in wide, stacked, and compact panels;
- falling back to the existing minimal panel when the terminal is too narrow.

No new image files, configuration, rendering modes, or compatibility aliases are needed.

## Behavior and compatibility

Every row must retain a visible width of exactly 16 terminal columns. The artwork uses only spaces and single-column box-drawing characters, so the current character-width and centering logic remains valid.

The change is presentation-only. Startup sequencing, animation timing, phase selection, shine behavior, lifecycle reporting, stdout/stderr ownership, and terminal restoration are unchanged. There are no new error states or fallback paths.

## Verification

Update the cloud rendering test to assert the exact unstyled artwork in addition to the existing width and animation checks. This protects the approved contour from accidental substitution while retaining coverage that animated frames alter color only.

Run the focused output tests and render the actual CLI startup panel in a terminal. Verify that:

- the no-color output matches the five approved rows exactly;
- all five rows have visible width 16;
- true-color animation changes ANSI color without changing glyphs;
- wide, stacked, compact, and minimal panels remain aligned;
- the wide panel shows the compact curved outline centered in its existing 28-column cell.
