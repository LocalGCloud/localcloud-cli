## 1. Data Layer — Categorization Logic & Sample Code Data

- [x] 1.1 Create a helper function `categorizeEnvVars(vars)` that takes the flat env var map from the API and returns three groups: `common` (keys matching `GOOGLE_CLOUD_PROJECT`, `GCLOUD_PROJECT`), `sdk` (keys matching `*_EMULATOR_HOST` or `REDIS_HOST`), and `gcloud` (keys matching `CLOUDSDK_*`)
- [x] 1.2 Create a mapping from SDK env var name to service metadata: `{ envVar → { serviceId, displayName, hasGcloud } }` — derived from the existing `ALL_SERVICES` array or the `/_localcloud/services` response
- [x] 1.3 Create a `SAMPLE_CODE` data structure: `{ serviceId → { python, nodejs, go, java, gcloud? } }` with short runnable code snippets for each enabled service (Cloud Storage, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery, Secret Manager, Cloud Tasks, Memorystore). Each snippet should be 3-8 lines showing a basic create/list operation.

## 2. UI Component — CopyableEnvVar Row

- [x] 2.1 Create a `CopyableEnvVar` component that renders: variable name (monospace, muted) + value (monospace, semi-bold) + copy icon button. Clicking the icon copies `export KEY="VALUE"` and shows a 2-second "Copied!" tooltip/indicator.
- [x] 2.2 Style the copy icon: use the existing clipboard SVG icon, 16x16, color `var(--text-tertiary)` default, `var(--primary)` on hover, with `cursor: pointer`

## 3. UI Component — ServiceEnvCard with Sample Code

- [x] 3.1 Create a `ServiceEnvCard` component: shows the service display name as a header, its `CopyableEnvVar` row(s), and a collapsible "Sample Code" toggle
- [x] 3.2 Implement the collapsible "Sample Code" section: collapsed by default, expands on click with a rotate-chevron animation. Contains a tab bar (Python / Node.js / Go / Java / gcloud CLI) and a code block showing the selected snippet
- [x] 3.3 Only show the "gcloud CLI" tab for services that have `gcloudApiName` defined (exclude Memorystore, Logging, Monitoring, Bigtable)

## 4. UI — Redesign Environment Variables Section in Settings.jsx

- [x] 4.1 Add the auto-configure quick-setup command at the top of the Environment Variables area: a highlighted card with the `eval "$(curl ...)"` command, a "Quick Setup" label, and a copy button
- [x] 4.2 Replace the flat env var code block with three category sections: "Common", "SDK Environment Variables", "gcloud CLI Overrides" — each as a card with a section header
- [x] 4.3 Render the **Common** section with `CopyableEnvVar` rows for project variables + a "Copy All" button
- [x] 4.4 Render the **SDK** section with a `ServiceEnvCard` for each enabled service (ordered: Storage, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery, Secret Manager, Cloud Tasks, Logging, Monitoring, Memorystore) + a "Copy All SDK" button
- [x] 4.5 Render the **gcloud CLI** section with `CopyableEnvVar` rows for all `CLOUDSDK_*` variables + a "Copy All gcloud" button
- [x] 4.6 Wire up all "Copy All" buttons to copy all variables in their category as `export` lines

## 5. Styling

- [x] 5.1 Add CSS for the env var row layout: flexbox row with key, value, and copy icon; consistent spacing; monospace font; hover highlight on the row
- [x] 5.2 Add CSS for the collapsible sample code section: smooth height transition, tab bar styling consistent with the existing User Guide modal tabs, code block with syntax-appropriate font size (12px)
- [x] 5.3 Add CSS for the quick-setup auto-configure card: subtle highlight background (using `var(--primary)` at low opacity), border-left accent, larger copy button
- [x] 5.4 Ensure all new styles work in both light and dark mode (use CSS variables only, no hardcoded colors)

## 6. Verification

- [x] 6.1 Build the console (`cd localcloud-console && npm run build`) and verify no build errors
- [x] 6.2 Manually verify: Settings page loads with three categorized sections, copy icons work on each variable, Copy All works per section, sample code expands/collapses with tabs, auto-configure command appears at top
