# Terminal Cloud Compaction Design

## Goal

Make the LocalCloud terminal artwork less dominant at half-width windows without losing the established five-row silhouette, hollow contour, colorful perimeter animation, or responsive context layout.

## Approved Artwork

The selected mark is **16 columns by 5 rows**, a 20% width reduction from the previous 20×5 mark:

```text
     ╭────╮     
  ╭──╯    ╰──╮  
 ╭─╯        ╰─╮ 
╭╯            ╰╮
╰──────────────╯
```

Every row is exactly 16 visible columns. The height remains five rows because the shorter four-row proposals made the artwork feel reduced rather than compact. The 18×5 alternative was rejected because its 10% reduction provided too little additional room at half-window widths.

## Rendering Contract

`_CLOUD` remains a single global mark shared by wide, stacked, and compact panels. There is no second narrow-only artwork variant.

`_CLOUD_PERIMETER_COORDS` changes with the geometry so that:

- every non-space glyph appears exactly once in the perimeter path;
- the diagonal Google-color gradient remains continuous;
- animation changes color only, never glyphs or width;
- no-color output remains the exact five approved rows.

Panel breakpoints, the 40-column complete-context floor, headings, service markers, padding, lifecycle output, and the rule that `start`, `restart`, and `stop` do not show panels remain unchanged.

## Documentation

The terminal preview in `README.md` must be generated from the updated 40-column renderer. Existing design documents that name the previous 20×5 geometry must point to this specification as the superseding size contract rather than remain contradictory.

## Verification

Automated tests must assert:

1. exact unstyled 16×5 artwork;
2. 16-column visible width for every cloud row in every color mode;
3. complete, unique perimeter-coordinate coverage of every non-space glyph;
4. unchanged glyphs after stripping truecolor, ANSI-256, and ANSI-16 sequences;
5. aligned panels at 40, 50, 79, 80, and 100 columns;
6. README terminal preview equality with deterministic 40-column output.

Smoke verification must render 40- and 100-column panels and run PTY `lc doctor` to confirm the compact mark remains centered and colorful in the shipped command path.

## Alternatives Considered

### 14×4 proportional reduction

Rejected because reducing both width and height made the mark feel too short.

### 14×5 width-only reduction

Rejected because the 30% horizontal reduction made the five-row contour feel too narrow and visually small.

### 18×5 conservative reduction

Rejected because the 10% width reduction did not materially improve half-window spacing.

### Separate narrow-only artwork

Rejected because maintaining two marks would create visual drift and breakpoint-dependent brand identity.
